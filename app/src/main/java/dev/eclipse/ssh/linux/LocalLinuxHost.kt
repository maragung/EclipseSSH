package dev.eclipse.ssh.linux

import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostProfile

/**
 * The local Ubuntu userspace as the host list sees it: a host card that is there when the
 * environment is installed and healthy, and is not there otherwise — no card for "not installed",
 * no card for a broken install, because a card is a promise that tapping it opens a terminal.
 *
 * ## Why a synthesized profile
 *
 * The host list, the terminal tabs and the connect path all speak [HostProfile], and rewriting
 * them to speak a wider "host or local environment" type would touch every feature this app
 * already ships. So the local environment *is* a HostProfile — synthesized on the fly from the
 * userspace's state, never read from or written to the host repository. It has a reserved
 * [HOST_ID] so the connect path can recognize it (one `if`) and route to the local terminal
 * instead of dialing anything, and so the repository and the vault export can refuse to persist a
 * profile with that id: a synthesized host that survived into the vault would come back on another
 * device as a broken entry named "Local Ubuntu" that dials localhost.
 *
 * ## The card rule
 *
 * [shouldShowCard] is the one place that decides visibility, so the settings screen, the host list
 * and any future entry point cannot disagree: the userspace must be installed (not mid-install,
 * not mid-repair — a card that disappears mid-tap is worse than one that arrives a minute later)
 * and the last health probe must have passed. On uninstall the state leaves those values and the
 * card leaves with it, which is the spec's "auto-removed on uninstall" without a single line of
 * removal code — the card is derived, never registered.
 */
object LocalLinuxHost {

    /** Reserved: never persisted, never dialed. */
    const val HOST_ID = "local-linux-ubuntu"

    /** The card's title, exactly as the feature spec names it. */
    const val CARD_TITLE = "Local Ubuntu 22.04"

    /** The group the synthesized profile claims, so it sorts apart from real servers. */
    const val GROUP = "On this device"

    fun isLocalHost(hostId: String): Boolean = hostId == HOST_ID

    /**
     * Whether the host list should show the local Ubuntu card.
     *
     * Installing and NeedsRepair are deliberately excluded while Stopped/Starting/Running are
     * included: the card is a door, and a door mid-construction or broken open is not a door yet.
     * [health] is the last probe's report — `null` means no probe has ever run, which cannot
     * happen for a Stopped userspace (install ends with one), so a null here is treated as
     * unhealthy rather than trusted.
     */
    fun shouldShowCard(state: LinuxUserspaceState, health: HealthReport?): Boolean =
        when (state) {
            is LinuxUserspaceState.Stopped,
            is LinuxUserspaceState.Starting,
            is LinuxUserspaceState.Running,
            -> health?.healthy == true
            else -> false
        }

    /**
     * The synthesized profile. Every field the SSH path reads carries an honest placeholder: the
     * connect path never gets that far, because [isLocalHost] intercepts it first, but a stray
     * `localhost` dial that fails in two seconds is a better failure than a crash from a field
     * someone forgot to fill.
     */
    fun hostProfile(distro: LinuxDistro): HostProfile =
        HostProfile(
            id = HOST_ID,
            name = CARD_TITLE,
            host = "localhost",
            username = "ubuntu",
            port = 22,
            authMethod = AuthMethod.PASSWORD,
            group = GROUP,
            tags = emptyList(),
            // The local environment reconnects by construction - its "transport" is the app's own
            // process - so the reconnect ladder must never schedule attempts for it.
            autoReconnect = false,
        )
}

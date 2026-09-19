package dev.eclipse.ssh.ui.hosts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.HostRepository
import dev.eclipse.ssh.data.credentials.HostCredentialStore
import dev.eclipse.ssh.data.credentials.StoredCredentials
import dev.eclipse.ssh.data.credentials.describe
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.ProxyType
import dev.eclipse.ssh.data.model.ServerStats
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ui.actions.ActionAnswer
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.ActionSubject
import dev.eclipse.ssh.ui.actions.HostDetailsKind
import dev.eclipse.ssh.ui.settings.SettingsDestinationWindow
import javax.inject.Inject

/**
 * One host's own details: how it is reached, what is remembered for it, and what it is doing.
 *
 * It used to be a `ModalBottomSheet` opened from a host's kebab menu, and it is a window now for the
 * reason the rest of this app's sheets are: it is read *while* something else is being decided - which
 * key this host wants, whether its fingerprint was ever verified - and a sheet covers the list of
 * hosts it is describing while being unable to be read beside it.
 *
 * **It does not act, and it holds almost nothing.** The host itself and the credentials remembered for
 * it are read live from their own singletons by id, which is what makes the Credentials line honest:
 * the one row that changes it is in this window, and a line still reading "Password saved" after the
 * user forgot it would be this window lying about the user's own act. The Monitoring block is the one
 * snapshot, handed over in [ActionSubject.HostDetails], because the stats map is the workspace's - it
 * fetches them over a connection only it holds.
 *
 * That asymmetry is also why "Load server stats" closes the window: it is not a read this window can
 * perform, it is a request to the workspace, and the workspace only hears requests when it resumes.
 * Refresh closes it for the same reason - the snapshot it is showing is about to be replaced, and a
 * window cannot tell the difference between the old numbers and the new ones.
 *
 * The subject travels as a token rather than an id in the intent, because of that snapshot: an intent
 * carries the stats of a host, not a map. The manifest gives this the editor's `configChanges` list
 * and `singleTop` for the same reason every token window has them - a re-delivered intent must find
 * its token spent and close, not reopen a snapshot of something that has since moved on.
 */
@AndroidEntryPoint
class HostDetailsActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var hostRepository: HostRepository

    @Inject lateinit var credentialStore: HostCredentialStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val subject = ActionRequests.take(intent?.getStringExtra(ActionRequests.EXTRA_SUBJECT_TOKEN))
            as? ActionSubject.HostDetails
        if (subject == null) {
            finish()
            return
        }
        setContent {
            // Null until the repository has answered, which is what tells a first frame apart from a
            // host that has genuinely gone - the two are one frame apart and only one of them should
            // close the window. A host deleted while this was open has nothing left to describe.
            val hosts by hostRepository.hosts.collectAsStateWithLifecycle(initialValue = null)
            val host = hosts?.firstOrNull { it.id == subject.hostId }
            val credentials by credentialStore.credentials.collectAsStateWithLifecycle(
                initialValue = emptyMap(),
            )
            LaunchedEffect(hosts, host) {
                if (hosts != null && host == null) finish()
            }
            SettingsDestinationWindow(
                settingsRepository = settingsRepository,
                // The host's own name, not "Details": the window is opened from a menu that offers
                // the same entry on every row, and it has to say which row it belongs to.
                title = host?.name ?: "Host details",
                onClose = { finish() },
            ) {
                host?.let { profile ->
                    HostDetailsBody(
                        host = profile,
                        stats = subject.stats,
                        credentials = credentials[profile.id] ?: StoredCredentials(),
                        onAnswer = { answer ->
                            ActionRequests.answer(answer)
                            finish()
                        },
                    )
                }
            }
        }
    }

    companion object {

        /**
         * The intent that opens this window on [host].
         *
         * The host is not in here and does not need to be - the window reads it, and the credentials
         * saved for it, from their own singletons by id. What travels is the one thing the workspace
         * owns: the server stats it last heard, or null when it has heard nothing yet. Null is not
         * the same as "no stats exist" and the window says so by offering to load them.
         */
        fun intent(context: Context, host: HostProfile, stats: ServerStats?): Intent =
            Intent(context, HostDetailsActivity::class.java)
                .putExtra(
                    ActionRequests.EXTRA_SUBJECT_TOKEN,
                    ActionRequests.put(ActionSubject.HostDetails(host.id, stats)),
                )
    }
}

/**
 * The rows themselves, in the order the sheet had them: how this host is reached, what is remembered
 * for it, then what it is doing.
 *
 * The two rows that pass through the credentials line and the Monitoring block are the only
 * interactive things here, and both are answers rather than actions. What is deliberately *not* here
 * is the rest of the kebab menu - Connect, Port forwarding, Favorite, Export account, Edit and Remove
 * are all one tap away on the row this window was opened from, and repeating them here would be two
 * paths to each act.
 */
@Composable
private fun HostDetailsBody(
    host: HostProfile,
    stats: ServerStats?,
    credentials: StoredCredentials,
    onAnswer: (ActionAnswer) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text("${host.username}@${host.host}:${host.port}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        DetailLine("Authentication", host.authMethod.label)
        DetailLine("Group", host.group)
        DetailLine("Route", host.proxyType.label)
        if (host.proxyType == ProxyType.PROXY_JUMP) DetailLine("Jump host", host.proxyJump ?: "—")
        if (host.proxyType == ProxyType.SOCKS5 || host.proxyType == ProxyType.HTTP_CONNECT) {
            DetailLine(if (host.proxyType == ProxyType.HTTP_CONNECT) "HTTP proxy" else "SOCKS5", "${host.socksHost}:${host.socksPort}")
            if (!host.socksUsername.isNullOrBlank()) DetailLine("Proxy auth", host.socksUsername)
        }
        if (host.accentColor != null) DetailLine("Accent", "Custom")
        DetailLine("Fingerprint", host.fingerprint ?: "Not verified yet")
        DetailLine("Reconnect", "Automatic on network recovery")
        // Shown for every host, including those with nothing saved: "Asked at every connect" is the
        // answer to the question this line exists to answer, and leaving the row out when the answer
        // is "nothing" makes its absence indistinguishable from the app not tracking it.
        DetailLine("Credentials", credentials.describe())
        // The one row only this window can offer. It lives beside the Credentials line rather than in
        // the kebab menu because that is the line it changes - and because a menu the user opens for
        // other reasons is the wrong place for the one destructive entry in it.
        if (!credentials.isEmpty) {
            OutlinedButton(
                onClick = { onAnswer(ActionAnswer.HostDetailsAction(host.id, HostDetailsKind.FORGET_CREDENTIALS)) },
            ) { Text("Forget credentials") }
        }
        Spacer(Modifier.height(18.dp))
        Text("Monitoring".uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
        if (stats == null) {
            TextButton(
                onClick = { onAnswer(ActionAnswer.HostDetailsAction(host.id, HostDetailsKind.REFRESH_STATS)) },
            ) { Text("Load server stats") }
        } else {
            DetailLine("Hostname", stats.hostname)
            DetailLine("Load average", stats.loadAverage)
            DetailLine("Memory", "${stats.memoryUsed} / ${stats.memoryTotal}")
            DetailLine("Disk /", "${stats.diskUsed} / ${stats.diskTotal}")
            TextButton(
                onClick = { onAnswer(ActionAnswer.HostDetailsAction(host.id, HostDetailsKind.REFRESH_STATS)) },
            ) { Text("Refresh stats") }
        }
    }
}

/** One label/value pair, the shape every row of this window takes. */
@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(
            label,
            Modifier.width(115.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(value, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
    }
}

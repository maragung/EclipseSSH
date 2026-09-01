package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.ProxyType
import org.junit.Test

class OpenSshConfigParserTest {
    @Test
    fun `parses multiple host blocks`() {
        val config = """
            Host web
                HostName web.example.com
                User deploy
                Port 2222

            Host db
                HostName 10.0.0.5
                User admin
        """.trimIndent()

        val hosts = OpenSshConfigParser.parse(config)

        assertThat(hosts).hasSize(2)
        assertThat(hosts[0].name).isEqualTo("web")
        assertThat(hosts[0].host).isEqualTo("web.example.com")
        assertThat(hosts[0].username).isEqualTo("deploy")
        assertThat(hosts[0].port).isEqualTo(2222)
        assertThat(hosts[1].host).isEqualTo("10.0.0.5")
        assertThat(hosts[1].port).isEqualTo(22)
    }

    @Test
    fun `skips wildcard patterns and comments`() {
        val config = """
            # production wildcard
            Host *.example.com
                User root
            Host web
                HostName web.example.com
        """.trimIndent()

        val hosts = OpenSshConfigParser.parse(config)

        assertThat(hosts).hasSize(1)
        assertThat(hosts.single().name).isEqualTo("web")
    }

    @Test
    fun `maps proxyjump and hostname port shorthand`() {
        val config = """
            Host jump
                HostName bastion:2200
                User ops
                ProxyJump gateway@proxy.corp:22
        """.trimIndent()

        val host = OpenSshConfigParser.parse(config).single()

        assertThat(host.host).isEqualTo("bastion")
        assertThat(host.port).isEqualTo(2200)
        assertThat(host.proxyType).isEqualTo(ProxyType.PROXY_JUMP)
        assertThat(host.proxyJump).isEqualTo("gateway@proxy.corp:22")
    }

    /**
     * ConnectTimeout and ServerAliveInterval map onto the per-host connection options.
     *
     * An imported profile has to connect the way the user's own `ssh_config` says it does;
     * silently dropping these two meant a host tuned for a slow link arrived with the 15-second
     * default and looked broken.
     */
    @Test
    fun `maps connecttimeout and serveraliveinterval onto connection options`() {
        val config = """
            Host slow
                HostName slow.example.com
                User deploy
                ConnectTimeout 120
                ServerAliveInterval 45
        """.trimIndent()

        val host = OpenSshConfigParser.parse(config).single()

        assertThat(host.connectTimeoutSeconds).isEqualTo(120)
        assertThat(host.keepAliveSeconds).isEqualTo(45)
    }

    /** Directive names are case-insensitive in ssh_config, so the parser must not care either. */
    @Test
    fun `connection option keywords are case insensitive`() {
        val config = """
            Host mixed
                hostname mixed.example.com
                connecttimeout 30
                SERVERALIVEINTERVAL 60
        """.trimIndent()

        val host = OpenSshConfigParser.parse(config).single()

        assertThat(host.connectTimeoutSeconds).isEqualTo(30)
        assertThat(host.keepAliveSeconds).isEqualTo(60)
    }

    /**
     * A block with no ConnectTimeout keeps the app default, and a `ConnectTimeout 0` does too.
     *
     * Zero is not "give up immediately" in ssh_config — it means "use the system default" — so
     * honouring it literally would turn every such host into an instant timeout. Out-of-range
     * values fall back rather than clamp for the same reason: the file is stating an intent the
     * app cannot express, and the safe reading of an unexpressible intent is the default.
     */
    @Test
    fun `out of range connection options fall back to the defaults`() {
        val config = """
            Host zero
                HostName zero.example.com
                ConnectTimeout 0
                ServerAliveInterval 0

            Host huge
                HostName huge.example.com
                ConnectTimeout 99999
                ServerAliveInterval 99999

            Host junk
                HostName junk.example.com
                ConnectTimeout yes
                ServerAliveInterval sometimes

            Host bare
                HostName bare.example.com
        """.trimIndent()

        val hosts = OpenSshConfigParser.parse(config)

        assertThat(hosts).hasSize(4)
        assertThat(hosts.map { it.connectTimeoutSeconds })
            .containsExactly(15, 15, 15, 15)
        assertThat(hosts.map { it.keepAliveSeconds }).containsExactly(null, null, null, null)
    }

    /** Both ends of each range are legitimate and must survive the import untouched. */
    @Test
    fun `connection options at the range bounds are preserved`() {
        val config = """
            Host low
                HostName low.example.com
                ConnectTimeout 5
                ServerAliveInterval 5

            Host high
                HostName high.example.com
                ConnectTimeout 300
                ServerAliveInterval 600
        """.trimIndent()

        val hosts = OpenSshConfigParser.parse(config)

        assertThat(hosts.map { it.connectTimeoutSeconds }).containsExactly(5, 300).inOrder()
        assertThat(hosts.map { it.keepAliveSeconds }).containsExactly(5, 600).inOrder()
    }

    /**
     * The pure pattern classifier, tested directly: negated tokens are exclusions, wildcard tokens are
     * families, and only the positive literal aliases identify a host — the first names the draft and
     * the rest are its tags.
     *
     * Would fail without the change: the classifier did not exist. The old parser inlined a filter that
     * dropped only wildcard tokens and treated a `!token` as an ordinary literal alias.
     */
    @Test
    fun `importableHostLine keeps concrete aliases and drops negations and wildcards`() {
        val mixed = importableHostLine("web !web-legacy *.example.com web.prod")
        assertThat(mixed.aliases).containsExactly("web", "web.prod").inOrder()
        assertThat(mixed.label).isEqualTo("web")
        assertThat(mixed.matchedHost).isEqualTo("web")

        val familyOnly = importableHostLine("*.example.com")
        assertThat(familyOnly.aliases).isEmpty()
        assertThat(familyOnly.label).isEqualTo("*.example.com")
        assertThat(familyOnly.matchedHost).isNull()

        val negationOnly = importableHostLine("!only")
        assertThat(negationOnly.label).isNull()
    }

    /**
     * A wildcard `Host` line with a concrete `HostName` now imports, where the old parser dropped it.
     * The wildcard says which names the rule matches; `HostName` says the one place they all go, and
     * that endpoint is a real host worth saving.
     *
     * Would fail before: the old filter removed every wildcard token, left the block with no pattern,
     * and returned nothing for it.
     */
    @Test
    fun `imports a wildcard host with a concrete hostname`() {
        val config = """
            Host *.example.com
                HostName bastion.example.com
                User deploy
        """.trimIndent()

        val host = OpenSshConfigParser.parse(config).single()

        assertThat(host.host).isEqualTo("bastion.example.com")
        assertThat(host.username).isEqualTo("deploy")
        assertThat(host.name).isEqualTo("*.example.com")
    }

    /**
     * `%h` in `HostName` expands to the matched host, which for a concrete alias is the alias itself.
     *
     * Would fail before: the old parser copied `HostName` verbatim, so this host's address was the
     * literal string `%h.internal.example.com`, which resolves to nothing.
     */
    @Test
    fun `expands %h in hostname to the matched alias`() {
        val config = """
            Host web
                HostName %h.internal.example.com
                User deploy
        """.trimIndent()

        val host = OpenSshConfigParser.parse(config).single()

        assertThat(host.host).isEqualTo("web.internal.example.com")
    }

    /**
     * Several plain aliases on one line collapse to a single draft — the first names it, the rest are
     * tags — and `%h` resolves to that first alias. One machine with several names is the common meaning
     * of a multi-alias line, so one reviewable draft with the others searchable as tags is the honest
     * import.
     *
     * Would fail before: `%h` was not expanded, so the address was the literal `%h.internal`.
     */
    @Test
    fun `collapses a multi-alias %h block onto its first alias`() {
        val config = """
            Host web db
                HostName %h.internal
        """.trimIndent()

        val host = OpenSshConfigParser.parse(config).single()

        assertThat(host.name).isEqualTo("web")
        assertThat(host.host).isEqualTo("web.internal")
        assertThat(host.tags).containsExactly("db")
    }

    /**
     * A negated pattern is an exclusion, never an alias, so it is dropped rather than kept as a name or
     * a tag.
     *
     * Would fail before: `!prod-legacy` contains no wildcard, so the old filter kept it and it arrived
     * as a bogus tag on the imported host.
     */
    @Test
    fun `drops negated patterns instead of importing them as aliases`() {
        val config = """
            Host prod !prod-legacy
                HostName 10.0.0.9
        """.trimIndent()

        val host = OpenSshConfigParser.parse(config).single()

        assertThat(host.name).isEqualTo("prod")
        assertThat(host.tags).isEmpty()
    }

    /**
     * A wildcard family whose `HostName` is the matched name (`%h`) has no single host behind it and no
     * command-line name to pin it to, so it is skipped.
     *
     * Would fail before: the old filter dropped the `*` token but kept the negated `!web` as a literal
     * alias and copied the unresolved `%h.example.com` as the address, saving a host wrong in two ways.
     */
    @Test
    fun `skips a wildcard family whose hostname is the matched name`() {
        val config = """
            Host * !web
                HostName %h.example.com
        """.trimIndent()

        assertThat(OpenSshConfigParser.parse(config)).isEmpty()
    }

    /**
     * A block that names no `User` imports with a blank username, not `root`. The app cannot invent a
     * username any more than it can invent a hostname (`HostFormDraft.identityValid` says exactly this),
     * a blank one shows as `@host` and is refused by that form the moment the host is edited, and the
     * old default was the one guess with real downside — silently dialling a locked-down root account.
     *
     * Would fail before: the missing `User` became `root`.
     */
    @Test
    fun `leaves the username blank when the config names no user`() {
        val config = """
            Host withuser
                HostName a.example.com
                User deploy

            Host nouser
                HostName b.example.com
        """.trimIndent()

        val hosts = OpenSshConfigParser.parse(config)

        assertThat(hosts.map { it.username }).containsExactly("deploy", "").inOrder()
    }

    /**
     * `IdentityFile` is ignored: it points at a key file on the machine that wrote the config, a path
     * that does not exist in the app sandbox. The rest of the block still imports as an ordinary host;
     * the key is attached later through the form.
     *
     * Behaviour the previous parser also had — pinned here so the deliberate decision cannot be undone
     * by accident.
     */
    @Test
    fun `ignores IdentityFile and imports the rest of the block`() {
        val config = """
            Host key
                HostName key.example.com
                User deploy
                IdentityFile ~/.ssh/id_ed25519
        """.trimIndent()

        val host = OpenSshConfigParser.parse(config).single()

        assertThat(host.host).isEqualTo("key.example.com")
        assertThat(host.username).isEqualTo("deploy")
    }
}

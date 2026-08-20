package dev.eclipse.ssh.ssh

import java.io.InputStream
import java.security.KeyPair
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.common.util.security.SecurityUtils

object SshKeyLoader {
    fun load(bytes: ByteArray, displayName: String, passphrase: String? = null): KeyPair =
        load(bytes.inputStream(), displayName, passphrase)

    fun load(input: InputStream, displayName: String, passphrase: String? = null): KeyPair {
        val passwordProvider = object : FilePasswordProvider {
            override fun getPassword(session: SessionContext?, resourceKey: NamedResource?, retryIndex: Int): String = passphrase.orEmpty()
        }
        return SecurityUtils.loadKeyPairIdentities(
            null,
            NamedResource.ofName(displayName),
            input,
            passwordProvider,
        ).firstOrNull() ?: error("No supported private key found in $displayName")
    }
}

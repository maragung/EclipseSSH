package dev.eclipse.ssh.data

import dev.eclipse.ssh.data.local.HostDao
import dev.eclipse.ssh.data.local.asDomain
import dev.eclipse.ssh.data.local.toEntity
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.security.SecureVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface HostRepository {
    val hosts: Flow<List<HostProfile>>
    suspend fun save(host: HostProfile)
    suspend fun delete(host: HostProfile)
    suspend fun seedIfEmpty()
}

class RoomHostRepository(
    private val dao: HostDao,
    private val vault: SecureVault,
) : HostRepository {
    // The SOCKS5 proxy password is stored encrypted at rest (AES-256-GCM via Android
    // Keystore); the in-memory domain model carries the plaintext only while in use.
    override val hosts: Flow<List<HostProfile>> = dao.observeAll().asDomain().map { profiles ->
        profiles.map { profile ->
            if (profile.socksPassword == null) profile
            else profile.copy(socksPassword = decryptSafe(profile.socksPassword))
        }
    }

    override suspend fun save(host: HostProfile) {
        val encrypted = host.copy(
            socksPassword = host.socksPassword?.takeIf(String::isNotBlank)?.let(vault::encrypt),
        )
        dao.upsert(encrypted.toEntity())
    }

    override suspend fun delete(host: HostProfile) = dao.delete(host.toEntity())

    private fun decryptSafe(encrypted: String): String? =
        runCatching { vault.decrypt(encrypted) }.getOrNull()

    override suspend fun seedIfEmpty() {
        if (dao.count() == 0) {
            dao.upsert(
                HostProfile(
                    id = "eclipse-demo",
                    name = "Production edge",
                    host = "edge.example.com",
                    username = "deploy",
                    authMethod = AuthMethod.SSH_KEY,
                    group = "Work",
                    tags = listOf("production", "favorite"),
                    isFavorite = true,
                    // No seeded fingerprint. It used to carry the placeholder "SHA256:7m3…Pq9",
                    // which is now actively harmful: saving a host promotes its pinned fingerprint
                    // to a trusted known-hosts entry, so a made-up pin would make the very first
                    // real connection fail as a *changed* host key — the alarm that is supposed to
                    // mean "you are being MITM'd". A host nobody has connected to knows nothing
                    // about its key, and null says exactly that.
                ).toEntity(),
            )
            dao.upsert(
                HostProfile(
                    id = "eclipse-staging",
                    name = "Staging cluster",
                    host = "staging.example.com",
                    username = "ubuntu",
                    group = "Work",
                    tags = listOf("staging"),
                ).toEntity(),
            )
            dao.upsert(
                HostProfile(
                    id = "eclipse-lab",
                    name = "Home lab",
                    host = "192.168.1.42",
                    username = "alex",
                    group = "Personal",
                    tags = listOf("nas", "homelab"),
                ).toEntity(),
            )
        }
    }
}

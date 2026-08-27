package dev.eclipse.ssh.feature.qstile

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.eclipse.ssh.data.HostRepository
import dev.eclipse.ssh.data.model.HostProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Resolves the most-recently-used host for the Quick Settings tile and
 * the home-screen widget.
 *
 * Both surfaces show "Connect: <name>" on tap and need the answer in
 * the click handler — a tile that takes 200 ms to show its label is one
 * a user stops tapping. [lastConnected] blocks the calling thread for
 * the one-shot read; the host list is small (a few hundred rows) and the
 * query is `ORDER BY lastConnectedAt DESC LIMIT 1`, which is what makes
 * a synchronous read acceptable.
 */
interface LastHostProvider {
    suspend fun lastConnected(): HostProfile?
}

@Singleton
class LastHostProviderImpl @Inject constructor(
    private val repository: HostRepository,
) : LastHostProvider {
    override suspend fun lastConnected(): HostProfile? {
        // The repository exposes a Flow<List<HostProfile>>; `first()`
        // takes the first emission. The host list is loaded eagerly on
        // app start, so the first emission arrives within a frame.
        val all = repository.hosts.first()
        return all
            .filter { it.lastConnectedAt != null }
            .maxByOrNull { it.lastConnectedAt ?: 0L }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LastHostProviderModule {
    @Binds
    abstract fun bindLastHostProvider(impl: LastHostProviderImpl): LastHostProvider
}

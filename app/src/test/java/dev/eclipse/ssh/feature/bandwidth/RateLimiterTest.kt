package dev.eclipse.ssh.feature.bandwidth

import com.google.common.truth.Truth.assertThat
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `unlimited limiter charges immediately`() = runTest {
        val lim = RateLimiter.UNLIMITED
        // 1 GiB worth of charges in zero time.
        val elapsed = measureTimeMillis { lim.await(1024L * 1024 * 1024) }
        assertThat(elapsed).isLessThan(50L)
    }

    @Test
    fun `zero or negative bytes are a noop`() = runTest {
        val lim = RateLimiter(bytesPerSecond = 1000)
        lim.await(0)
        lim.await(-1)
        // No throw, no state corruption.
    }

    @Test
    fun `a 1 KB-per-second limiter holds a 1 KB chunk for roughly one second`() = runTest {
        val lim = RateLimiter(bytesPerSecond = 1024, bucketSize = 1024)
        val elapsed = measureTimeMillis { lim.await(1024) }
        // The bucket starts full, so the first charge is free; the second
        // 1 KB has to wait the ~1s it takes to refill 1024 tokens at
        // 1 KB/s. Generous bound for a busy CI host.
        val elapsedSecond = measureTimeMillis { lim.await(1024) }
        assertThat(elapsedSecond).isAtLeast(800L)
    }

    @Test
    fun `the bucket refills over time without any explicit charge`() = runTest {
        val lim = RateLimiter(bytesPerSecond = 4096, bucketSize = 4096)
        // Drain the bucket.
        lim.await(4096)
        // Wait for it to refill.
        kotlinx.coroutines.delay(1100L)
        // Now a 1 KB charge should be essentially free. Generous bound
        // because the test JVM is shared with other tenants.
        val elapsed = measureTimeMillis { lim.await(1024) }
        assertThat(elapsed).isLessThan(500L)
    }
}

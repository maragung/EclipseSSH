package dev.eclipse.ssh.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HostSearchTest {
    private val host = HostProfile(
        name = "Production edge",
        host = "edge.example.com",
        username = "deploy",
        group = "Work",
        tags = listOf("production", "priority"),
    )

    @Test
    fun `matches name host username group and tags`() {
        listOf("production edge", "edge.example", "deploy", "work", "priority").forEach { query ->
            assertThat(host.matchesQuery(query)).isTrue()
        }
    }

    @Test
    fun `matching is case insensitive and trims whitespace`() {
        assertThat(host.matchesQuery("  PRODUCTION  ")).isTrue()
    }

    @Test
    fun `unrelated query does not match`() {
        assertThat(host.matchesQuery("database")).isFalse()
    }

    @Test
    fun `blank query matches every host`() {
        assertThat(host.matchesQuery("   ")).isTrue()
    }
}

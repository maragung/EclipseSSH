package dev.eclipse.ssh.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class Socks5HandshakeTest {

    @Test
    fun `connect request uses ipv4 address type for dotted quad`() {
        val bytes = Socks5Handshake.connectRequestBytes("192.168.1.50", 22)
        // VER=5, CMD=1, RSV=0, ATYP=1(IPv4), 4 octets, port 22 (0x00 0x16)
        assertArrayEquals(
            byteArrayOf(5, 1, 0, 1, 192.toByte(), 168.toByte(), 1, 50, 0, 22),
            bytes,
        )
    }

    @Test
    fun `connect request uses domain address type with length prefix`() {
        val bytes = Socks5Handshake.connectRequestBytes("example.com", 1080)
        // VER=5, CMD=1, RSV=0, ATYP=3(domain), len=11, "example.com", port 1080 (0x04 0x38)
        val expected = byteArrayOf(5, 1, 0, 3, 11) +
            "example.com".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x04, 0x38.toByte())
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `connect request encodes port big endian`() {
        val bytes = Socks5Handshake.connectRequestBytes("10.0.0.1", 65535)
        // last two bytes are the port 0xFF 0xFF
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), bytes.copyOfRange(bytes.size - 2, bytes.size))
    }

    @Test
    fun `connect request rejects overlong hostname`() {
        val longHost = "a".repeat(256)
        assertThrows(IOException::class.java) {
            Socks5Handshake.connectRequestBytes(longHost, 22)
        }
    }

    @Test
    fun `negotiation offers no-auth only without credentials`() {
        assertArrayEquals(byteArrayOf(5, 1, 0), Socks5Handshake.negotiationBytes(hasCredentials = false))
    }

    @Test
    fun `negotiation offers no-auth plus username-password with credentials`() {
        assertArrayEquals(byteArrayOf(5, 2, 0, 2), Socks5Handshake.negotiationBytes(hasCredentials = true))
    }

    @Test
    fun `auth request encodes rfc1929 username and password`() {
        val bytes = Socks5Handshake.authRequestBytes("alice", "s3cret")
        // VER=1, ULEN=5, "alice", PLEN=6, "s3cret"
        val expected = byteArrayOf(1, 5) +
            "alice".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(6) +
            "s3cret".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `auth request rejects overlong username`() {
        assertThrows(IOException::class.java) {
            Socks5Handshake.authRequestBytes("a".repeat(256), "pw")
        }
    }

    // ---------------------------------------------------------------------
    // IPv6 targets. An IPv6 literal sent as ATYP=domain asks the proxy to
    // resolve "2001:db8::1" as a hostname, which no conforming proxy can do,
    // so the address type and the 16-byte expansion both matter.
    // ---------------------------------------------------------------------

    @Test
    fun `connect request uses ipv6 address type for literal`() {
        val bytes = Socks5Handshake.connectRequestBytes("2001:db8::1", 22)
        val expected = byteArrayOf(5, 1, 0, 4) +
            byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1) +
            byteArrayOf(0, 22)
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `connect request expands loopback compression to sixteen bytes`() {
        val address = Socks5Handshake.parseIpv6("::1")
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1), address)
    }

    @Test
    fun `connect request accepts bracketed form and drops the zone index`() {
        // A scope id only means something on the local host; the proxy cannot use it.
        val address = Socks5Handshake.parseIpv6("[fe80::1%wlan0]")
        assertArrayEquals(
            byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            address,
        )
    }

    @Test
    fun `connect request expands an ipv4 mapped tail`() {
        val address = Socks5Handshake.parseIpv6("::ffff:192.168.1.1")
        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(),
                192.toByte(), 168.toByte(), 1, 1,
            ),
            address,
        )
    }

    @Test
    fun `connect request accepts a full eight group address`() {
        val address = Socks5Handshake.parseIpv6("2001:0db8:85a3:0000:0000:8a2e:0370:7334")
        assertArrayEquals(
            byteArrayOf(
                0x20, 0x01, 0x0d, 0xb8.toByte(), 0x85.toByte(), 0xa3.toByte(), 0, 0,
                0, 0, 0x8a.toByte(), 0x2e, 0x03, 0x70, 0x73, 0x34,
            ),
            address,
        )
    }

    @Test
    fun `malformed ipv6 literals are rejected rather than sent as hostnames`() {
        // Two compressions are ambiguous; too few or too many groups cannot fill 16 bytes;
        // 'g' is not a hex digit; a stray third colon leaves an empty group.
        listOf("2001:db8::1::2", "1:2:3", "1:2:3:4:5:6:7:8:9", "gggg::1", ":::1", "2001:db8:::1")
            .forEach { literal ->
                assertNull("expected $literal to be rejected", Socks5Handshake.parseIpv6(literal))
                assertThrows(IOException::class.java) {
                    Socks5Handshake.connectRequestBytes(literal, 22)
                }
            }
    }

    /**
     * A dotted-quad only means anything as the last 32 bits of the address.
     *
     * `1.2.3.4::` used to be accepted, because the dotted-quad rule was applied per side of the "::"
     * rather than to the address as a whole: 1.2.3.4 was taken as the *leading* four bytes and the
     * rest zero-filled, so the connector asked the proxy for 1.2.3.4:: — an address the user never
     * wrote and, on the wire, a different host. Refusing is the only right answer for a literal that
     * is not one.
     */
    @Test
    fun `a dotted quad before the compression is refused`() {
        listOf("1.2.3.4::", "1.2.3.4::1", "::1.2.3.4:5", "1.2.3.4::5:6")
            .forEach { literal ->
                assertNull("expected $literal to be rejected", Socks5Handshake.parseIpv6(literal))
                assertThrows(IOException::class.java) {
                    Socks5Handshake.connectRequestBytes(literal, 22)
                }
            }
    }

    /** The legal placement still works, so the rule above did not cost the mapped-address form. */
    @Test
    fun `a dotted quad after the compression is still accepted`() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 1),
            Socks5Handshake.parseIpv6("::10.0.0.1"),
        )
        assertArrayEquals(
            byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 1),
            Socks5Handshake.parseIpv6("2001:db8::10.0.0.1"),
        )
    }

    @Test
    fun `hostnames and dotted quads are not mistaken for ipv6`() {
        assertNull(Socks5Handshake.parseIpv6("example.com"))
        assertNull(Socks5Handshake.parseIpv6("192.168.1.50"))
        // Still encoded as a domain so the proxy performs the lookup.
        assertArrayEquals(byteArrayOf(5, 1, 0, 3), Socks5Handshake.connectRequestBytes("example.com", 22).copyOfRange(0, 4))
    }
}

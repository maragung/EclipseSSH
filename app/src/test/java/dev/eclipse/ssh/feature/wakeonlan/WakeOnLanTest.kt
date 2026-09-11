package dev.eclipse.ssh.feature.wakeonlan

import com.google.common.truth.Truth.assertThat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

/**
 * The Wake-on-LAN sender, on the plain JVM.
 *
 * The packet is the entire feature: 102 bytes in a fixed layout, and one wrong byte means the NIC
 * never recognises its own address. The send itself is exercised against a loopback socket rather
 * than a real broadcast, which is why [WakeOnLan.wake] takes its destination as parameters instead
 * of holding it as constructor state.
 */
class WakeOnLanTest {

    private val mac = byteArrayOf(0x4C.toByte(), 0x2E.toByte(), 0x81.toByte(), 0x1A.toByte(), 0x02.toByte(), 0xF7.toByte())

    @Test
    fun `the magic packet is six sync bytes followed by sixteen copies of the mac`() {
        val packet = WakeOnLan.magicPacket(mac)

        assertThat(packet).hasLength(102)
        // The sync stream: 0xFF six times, then the address sixteen times, nothing else.
        assertThat(packet.copyOfRange(0, 6).toHexString()).isEqualTo("ffffffffffff")
        for (repeat in 0 until 16) {
            val slice = packet.copyOfRange(6 + repeat * 6, 6 + (repeat + 1) * 6)
            assertThat(slice).isEqualTo(mac)
        }
    }

    @Test
    fun `the magic packet refuses a mac that is not six bytes`() {
        val thrown = runCatching { WakeOnLan.magicPacket(byteArrayOf(1, 2, 3)) }.exceptionOrNull()

        // A wrong-length MAC silently shifts every repetition and produces a packet that wakes
        // nothing, so this is refused at build time rather than sent and hoped for.
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `parseMac accepts colons dashes and bare hex in any case`() {
        val expected = byteArrayOf(0xAA.toByte(), 0x0B.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0x01.toByte())

        assertThat(parseMac("AA:0B:CC:DD:EE:01")).isEqualTo(expected)
        assertThat(parseMac("aa:0b:cc:dd:ee:01")).isEqualTo(expected)
        assertThat(parseMac("AA-0B-CC-DD-EE-01")).isEqualTo(expected)
        assertThat(parseMac("AA0BCCDDEE01")).isEqualTo(expected)
        // Surrounding whitespace is tolerated: a paste from a label or a router page often brings it.
        assertThat(parseMac("  AA:0B:CC:DD:EE:01 ")).isEqualTo(expected)
    }

    @Test
    fun `parseMac rejects junk rather than guessing at what was meant`() {
        // Too few pairs, and too many.
        assertThat(parseMac("AA:BB:CC:DD:EE")).isNull()
        assertThat(parseMac("AABBCCDDEE")).isNull()
        assertThat(parseMac("AA:BB:CC:DD:EE:01:02")).isNull()
        assertThat(parseMac("AABBCCDDEE0111")).isNull()
        // Non-hex pairs.
        assertThat(parseMac("AA:BB:CC:DD:EE:ZZ")).isNull()
        assertThat(parseMac("AA-BB-CC-DD-EE-0G")).isNull()
        // Separators that do not match in kind: a mixed spelling is a paste error, not a MAC, and
        // accepting it would store text the form could not re-validate.
        assertThat(parseMac("AA:BB-CC:DD:EE:01")).isNull()
        // Nothing at all, including the whitespace-only case the trim must not mistake for a MAC.
        assertThat(parseMac("")).isNull()
        assertThat(parseMac("   ")).isNull()
    }

    @Test
    fun `a send against a loopback socket delivers the exact 102 bytes`() {
        // A listener thread with a latch, the same shape the socket tests elsewhere in this project
        // use: the sender is fire-and-forget, so the test has to block on something other than a
        // reply that will never come.
        val receiver = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val received = CountDownLatch(1)
        var datagram = ByteArray(0)
        Thread {
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            receiver.receive(packet)
            datagram = buffer.copyOf(packet.length)
            received.countDown()
        }.also { it.isDaemon = true }.start()
        try {
            WakeOnLan().wake(mac, address = "127.0.0.1", port = receiver.localPort)

            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(datagram).hasLength(102)
            assertThat(datagram).isEqualTo(WakeOnLan.magicPacket(mac))
        } finally {
            receiver.close()
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}

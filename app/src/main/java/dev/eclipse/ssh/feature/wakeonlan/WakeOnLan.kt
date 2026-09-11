package dev.eclipse.ssh.feature.wakeonlan

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject

/**
 * Sends the Wake-on-LAN "magic packet" that brings a sleeping machine back.
 *
 * The packet is one UDP datagram: six `0xFF` bytes followed by the target's MAC address repeated
 * sixteen times, 102 bytes in all. There is no handshake, no reply and no acknowledgement — the
 * sleeping machine's network card hears the datagram, recognises its own address inside it, and
 * asserts the motherboard's power-on line. Everything after that (the OS booting, SSH coming up) is
 * the machine's own business, which is why this feature reports "packet sent" and never "host awake":
 * nothing short of trying to connect could tell the difference, and a connect that arrives before the
 * boot finishes would report a failure the wake actually caused.
 *
 * **Why broadcast.** A machine that is asleep has no IP address: DHCP leases expire, and even a
 * static one is useless because nothing behind the NIC is listening. The only address that can reach
 * a sleeping card is the LAN broadcast, which every NIC passes to its Wake-on-LAN circuitry whatever
 * the OS is doing. That is also the feature's hard limit, and the form says so: the phone has to be
 * on the same LAN as the target. A packet sent from another network stops at the first router —
 * routers do not forward broadcasts — so a host woken over a VPN, mobile data or a different Wi-Fi
 * stays asleep, silently. The same holds for the SSH host's own proxy settings: a ProxyJump or SOCKS
 * route is an *SSH* construct and is deliberately ignored here, because there is no SSH session to
 * carry anything and no server awake to tunnel through.
 *
 * **Why port 9.** The discard service's port is the canonical Wake-on-LAN port and the one nearly
 * every NIC and management controller listens on. The receiving end answers to the packet's *content*
 * at the data-link layer rather than to its port number, but a specific port still has to be named
 * to send UDP at all, and 9 is the one that maximises the number of cards that see it.
 *
 * Constructed with no arguments so Hilt can inject it wherever a host-scoped action needs it, the
 * same as [dev.eclipse.ssh.ssh.PortForwardingManager]. The destination address and port are
 * parameters with defaults rather than constructor state, because they are per-packet facts, and the
 * defaults are exactly what the tests override to point the datagram at a loopback socket instead of
 * a real broadcast.
 */
class WakeOnLan @Inject constructor() {

    /**
     * Sends one magic packet for [mac] to [address]:[port], and returns when the datagram is on the
     * wire.
     *
     * A network call: callers must run it away from the main thread, exactly as they would a connect
     * attempt. It throws whatever the socket throws — unknown host, no route, network gone — and
     * deliberately does not catch anything itself, because the caller is the one holding the host's
     * name and the snackbar that a failure should reach.
     */
    fun wake(mac: ByteArray, address: String = BROADCAST_ADDRESS, port: Int = DEFAULT_PORT) {
        val packet = magicPacket(mac)
        val destination = InetAddress.getByName(address)
        DatagramSocket().use { socket ->
            // 255.255.255.255 is refused outright by several platform stacks without SO_BROADCAST,
            // and a directed broadcast (x.x.x.255) needs it everywhere.
            socket.broadcast = true
            socket.send(DatagramPacket(packet, packet.size, destination, port))
        }
    }

    companion object {
        /** The LAN broadcast address — the only one a sleeping NIC can be reached on. */
        const val BROADCAST_ADDRESS = "255.255.255.255"

        /**
         * The discard port, which is Wake-on-LAN's canonical port.
         *
         * Named rather than inlined so the form's helper text, the sender and the tests cannot drift
         * apart on what the app actually uses.
         */
        const val DEFAULT_PORT = 9

        /** One MAC address, in bytes. Not six-and-something: a magic packet has no room for variance. */
        const val MAC_LENGTH = 6

        /** The sixteen `0xFF` bytes that lead a magic packet and mark it as one. */
        const val SYNC_LENGTH = 6

        /** How many times the MAC is repeated after the sync stream — sixteen, per the spec. */
        const val MAC_REPEATS = 16

        /**
         * The 102-byte datagram for [mac]: `FF FF FF FF FF FF` then the address sixteen times.
         *
         * A plain builder with no cleverness, because there is nothing to be clever about — every
         * byte of the layout is fixed by the spec the NICs implement, and the one thing this function
         * must never do is accept a MAC of the wrong length, which would silently shift every
         * repetition and produce a packet that wakes nothing.
         */
        fun magicPacket(mac: ByteArray): ByteArray {
            require(mac.size == MAC_LENGTH) { "A Wake-on-LAN MAC is $MAC_LENGTH bytes, got ${mac.size}" }
            return ByteArray(SYNC_LENGTH + MAC_REPEATS * MAC_LENGTH).also { packet ->
                for (i in 0 until SYNC_LENGTH) packet[i] = 0xFF.toByte()
                for (repeat in 0 until MAC_REPEATS) {
                    for (i in 0 until MAC_LENGTH) {
                        packet[SYNC_LENGTH + repeat * MAC_LENGTH + i] = mac[i]
                    }
                }
            }
        }
    }
}

/**
 * Reads a MAC address in any of the spellings a user might type, or null if it is not one.
 *
 * Accepts `AA:BB:CC:DD:EE:FF`, `AA-BB-CC-DD-EE-FF` and the bare `AABBCCDDEEFF`, in any letter case,
 * with surrounding whitespace tolerated. Separators must be one kind and all five of them: a mixed
 * `AA:BB-CC` is a paste error, not a spelling, and rejecting it here is what lets the form colour the
 * field red instead of the wake sending a packet no card answers to.
 *
 * No third-party dependency, and deliberately no normalisation on the way out — the text as typed is
 * what the profile stores and shows, so a host edited on another device still reads the way its
 * owner wrote it. Every consumer (the form's validation, the vault importer, the sender) parses the
 * same way through this one function, which is the only way those three cannot drift.
 */
fun parseMac(text: String): ByteArray? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val separator = trimmed.firstOrNull { it == ':' || it == '-' }
    return if (separator != null) {
        val parts = trimmed.split(separator)
        if (parts.size != WakeOnLan.MAC_LENGTH) return null
        ByteArray(WakeOnLan.MAC_LENGTH) { index -> hexByte(parts[index]) ?: return null }
    } else {
        if (trimmed.length != WakeOnLan.MAC_LENGTH * 2) return null
        ByteArray(WakeOnLan.MAC_LENGTH) { index ->
            hexByte(trimmed.substring(index * 2, index * 2 + 2)) ?: return null
        }
    }
}

/** Two hexadecimal characters as one byte, or null when they are not exactly that. */
private fun hexByte(text: String): Byte? {
    if (text.length != 2 || text.any { it !in HEX_DIGITS }) return null
    return text.toInt(16).toByte()
}

private val HEX_DIGITS = "0123456789abcdefABCDEF".toSet()

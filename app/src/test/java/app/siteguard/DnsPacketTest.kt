package app.siteguard

import org.junit.Assert.*
import org.junit.Test

private fun ip(last: Int) = byteArrayOf(10, 111, 222.toByte(), last.toByte())

class DnsPacketTest {
    /** Builds an IPv4/UDP DNS query for [name] (type A). */
    private fun query(name: String): ByteArray {
        val q = ArrayList<Byte>()
        q += listOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0).map { it.toByte() }
        name.split('.').forEach { l -> q += l.length.toByte(); l.forEach { q += it.code.toByte() } }
        q += listOf(0, 0, 1, 0, 1).map { it.toByte() }
        val dns = q.toByteArray()
        val p = ByteArray(28 + dns.size)
        p[0] = 0x45; p[2] = (p.size shr 8).toByte(); p[3] = p.size.toByte(); p[8] = 64; p[9] = 17
        ip(2).copyInto(p, 12); ip(1).copyInto(p, 16)
        p[20] = 0xC3.toByte(); p[21] = 0x50; p[22] = 0; p[23] = 53
        dns.copyInto(p, 28)
        return p
    }

    @Test fun parsesName() {
        val q = DnsPacket.parse(query("www.Instagram.com"))!!
        assertEquals("www.instagram.com", q.name)
        assertEquals(0xC350, q.srcPort)
    }

    @Test fun ignoresNonDns() {
        val p = query("a.com"); p[23] = 80
        assertNull(DnsPacket.parse(p))
    }

    @Test fun blockedReplyIsNxdomainBackToSender() {
        val q = DnsPacket.parse(query("bad.example"))!!
        val out = DnsPacket.wrap(q, DnsPacket.blockedReply(q))
        // IP: swapped addresses, valid header checksum
        assertArrayEquals(ip(1), out.copyOfRange(12, 16))
        assertArrayEquals(ip(2), out.copyOfRange(16, 20))
        assertEquals(0, DnsPacket.checksum(out, 0, 20))
        // UDP: ports swapped
        assertEquals(53, ((out[20].toInt() and 0xFF) shl 8) or (out[21].toInt() and 0xFF))
        // DNS: same id, QR set, RCODE 3, one question, no answers
        val dns = out.copyOfRange(28, out.size)
        assertEquals(0x12, dns[0].toInt()); assertEquals(0x34, dns[1].toInt())
        assertTrue(dns[2].toInt() and 0x80 != 0)
        assertEquals(3, dns[3].toInt() and 0x0F)
        assertEquals(1, dns[5].toInt()); assertEquals(0, dns[7].toInt())
        assertEquals(q.name, DnsPacket.parse(query(q.name))!!.name)
    }
}

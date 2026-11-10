package app.siteguard

/** Minimal IPv4 + UDP + DNS handling for the DNS-only VPN. Pure Kotlin so it is unit-testable. */
object DnsPacket {

    class Query(
        val src: ByteArray, val dst: ByteArray,
        val srcPort: Int, val dstPort: Int,
        /** The raw DNS message. */
        val dns: ByteArray,
        val name: String,
        /** Offset in [dns] where the first question ends. */
        val questionEnd: Int,
    )

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }

    /** Parses an IPv4/UDP packet sent to port 53. Returns null for anything else. */
    fun parse(pkt: ByteArray, len: Int = pkt.size): Query? {
        if (len < 28 || (pkt[0].toInt() shr 4) and 0xF != 4) return null
        val ihl = (pkt[0].toInt() and 0xF) * 4
        if (pkt[9].toInt() != 17 || len < ihl + 8) return null
        val total = u16(pkt, 2).coerceAtMost(len)
        val srcPort = u16(pkt, ihl); val dstPort = u16(pkt, ihl + 2)
        if (dstPort != 53) return null
        val dns = pkt.copyOfRange(ihl + 8, total)
        if (dns.size < 17 || u16(dns, 4) < 1) return null // header + at least a 1-label name + type/class

        val sb = StringBuilder()
        var i = 12
        while (true) {
            if (i >= dns.size) return null
            val l = dns[i].toInt() and 0xFF
            if (l == 0) { i++; break }
            if (l and 0xC0 != 0 || i + 1 + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..l) sb.append((dns[i + k].toInt() and 0xFF).toChar())
            i += 1 + l
        }
        if (i + 4 > dns.size) return null
        return Query(pkt.copyOfRange(12, 16), pkt.copyOfRange(16, 20), srcPort, dstPort, dns, sb.toString().lowercase(), i + 4)
    }

    /** DNS reply saying the name doesn't exist (NXDOMAIN), so nothing can connect to it. */
    fun blockedReply(q: Query): ByteArray {
        val r = q.dns.copyOf(q.questionEnd)
        r[2] = (0x80 or (q.dns[2].toInt() and 0x79)).toByte() // QR=1, keep opcode + RD
        r[3] = 0x83.toByte()                                // RA=1, RCODE=3
        put16(r, 4, 1); put16(r, 6, 0); put16(r, 8, 0); put16(r, 10, 0)
        return r
    }

    /** Wraps a DNS reply into an IPv4/UDP packet going back to the asker. */
    fun wrap(q: Query, payload: ByteArray, len: Int = payload.size): ByteArray {
        val total = 28 + len
        val p = ByteArray(total)
        p[0] = 0x45; put16(p, 2, total); p[6] = 0x40; p[8] = 64; p[9] = 17
        q.dst.copyInto(p, 12); q.src.copyInto(p, 16)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, q.dstPort); put16(p, 22, q.srcPort); put16(p, 24, 8 + len) // UDP checksum 0 = none (allowed on IPv4)
        payload.copyInto(p, 28, 0, len)
        return p
    }

    fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) { sum += u16(b, i); i += 2 }
        if (len % 2 == 1) sum += (b[off + len - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toInt() and 0xFFFF
    }
}

package com.paulfruitful.screenguard.dns

import java.util.concurrent.atomic.AtomicInteger

/** Minimal IPv4/UDP/DNS packet handling for the local DNS filter. */
object DnsPacketProcessor {

    const val TUN_ADDRESS = "10.0.0.2"
    const val DNS_ADDRESS = "10.0.0.1"
    const val TUN_PREFIX = 24
    private const val DNS_PORT = 53
    private val ipId = AtomicInteger(1)

    data class UdpDnsQuery(
        val sourceIp: ByteArray,
        val destIp: ByteArray,
        val sourcePort: Int,
        val payload: ByteArray,
    )

    /** Extracts the lowercase dotted question name from a DNS query. */
    fun questionName(payload: ByteArray): String? {
        if (payload.size < 13) {
            return null
        }
        val questionCount =
            ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        if (questionCount < 1) {
            return null
        }
        var offset = 12
        val labels = mutableListOf<String>()
        var hops = 0
        while (offset < payload.size && hops < 128) {
            val length = payload[offset].toInt() and 0xFF
            if (length == 0) {
                break
            }
            // Name compression in a question section is unexpected; do not guess.
            if ((length and 0xC0) == 0xC0) {
                return null
            }
            offset += 1
            if (offset + length > payload.size) {
                return null
            }
            labels.add(String(payload, offset, length, Charsets.US_ASCII))
            offset += length
            hops += 1
        }
        return if (labels.isEmpty()) null else labels.joinToString(".").lowercase()
    }

    fun questionEndOffset(payload: ByteArray): Int? {
        if (payload.size < 13) {
            return null
        }
        var offset = 12
        var hops = 0
        while (offset < payload.size && hops < 128) {
            val length = payload[offset].toInt() and 0xFF
            if (length == 0) {
                // Skip QTYPE + QCLASS.
                return if (offset + 5 <= payload.size) offset + 5 else null
            }
            if ((length and 0xC0) == 0xC0) {
                return null
            }
            offset += 1 + length
            hops += 1
        }
        return null
    }

    fun isBlocked(domain: String, config: DnsFilterConfig): Boolean {
        val entries = mutableListOf<String>()
        if (config.useStarterList) {
            entries.addAll(DnsStarterBlocklist.DOMAINS)
        }
        entries.addAll(config.customDomains)
        return entries.any { blocked ->
            domain == blocked || domain.endsWith(".$blocked")
        }
    }

    /** Builds an NXDOMAIN answer that mirrors the original question. */
    fun buildNxDomainResponse(query: ByteArray): ByteArray? {
        val end = questionEndOffset(query) ?: return null
        val response = ByteArray(end)
        query.copyInto(response, 0, 0, end)
        // QR=1, RD/RA preserved, RCODE=3 (NXDOMAIN).
        response[2] = 0x81.toByte()
        response[3] = 0x83.toByte()
        // Answer / authority / additional counts all zero.
        for (i in 6..11) {
            response[i] = 0
        }
        return response
    }

    /** Parses an IPv4 UDP packet and returns its DNS payload when destined to port 53. */
    fun parseQuery(packet: ByteArray): UdpDnsQuery? {
        if (packet.size < 28) {
            return null
        }
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (ipHeaderLength < 20 || packet.size < ipHeaderLength + 8) {
            return null
        }
        if ((packet[9].toInt() and 0xFF) != 17) {
            return null
        }
        val udpLength =
            ((packet[ipHeaderLength + 4].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 5].toInt() and 0xFF)
        val destinationPort =
            ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 3].toInt() and 0xFF)
        if (destinationPort != DNS_PORT || udpLength < 8) {
            return null
        }
        val payloadLength = udpLength - 8
        val payloadStart = ipHeaderLength + 8
        if (payloadLength < 12 || payloadStart + payloadLength > packet.size) {
            return null
        }
        val sourcePort =
            ((packet[ipHeaderLength].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 1].toInt() and 0xFF)
        return UdpDnsQuery(
            sourceIp = packet.copyOfRange(12, 16),
            destIp = packet.copyOfRange(16, 20),
            sourcePort = sourcePort,
            payload = packet.copyOfRange(payloadStart, payloadStart + payloadLength),
        )
    }

    /** Wraps a DNS answer in an IPv4/UDP packet addressed back to the original client. */
    fun buildAnswerPacket(query: UdpDnsQuery, dnsAnswer: ByteArray): ByteArray {
        val udpLength = 8 + dnsAnswer.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)
        packet[0] = 0x45.toByte()
        packet[1] = 0
        packet[2] = (totalLength shr 8).toByte()
        packet[3] = totalLength.toByte()
        val id = ipId.getAndIncrement() and 0xFFFF
        packet[4] = (id shr 8).toByte()
        packet[5] = id.toByte()
        packet[6] = 0
        packet[7] = 0
        packet[8] = 64.toByte()
        packet[9] = 17.toByte()
        // Header checksum is written after the rest of the header is filled.
        query.destIp.copyInto(packet, 12)
        query.sourceIp.copyInto(packet, 16)
        packet[20] = (DNS_PORT shr 8).toByte()
        packet[21] = DNS_PORT.toByte()
        packet[22] = (query.sourcePort shr 8).toByte()
        packet[23] = query.sourcePort.toByte()
        packet[24] = (udpLength shr 8).toByte()
        packet[25] = udpLength.toByte()
        // UDP checksum 0 is valid for IPv4.
        packet[26] = 0
        packet[27] = 0
        dnsAnswer.copyInto(packet, 28)
        val checksum = ipChecksum(packet.copyOfRange(0, 20))
        packet[10] = (checksum shr 8).toByte()
        packet[11] = checksum.toByte()
        return packet
    }

    private fun ipChecksum(header: ByteArray): Int {
        var sum = 0
        var index = 0
        while (index + 1 < header.size) {
            val high = header[index].toInt() and 0xFF
            val low = header[index + 1].toInt() and 0xFF
            sum += (high shl 8) or low
            index += 2
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}

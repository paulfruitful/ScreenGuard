package com.paulfruitful.screenguard.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketProcessorTest {

    private fun buildSampleQuery(txIdHi: Int, txIdLo: Int, domain: String, qType: Int = 1): ByteArray {
        val labels = domain.split('.')
        val body = mutableListOf<Byte>()
        // 12-byte DNS header
        body.add(txIdHi.toByte())
        body.add(txIdLo.toByte())
        body.add(0x01.toByte()) // RD=1
        body.add(0x00.toByte())
        body.add(0x00.toByte()); body.add(0x01.toByte()) // QDCOUNT=1
        repeat(6) { body.add(0x00.toByte()) }
        for (label in labels) {
            body.add(label.length.toByte())
            label.toByteArray(Charsets.US_ASCII).forEach { body.add(it) }
        }
        body.add(0x00.toByte()) // root terminator
        body.add((qType shr 8).toByte())
        body.add((qType and 0xFF).toByte())
        body.add(0x00.toByte()); body.add(0x01.toByte()) // QCLASS=IN
        return body.toByteArray()
    }

    @Test
    fun whitelistOverridesLocalBlocklistForExactAndSubdomains() {
        val config = DnsFilterConfig(
            customDomains = listOf("example.com", "blocked.org"),
            whitelistedDomains = listOf("example.com", "reddit.com"),
            useStarterList = true,
        )

        assertTrue(DnsPacketProcessor.isWhitelisted("example.com", config))
        assertTrue(DnsPacketProcessor.isWhitelisted("sub.example.com", config))
        assertTrue(DnsPacketProcessor.isWhitelisted("www.reddit.com", config))
        assertFalse(DnsPacketProcessor.isWhitelisted("notreddit.com", config))

        // Whitelisted domain is NOT blocked even though it also appears in customDomains
        assertFalse(DnsPacketProcessor.isBlocked("example.com", config))
        assertFalse(DnsPacketProcessor.isBlocked("api.example.com", config))

        // Non-whitelisted blocked domains remain blocked
        assertTrue(DnsPacketProcessor.isBlocked("blocked.org", config))
        assertTrue(DnsPacketProcessor.isBlocked("www.pornhub.com", config))
    }

    @Test
    fun profilesProvidePrimaryAndFailoverUpstreams() {
        val cf = DnsFilterProfiles.upstreamsFor(DnsFilterProfiles.CLOUDFLARE_FAMILY, "")
        assertEquals(listOf("1.1.1.3", "1.0.0.3", "94.140.14.15"), cf)

        val adguard = DnsFilterProfiles.upstreamsFor(DnsFilterProfiles.ADGUARD_FAMILY, "")
        assertEquals(listOf("94.140.14.15", "94.140.15.16", "1.1.1.3"), adguard)

        val custom = DnsFilterProfiles.upstreamsFor(DnsFilterProfiles.CUSTOM, "9.9.9.9")
        assertEquals(listOf("9.9.9.9", "1.0.0.3"), custom)

        assertTrue(DnsFilterProfiles.UNFILTERED_WHITELIST_UPSTREAMS.contains("1.1.1.1"))
        assertTrue(DnsFilterProfiles.UNFILTERED_WHITELIST_UPSTREAMS.contains("8.8.8.8"))
    }

    @Test
    fun transactionIdMatchingAndCacheRewriteWorkCorrectly() {
        val q1 = buildSampleQuery(0x12, 0x34, "github.com", qType = 28)
        val q2 = buildSampleQuery(0xAB, 0xCD, "github.com", qType = 28)
        assertEquals("github.com", DnsPacketProcessor.questionName(q1))
        assertEquals(28, DnsPacketProcessor.questionType(q1))

        val r1 = q1.copyOf()
        r1[2] = 0x81.toByte() // QR=1 response
        r1[3] = 0x80.toByte() // NOERROR

        assertTrue(DnsPacketProcessor.isMatchingDnsResponse(q1, r1))
        assertFalse(DnsPacketProcessor.isMatchingDnsResponse(q2, r1))

        val rewritten = DnsPacketProcessor.rewriteTransactionId(r1, q2)
        assertNotNull(rewritten)
        assertTrue(DnsPacketProcessor.isMatchingDnsResponse(q2, rewritten!!))
    }

    @Test
    fun servFailAndIpv4LiteralParsingWorkWithoutDnsLookups() {
        val q = buildSampleQuery(0x44, 0x55, "offline.test")
        val servFail = DnsPacketProcessor.buildServFailResponse(q)
        assertNotNull(servFail)
        assertEquals(0x81.toByte(), servFail!![2])
        assertEquals(0x82.toByte(), servFail[3]) // RCODE=2 SERVFAIL
        assertTrue(DnsPacketProcessor.isMatchingDnsResponse(q, servFail))

        val ipv4 = DnsPacketProcessor.parseIpv4Literal("1.1.1.3")
        assertNotNull(ipv4)
        assertArrayEquals(byteArrayOf(1, 1, 1, 3), ipv4!!.address)
        assertNull(DnsPacketProcessor.parseIpv4Literal("dns.quad9.net"))
        assertNull(DnsPacketProcessor.parseIpv4Literal("999.1.1.1"))
    }
}

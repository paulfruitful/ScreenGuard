package com.paulfruitful.screenguard.dns

/**
 * Milestone 5 — local DNS content filter.
 *
 * IMPORTANT: this is a user-approved VPN-service based DNS filter. It only redirects DNS
 * queries (UDP/53) through a local resolver; it does NOT route or inspect the user's other
 * network traffic. It is not a guarantee: apps using their own DoH/DoT, hardcoded IPs, or
 * Android Private DNS can bypass it. The UI must say this plainly.
 */

object DnsFilterProfiles {
    const val CLOUDFLARE_FAMILY = "CLOUDFLARE_FAMILY"
    const val ADGUARD_FAMILY = "ADGUARD_FAMILY"
    const val CLEANBROWSING_ADULT = "CLEANBROWSING_ADULT"
    const val OPENDNS_FAMILYSHIELD = "OPENDNS_FAMILYSHIELD"
    const val CUSTOM = "CUSTOM"

    val ALL = listOf(
        CLOUDFLARE_FAMILY,
        ADGUARD_FAMILY,
        CLEANBROWSING_ADULT,
        OPENDNS_FAMILYSHIELD,
        CUSTOM,
    )

    /** Unfiltered resolvers used ONLY for explicitly whitelisted domains so upstream over-blocking cannot break them. */
    val UNFILTERED_WHITELIST_UPSTREAMS = listOf(
        "1.1.1.1",
        "8.8.8.8",
        "1.0.0.1",
    )

    /** Primary IPv4 resolver for each preset; CUSTOM uses the configured host. */
    fun upstreamFor(profile: String, customHost: String): String =
        upstreamsFor(profile, customHost).first()

    /**
     * Ordered primary + secondary + tertiary family-safe IPv4 resolvers so a carrier hiccup or
     * transient drop on a single upstream IP never causes a DNS blackout.
     */
    fun upstreamsFor(profile: String, customHost: String): List<String> = when (profile) {
        CLOUDFLARE_FAMILY -> listOf("1.1.1.3", "1.0.0.3", "94.140.14.15")
        ADGUARD_FAMILY -> listOf("94.140.14.15", "94.140.15.16", "1.1.1.3")
        CLEANBROWSING_ADULT -> listOf("185.228.168.10", "185.228.169.11", "1.1.1.3")
        OPENDNS_FAMILYSHIELD -> listOf("208.67.222.123", "208.67.220.123", "1.1.1.3")
        CUSTOM -> {
            val primary = customHost.trim().ifEmpty { "1.1.1.3" }
            listOf(primary, "1.0.0.3").distinct()
        }
        else -> listOf("1.1.1.3", "1.0.0.3", "94.140.14.15")
    }

    fun labelFor(profile: String): String = when (profile) {
        CLOUDFLARE_FAMILY -> "Cloudflare Family (1.1.1.3 / 1.0.0.3)"
        ADGUARD_FAMILY -> "AdGuard Family (94.140.14.15 / 94.140.15.16)"
        CLEANBROWSING_ADULT -> "CleanBrowsing Adult (185.228.168.10 / 185.228.169.11)"
        OPENDNS_FAMILYSHIELD -> "OpenDNS FamilyShield (208.67.222.123 / 208.67.220.123)"
        CUSTOM -> "Custom DNS server"
        else -> "Unknown"
    }
}

/** A small starter list. The maintained filtering work is done by the chosen upstream resolver. */
object DnsStarterBlocklist {
    val DOMAINS = listOf(
        "pornhub.com",
        "xvideos.com",
        "xnxx.com",
        "xhamster.com",
        "redtube.com",
        "tube8.com",
        "youporn.com",
        "spankbang.com",
        "porn.com",
        "xhot.com",
        "xvideos.es",
        "xhamster.desi",
        "pornhub.org",
        "xxx.com",
        "adult.com",
        "livejasmin.com",
        "chaturbate.com",
        "bongacams.com",
        "eporner.com",
        "hardsextube.com",
    )

    fun isKnownAdultDomain(domain: String): Boolean {
        val d = domain.trim().lowercase()
        return DOMAINS.any { blocked -> d == blocked || d.endsWith(".$blocked") }
    }
}

data class DnsFilterConfig(
    val enabled: Boolean = true,
    val profile: String = DnsFilterProfiles.CLOUDFLARE_FAMILY,
    val customHost: String = "",
    val customDomains: List<String> = emptyList(),
    val whitelistedDomains: List<String> = emptyList(),
    val useStarterList: Boolean = true,
)

data class DnsFilterStatus(
    val active: Boolean,
    val prepared: Boolean,
    val profile: String,
    val profileLabel: String,
    val upstream: String,
    val customDomains: List<String>,
    val customDomainCount: Int,
    val whitelistedDomains: List<String>,
    val whitelistedDomainCount: Int,
    val useStarterList: Boolean,
    val blockedQueries: Long,
    val allowedQueries: Long,
    val failedQueries: Long,
    val lastEvent: String,
    val lastEventTimestampMs: Long,
    val note: String,
)

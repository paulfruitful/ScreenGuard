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

    /** Primary IPv4 resolver for each preset; CUSTOM uses the configured host. */
    fun upstreamFor(profile: String, customHost: String): String = when (profile) {
        CLOUDFLARE_FAMILY -> "1.1.1.3"
        ADGUARD_FAMILY -> "94.140.14.15"
        CLEANBROWSING_ADULT -> "185.228.168.10"
        OPENDNS_FAMILYSHIELD -> "208.67.222.123"
        CUSTOM -> customHost.trim().ifEmpty { "1.1.1.3" }
        else -> "1.1.1.3"
    }

    fun labelFor(profile: String): String = when (profile) {
        CLOUDFLARE_FAMILY -> "Cloudflare Family (1.1.1.3)"
        ADGUARD_FAMILY -> "AdGuard Family (94.140.14.15)"
        CLEANBROWSING_ADULT -> "CleanBrowsing Adult (185.228.168.10)"
        OPENDNS_FAMILYSHIELD -> "OpenDNS FamilyShield (208.67.222.123)"
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
}

data class DnsFilterConfig(
    val enabled: Boolean = true,
    val profile: String = DnsFilterProfiles.CLOUDFLARE_FAMILY,
    val customHost: String = "",
    val customDomains: List<String> = emptyList(),
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
    val useStarterList: Boolean,
    val blockedQueries: Long,
    val allowedQueries: Long,
    val failedQueries: Long,
    val lastEvent: String,
    val lastEventTimestampMs: Long,
    val note: String,
)

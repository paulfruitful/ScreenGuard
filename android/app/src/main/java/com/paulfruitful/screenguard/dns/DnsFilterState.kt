package com.paulfruitful.screenguard.dns

import android.content.Context
import android.net.VpnService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Persisted DNS-filter configuration plus in-memory runtime counters.
 * Configuration lives in app-private storage; nothing about browsing activity is stored.
 * Counters are reset on process restart and are only diagnostic totals, not history.
 */
object DnsFilterState {

    private const val PREFS_NAME = "screenguard_dns_filter_v1"
    private const val KEY_CONFIG = "dns_filter_config_v1"

    private val blockedCounter = AtomicLong(0L)
    private val allowedCounter = AtomicLong(0L)
    private val failedCounter = AtomicLong(0L)

    /** Incremented on every config save so DnsFilterVpnService flushes its in-memory DNS cache immediately. */
    private val configGeneration = AtomicLong(1L)

    val blockedQueries: Long get() = blockedCounter.get()
    val allowedQueries: Long get() = allowedCounter.get()
    val failedQueries: Long get() = failedCounter.get()
    val currentConfigGeneration: Long get() = configGeneration.get()

    @Volatile
    var serviceRunning: Boolean = false

    /** Live config used by the VPN worker so edits apply without a restart. */
    @Volatile
    var currentConfig: DnsFilterConfig = DnsFilterConfig()

    @Volatile
    var lastEvent: String = "NONE"

    @Volatile
    var lastEventTimestampMs: Long = 0L

    fun bumpBlocked() {
        blockedCounter.incrementAndGet()
        mark("QUERY_BLOCKED")
    }

    fun bumpAllowed() {
        allowedCounter.incrementAndGet()
    }

    fun bumpFailed() {
        failedCounter.incrementAndGet()
        mark("QUERY_FORWARD_FAILED")
    }

    fun mark(event: String) {
        lastEvent = event
        lastEventTimestampMs = System.currentTimeMillis()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadConfig(context: Context): DnsFilterConfig {
        val raw = prefs(context).getString(KEY_CONFIG, null)
        val loaded = if (raw == null) {
            DnsFilterConfig()
        } else {
            try {
                val obj = JSONObject(raw)
                val domains = JSONArray(obj.optString("customDomains", "[]"))
                val whitelisted = JSONArray(obj.optString("whitelistedDomains", "[]"))
                DnsFilterConfig(
                    enabled = obj.optBoolean("enabled", true),
                    profile = obj.optString("profile", DnsFilterProfiles.CLOUDFLARE_FAMILY),
                    customHost = obj.optString("customHost", ""),
                    customDomains = (0 until domains.length()).mapNotNull { i ->
                        domains.optString(i, "").trim().lowercase().ifEmpty { null }
                    },
                    whitelistedDomains = (0 until whitelisted.length()).mapNotNull { i ->
                        whitelisted.optString(i, "").trim().lowercase().ifEmpty { null }
                    },
                    useStarterList = obj.optBoolean("useStarterList", true),
                )
            } catch (_: Exception) {
                DnsFilterConfig()
            }
        }
        currentConfig = loaded
        return loaded
    }

    fun saveConfig(context: Context, config: DnsFilterConfig) {
        val domains = JSONArray()
        config.customDomains.forEach { domains.put(it) }
        val whitelisted = JSONArray()
        config.whitelistedDomains.forEach { whitelisted.put(it) }
        val obj = JSONObject().apply {
            put("enabled", config.enabled)
            put("profile", config.profile)
            put("customHost", config.customHost)
            put("customDomains", domains.toString())
            put("whitelistedDomains", whitelisted.toString())
            put("useStarterList", config.useStarterList)
        }
        prefs(context).edit().putString(KEY_CONFIG, obj.toString()).commit()
        currentConfig = config
        configGeneration.incrementAndGet()
    }

    /** Snapshot used by both the React Native bridge and the VPN service events. */
    fun status(context: Context): DnsFilterStatus {
        val appContext = context.applicationContext
        val config = loadConfig(appContext)
        val prepared = try {
            VpnService.prepare(appContext) == null
        } catch (_: Exception) {
            false
        }
        return DnsFilterStatus(
            active = serviceRunning,
            prepared = prepared,
            profile = config.profile,
            profileLabel = DnsFilterProfiles.labelFor(config.profile),
            upstream = DnsFilterProfiles.upstreamFor(config.profile, config.customHost),
            customDomains = config.customDomains,
            customDomainCount = config.customDomains.size,
            whitelistedDomains = config.whitelistedDomains,
            whitelistedDomainCount = config.whitelistedDomains.size,
            useStarterList = config.useStarterList,
            blockedQueries = blockedQueries,
            allowedQueries = allowedQueries,
            failedQueries = failedQueries,
            lastEvent = lastEvent,
            lastEventTimestampMs = lastEventTimestampMs,
            note = "Local DNS filter only: DNS queries go through this app with automatic primary/backup failover. Whitelisted sites resolve directly through unfiltered DNS so legitimate sites stay accessible.",
        )
    }

    fun normalizeHost(input: String): String? {
        var value = input.trim().lowercase()
        if (value.isEmpty() || value.contains(' ') || value.contains('/')) {
            return null
        }
        value = value.removePrefix("https://").removePrefix("http://")
        if (value.isEmpty() || value.contains('/') || value.contains(':')) {
            return null
        }
        return value
    }

    fun normalizeDomain(input: String): String? {
        var value = input.trim().lowercase()
        if (value.isEmpty() || value.contains(' ')) {
            return null
        }
        value = value.removePrefix("https://").removePrefix("http://")
        value = value.substringBefore('/').substringBefore('?').substringBefore('#')
        value = value.substringBefore(':')
        value = value.removePrefix("www.")
        value = value.trimEnd('.')
        if (!value.contains('.') || value.startsWith('.')) {
            return null
        }
        return value
    }
}

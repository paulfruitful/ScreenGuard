package com.paulfruitful.screenguard.dns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.paulfruitful.screenguard.MainActivity
import com.paulfruitful.screenguard.accessibility.AccessibilityDisconnectAlertManager
import com.paulfruitful.screenguard.accountability.AccountabilityManager
import com.paulfruitful.screenguard.bridge.EventBridge
import com.paulfruitful.screenguard.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Local DNS filter implemented as a VpnService.
 *
 * Only DNS queries addressed to the VPN's own DNS server (10.0.0.1/32) are handled. The builder
 * does NOT add a 0.0.0.0/0 route, so all non-DNS traffic continues over the normal cellular/Wi-Fi
 * connection and is never read by this service.
 *
 * Reliability architecture:
 * - Concurrent forwarding worker pool (16 threads) so slow queries never head-of-line block
 *   other domains.
 * - Fresh protected [DatagramSocket] per upstream attempt so Wi-Fi/cellular switches and Doze
 *   transitions never leave a stale socket bound to a dead interface, and late UDP replies can
 *   never desync subsequent queries.
 * - DNS transaction ID verification on every upstream response.
 * - Automatic primary -> secondary -> tertiary upstream failover with crisp timeouts.
 * - Explicitly whitelisted domains bypass local blocklists and route through unfiltered DNS so
 *   over-blocking by family resolvers never breaks user-allowed sites.
 * - Lightweight 45-second in-memory TTL cache (flushed immediately on config changes).
 */
class DnsFilterVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.paulfruitful.screenguard.dns.START"
        const val ACTION_STOP = "com.paulfruitful.screenguard.dns.STOP"
        private const val CHANNEL_ID = "screenguard_dns_filter"
        private const val NOTIFICATION_ID = 4101

        private const val WORKER_POOL_SIZE = 16
        private const val MAX_IN_FLIGHT_QUERIES = 64
        private const val CACHE_TTL_MS = 45_000L
        private const val CACHE_MAX_ENTRIES = 256
        private val UPSTREAM_TIMEOUTS_MS = intArrayOf(1200, 1500, 1800)

        /**
         * Automatically starts the DNS filter VPN service if the user has already granted
         * Android VPN consent and has not explicitly disabled the filter.
         */
        fun startIfPreparedAndEnabled(context: Context): Boolean {
            val appCtx = context.applicationContext
            return try {
                if (DnsFilterState.serviceRunning) return true
                val config = DnsFilterState.loadConfig(appCtx)
                if (!config.enabled) return false
                if (VpnService.prepare(appCtx) != null) return false
                val intent = Intent(appCtx, DnsFilterVpnService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
                DnsFilterState.mark("FILTER_AUTO_START_REQUESTED")
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private data class CachedDnsEntry(
        val answerPayload: ByteArray,
        val expiresAtElapsedMs: Long,
    )

    private val running = AtomicBoolean(false)
    private val tunWriteLock = Any()
    private val inFlightPermits = Semaphore(MAX_IN_FLIGHT_QUERIES)
    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val cacheGeneration = AtomicLong(0L)
    private val resolvedHostCache = ConcurrentHashMap<String, InetAddress>()
    private val workerThreadId = AtomicInteger(1)

    @Volatile
    private var tunnel: ParcelFileDescriptor? = null
    @Volatile
    private var tunOutput: FileOutputStream? = null
    private var readerThread: Thread? = null
    private var forwardExecutor: ExecutorService? = null
    private var accessibilityWatchdog: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (AccountabilityManager.isLockActive(this)) {
                DnsFilterState.mark("STOP_BLOCKED_BY_LOCK")
                EventBridge.emitDnsFilterStatus(DnsFilterState.status(this))
                return START_STICKY
            }
            stopFilter()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // A restored/null-action start must respect an explicit user stop.
        if (intent?.action != ACTION_START &&
            !DnsFilterState.loadConfig(this).enabled
        ) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            DnsFilterState.serviceRunning = false
            EventBridge.emitDnsFilterStatus(DnsFilterState.status(this))
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        // serviceRunning is set only after builder.establish() succeeds.
        if (running.compareAndSet(false, true)) {
            startFilter()
            startAccessibilityWatchdog()
        } else {
            AccessibilityDisconnectAlertManager.checkAndAlertIfDisconnected(applicationContext)
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopFilter()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopFilter()
        super.onDestroy()
    }

    private fun stopFilter() {
        running.set(false)
        accessibilityWatchdog?.interrupt()
        accessibilityWatchdog = null
        readerThread?.interrupt()
        readerThread = null
        try {
            forwardExecutor?.shutdownNow()
        } catch (_: Exception) {
        }
        forwardExecutor = null
        dnsCache.clear()
        resolvedHostCache.clear()
        tunOutput = null
        try {
            tunnel?.close()
        } catch (_: Exception) {
        }
        tunnel = null
        DnsFilterState.serviceRunning = false
        DnsFilterState.mark("FILTER_STOPPED")
        EventBridge.emitDnsFilterStatus(DnsFilterState.status(this))
    }

    private fun startFilter() {
        val executor = Executors.newFixedThreadPool(WORKER_POOL_SIZE) { runnable ->
            Thread(runnable, "screenguard-dns-worker-${workerThreadId.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
        forwardExecutor = executor

        readerThread = Thread(
            {
                DnsFilterState.loadConfig(this)
                cacheGeneration.set(DnsFilterState.currentConfigGeneration)
                try {
                    val builder = Builder()
                        .setSession(getString(R.string.dns_filter_session_name))
                        .setMtu(1500)
                        .setBlocking(true)
                        .addAddress(DnsPacketProcessor.TUN_ADDRESS, DnsPacketProcessor.TUN_PREFIX)
                        // Route only the virtual DNS server IP into tun0; never 0.0.0.0/0.
                        .addRoute(DnsPacketProcessor.DNS_ADDRESS, 32)
                        .addDnsServer(DnsPacketProcessor.DNS_ADDRESS)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        builder.setMetered(false)
                    }
                    val pfd = builder.establish()
                    if (pfd == null) {
                        DnsFilterState.serviceRunning = false
                        DnsFilterState.mark("VPN_ESTABLISH_FAILED")
                        EventBridge.emitDnsFilterStatus(DnsFilterState.status(this))
                        stopSelf()
                        return@Thread
                    }
                    tunnel = pfd
                    val input = FileInputStream(pfd.fileDescriptor)
                    val output = FileOutputStream(pfd.fileDescriptor)
                    tunOutput = output

                    DnsFilterState.serviceRunning = true
                    DnsFilterState.mark("FILTER_STARTED")
                    EventBridge.emitDnsFilterStatus(DnsFilterState.status(this))

                    val buffer = ByteArray(4096)
                    var consecutiveReadErrors = 0

                    while (running.get()) {
                        val count = try {
                            val n = input.read(buffer)
                            consecutiveReadErrors = 0
                            n
                        } catch (_: Exception) {
                            if (!running.get()) break
                            consecutiveReadErrors++
                            if (consecutiveReadErrors >= 3) {
                                DnsFilterState.mark("TUN_READ_FAILED")
                                break
                            }
                            try {
                                Thread.sleep(150L)
                            } catch (_: InterruptedException) {
                                break
                            }
                            continue
                        }
                        if (count <= 0) {
                            continue
                        }
                        val query = DnsPacketProcessor.parseQuery(buffer.copyOf(count))
                            ?: continue
                        dispatchQuery(query, executor)
                    }
                    try {
                        input.close()
                        output.close()
                    } catch (_: Exception) {
                    }
                } catch (_: Exception) {
                    DnsFilterState.mark("FILTER_ERROR")
                } finally {
                    tunOutput = null
                    try {
                        executor.shutdownNow()
                    } catch (_: Exception) {
                    }
                    if (forwardExecutor === executor) {
                        forwardExecutor = null
                    }
                    try {
                        tunnel?.close()
                    } catch (_: Exception) {
                    }
                    tunnel = null
                    running.set(false)
                    DnsFilterState.serviceRunning = false
                    EventBridge.emitDnsFilterStatus(DnsFilterState.status(this))
                    stopSelf()
                }
            },
            "screenguard-dns-reader",
        )
        readerThread?.start()
    }

    private fun dispatchQuery(
        query: DnsPacketProcessor.UdpDnsQuery,
        executor: ExecutorService,
    ) {
        val currentGen = DnsFilterState.currentConfigGeneration
        if (cacheGeneration.getAndSet(currentGen) != currentGen) {
            dnsCache.clear()
        }

        val config = DnsFilterState.currentConfig
        val domain = DnsPacketProcessor.questionName(query.payload)
        val whitelisted = domain != null && DnsPacketProcessor.isWhitelisted(domain, config)

        // Fast-path 1: local blocklist (whitelisted domains ALWAYS bypass local blocks).
        if (!whitelisted && domain != null && DnsPacketProcessor.isBlocked(domain, config)) {
            val answer = DnsPacketProcessor.buildNxDomainResponse(query.payload)
            if (answer != null) {
                writePacketToTun(DnsPacketProcessor.buildAnswerPacket(query, answer))
                DnsFilterState.bumpBlocked()
            }
            return
        }

        // Fast-path 2: in-memory TTL cache for recent safe lookups.
        val qType = DnsPacketProcessor.questionType(query.payload)
        val cacheKey = if (domain != null) {
            "$domain|$qType|$whitelisted|${config.profile}|${config.customHost}"
        } else {
            null
        }
        if (cacheKey != null) {
            val cached = dnsCache[cacheKey]
            val nowMs = SystemClock.elapsedRealtime()
            if (cached != null && nowMs < cached.expiresAtElapsedMs) {
                val rewritten = DnsPacketProcessor.rewriteTransactionId(
                    cached.answerPayload,
                    query.payload,
                )
                if (rewritten != null) {
                    writePacketToTun(DnsPacketProcessor.buildAnswerPacket(query, rewritten))
                    DnsFilterState.bumpAllowed()
                    return
                }
            } else if (cached != null) {
                dnsCache.remove(cacheKey)
            }
        }

        // Fast-path 3: dispatch upstream forwarding concurrently without blocking tun0 reads.
        if (!inFlightPermits.tryAcquire()) {
            // Too many simultaneous stalled queries; fail fast with SERVFAIL so clients retry cleanly.
            DnsPacketProcessor.buildServFailResponse(query.payload)?.let { servFail ->
                writePacketToTun(DnsPacketProcessor.buildAnswerPacket(query, servFail))
            }
            DnsFilterState.bumpFailed()
            return
        }

        try {
            executor.execute {
                try {
                    forwardQueryWithFailover(
                        query = query,
                        config = config,
                        whitelisted = whitelisted,
                        cacheKey = cacheKey,
                    )
                } finally {
                    inFlightPermits.release()
                }
            }
        } catch (_: Exception) {
            inFlightPermits.release()
        }
    }

    private fun forwardQueryWithFailover(
        query: DnsPacketProcessor.UdpDnsQuery,
        config: DnsFilterConfig,
        whitelisted: Boolean,
        cacheKey: String?,
    ) {
        if (!running.get()) return

        // Whitelisted domains route through unfiltered DNS so upstream family filters cannot
        // over-block sites the user explicitly allowed.
        val upstreams = if (whitelisted) {
            DnsFilterProfiles.UNFILTERED_WHITELIST_UPSTREAMS
        } else {
            DnsFilterProfiles.upstreamsFor(config.profile, config.customHost)
        }

        for (index in upstreams.indices) {
            if (!running.get()) return
            val host = upstreams[index]
            val timeoutMs = UPSTREAM_TIMEOUTS_MS[index.coerceAtMost(UPSTREAM_TIMEOUTS_MS.lastIndex)]
            val answer = querySingleUpstream(query.payload, host, timeoutMs)
            if (answer != null) {
                writePacketToTun(DnsPacketProcessor.buildAnswerPacket(query, answer))
                DnsFilterState.bumpAllowed()
                // Cache clean NOERROR (RCODE=0) responses for fast repeat resolution.
                val rcode = answer[3].toInt() and 0x0F
                if (cacheKey != null && rcode == 0 && answer.size <= 1500) {
                    if (dnsCache.size >= CACHE_MAX_ENTRIES) {
                        dnsCache.clear()
                    }
                    dnsCache[cacheKey] = CachedDnsEntry(
                        answerPayload = answer,
                        expiresAtElapsedMs = SystemClock.elapsedRealtime() + CACHE_TTL_MS,
                    )
                }
                return
            }
        }

        // All upstream servers failed (e.g. offline/no signal): send SERVFAIL so apps fail fast
        // instead of freezing on silent UDP timeouts.
        DnsPacketProcessor.buildServFailResponse(query.payload)?.let { servFail ->
            writePacketToTun(DnsPacketProcessor.buildAnswerPacket(query, servFail))
        }
        DnsFilterState.bumpFailed()
    }

    /**
     * Opens a fresh, VPN-protected UDP socket for a single upstream attempt.
     * Using a dedicated socket per query guarantees:
     * 1. Automatic binding to the current active Wi-Fi/cellular interface after network switches.
     * 2. Zero cross-query stale-reply poisoning when an earlier query arrives late.
     */
    private fun querySingleUpstream(
        queryPayload: ByteArray,
        upstreamHost: String,
        timeoutMs: Int,
    ): ByteArray? {
        val address = resolveUpstreamAddress(upstreamHost) ?: return null
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            if (!protect(socket)) {
                return null
            }
            socket.soTimeout = timeoutMs
            val request = DatagramPacket(queryPayload, queryPayload.size, address, 53)
            socket.send(request)

            val responseBuffer = ByteArray(4096)
            val response = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(response)
            val answer = response.data.copyOfRange(
                response.offset,
                response.offset + response.length,
            )
            if (DnsPacketProcessor.isMatchingDnsResponse(queryPayload, answer)) {
                answer
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveUpstreamAddress(host: String): InetAddress? {
        DnsPacketProcessor.parseIpv4Literal(host)?.let { return it }
        resolvedHostCache[host]?.let { return it }
        return try {
            val resolved = InetAddress.getByName(host)
            resolvedHostCache[host] = resolved
            resolved
        } catch (_: Exception) {
            null
        }
    }

    private fun writePacketToTun(packet: ByteArray) {
        val out = tunOutput ?: return
        synchronized(tunWriteLock) {
            if (!running.get()) return
            try {
                out.write(packet)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Co-resident Accessibility watchdog inside the START_STICKY VPN foreground service.
     * Because Android automatically restarts DnsFilterVpnService after an OEM task-killer
     * sweep (like Xiaomi HyperOS OneKeyClean), this watchdog detects within seconds when
     * ScreenGuardAccessibilityService was left in Android's `Crashed services` and raises
     * the urgent protective-shield reconnect alert.
     */
    private fun startAccessibilityWatchdog() {
        accessibilityWatchdog?.interrupt()
        accessibilityWatchdog = Thread(
            {
                // Brief initial settle so onServiceConnected has time to bind after app update/boot
                try {
                    Thread.sleep(4_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                while (running.get()) {
                    try {
                        AccessibilityDisconnectAlertManager.checkAndAlertIfDisconnected(
                            applicationContext
                        )
                        Thread.sleep(45_000L)
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: Exception) {
                        try {
                            Thread.sleep(45_000L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            },
            "screenguard-a11y-watchdog"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.dns_filter_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = getString(R.string.dns_filter_channel_description)
                    },
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DnsFilterVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.dns_filter_notification_title))
            .setContentText(getString(R.string.dns_filter_notification_text))
            .setContentIntent(openApp)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.dns_filter_notification_stop),
                stopIntent,
            )
            .setOngoing(true)
            .build()
    }
}

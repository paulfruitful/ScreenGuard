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
import androidx.core.app.NotificationCompat
import com.paulfruitful.screenguard.MainActivity
import com.paulfruitful.screenguard.accountability.AccountabilityManager
import com.paulfruitful.screenguard.bridge.EventBridge
import com.paulfruitful.screenguard.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local DNS filter implemented as a VpnService.
 *
 * Only DNS queries addressed to the VPN's own DNS server are handled. The builder does NOT
 * add a 0.0.0.0/0 route, so all non-DNS traffic continues over the normal cellular/Wi-Fi
 * connection and is never read by this service. Activation always requires the Android VPN
 * consent dialog; the app cannot enable it silently.
 */
class DnsFilterVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.paulfruitful.screenguard.dns.START"
        const val ACTION_STOP = "com.paulfruitful.screenguard.dns.STOP"
        private const val CHANNEL_ID = "screenguard_dns_filter"
        private const val NOTIFICATION_ID = 4101

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

    private val running = AtomicBoolean(false)
    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null

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
        worker?.interrupt()
        worker = null
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
        worker = Thread(
            {
                DnsFilterState.loadConfig(this)
                var socket: DatagramSocket? = null
                try {
                    val builder = Builder()
                        .setSession(getString(R.string.dns_filter_session_name))
                        .addAddress(DnsPacketProcessor.TUN_ADDRESS, DnsPacketProcessor.TUN_PREFIX)
                        // Route only the virtual DNS server IP into tun0; never 0.0.0.0/0.
                        .addRoute(DnsPacketProcessor.DNS_ADDRESS, 32)
                        .addDnsServer(DnsPacketProcessor.DNS_ADDRESS)
                    val pfd = builder.establish()
                    if (pfd == null) {
                        DnsFilterState.serviceRunning = false
                        DnsFilterState.mark("VPN_ESTABLISH_FAILED")
                        EventBridge.emitDnsFilterStatus(DnsFilterState.status(this))
                        stopSelf()
                        return@Thread
                    }
                    tunnel = pfd
                    DnsFilterState.serviceRunning = true
                    DnsFilterState.mark("FILTER_STARTED")
                    EventBridge.emitDnsFilterStatus(DnsFilterState.status(this))

                    socket = DatagramSocket()
                    // Protect the upstream socket so it is not routed back into our tunnel.
                    protect(socket)
                    socket.soTimeout = 3000

                    val input = FileInputStream(pfd.fileDescriptor)
                    val output = FileOutputStream(pfd.fileDescriptor)
                    val buffer = ByteArray(65535)

                    while (running.get()) {
                        val count = try {
                            input.read(buffer)
                        } catch (e: Exception) {
                            if (running.get()) {
                                DnsFilterState.mark("TUN_READ_FAILED")
                            }
                            break
                        }
                        if (count <= 0) {
                            continue
                        }
                        val query = DnsPacketProcessor.parseQuery(buffer.copyOf(count))
                            ?: continue
                        val domain = DnsPacketProcessor.questionName(query.payload)
                        val config = DnsFilterState.currentConfig
                        val upstream =
                            DnsFilterProfiles.upstreamFor(config.profile, config.customHost)

                        if (domain != null && DnsPacketProcessor.isBlocked(domain, config)) {
                            val answer = DnsPacketProcessor.buildNxDomainResponse(query.payload)
                            if (answer != null) {
                                output.write(DnsPacketProcessor.buildAnswerPacket(query, answer))
                                DnsFilterState.bumpBlocked()
                            }
                            continue
                        }

                        try {
                            val request = DatagramPacket(
                                query.payload,
                                query.payload.size,
                                InetAddress.getByName(upstream),
                                53,
                            )
                            socket.send(request)
                            val responseBuffer = ByteArray(65535)
                            val response = DatagramPacket(responseBuffer, responseBuffer.size)
                            socket.receive(response)
                            val answer = response.data.copyOfRange(
                                response.offset,
                                response.offset + response.length,
                            )
                            output.write(DnsPacketProcessor.buildAnswerPacket(query, answer))
                            DnsFilterState.bumpAllowed()
                        } catch (e: Exception) {
                            DnsFilterState.bumpFailed()
                        }
                    }
                    try {
                        input.close()
                        output.close()
                    } catch (_: Exception) {
                    }
                } catch (e: Exception) {
                    DnsFilterState.mark("FILTER_ERROR")
                } finally {
                    try {
                        socket?.close()
                    } catch (_: Exception) {
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
            "screenguard-dns-filter",
        )
        worker?.start()
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

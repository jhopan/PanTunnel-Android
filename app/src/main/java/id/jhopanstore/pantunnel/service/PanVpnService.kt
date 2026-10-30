package id.jhopanstore.pantunnel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import id.jhopanstore.pantunnel.MainActivity
import id.jhopanstore.pantunnel.R
import id.jhopanstore.pantunnel.core.SingboxConfig
import id.jhopanstore.pantunnel.core.TunnelDiagnostics
import id.jhopanstore.pantunnel.core.TunnelState
import id.jhopanstore.pantunnel.core.VlessConfig
import id.jhopanstore.pantunnel.core.VlessParser
import id.jhopanstore.pantunnel.data.ProfileRepository
import id.jhopanstore.pantunnel.data.Settings
import id.jhopanstore.pantunnel.data.SettingsRepository
import io.github.sagernet.libbox.libbox.BoxService
import io.github.sagernet.libbox.libbox.InterfaceUpdateListener
import io.github.sagernet.libbox.libbox.Libbox
import io.github.sagernet.libbox.libbox.NetworkInterfaceIterator
import io.github.sagernet.libbox.libbox.PlatformInterface
import io.github.sagernet.libbox.libbox.SetupOptions
import io.github.sagernet.libbox.libbox.TunOptions
import io.github.sagernet.libbox.libbox.WIFIState
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground VPN service running sing-box (libbox) with a gVisor TUN.
 *
 * Lifecycle contract:
 *  - CONNECT -> Connecting -> Checking -> Connected (only after a real 204 through the tunnel)
 *  - probe failures -> Reconnecting (max 3) -> Failed
 *  - the process stays alive when the app is swiped away, and survives network changes
 */
class PanVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private lateinit var statusStore: TunnelStatusStore
    private lateinit var profiles: ProfileRepository
    private lateinit var settingsRepo: SettingsRepository

    private var core: BoxService? = null
    private var tun: ParcelFileDescriptor? = null
    private var interfaceListener: InterfaceUpdateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var running = false
    private var connecting = false
    private var failedProbes = 0
    private var autoReconnects = 0
    private var terminalFailure = false

    private var healthyStable = false
    private var probeJob: Job? = null
    private var pingJob: Job? = null
    private var heartbeatJob: Job? = null

    /** Debug-only connection steps (visible when debug mode is on). */
    private val logLines = ArrayDeque<String>()

    /** Keep-alive lines: always rendered, because they are the user's proof of life. */
    private val statusLines = ArrayDeque<String>()

    private var baseState: TunnelState = TunnelState.Idle
    private var baseDetail = ""
    private var profileName = ""
    private var lastCoreLogAt = 0L
    private var meterBaseRx = -1L
    private var meterBaseTx = -1L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (running) triggerProbeSoon()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        statusStore = TunnelStatusStore(this)
        profiles = ProfileRepository(this)
        settingsRepo = SettingsRepository(this)

        createNotificationChannel()

        runCatching {
            Libbox.setup(SetupOptions().apply { basePath = filesDir.absolutePath })
        }.onFailure { Log.e(TAG, "libbox setup failed", it) }

        heartbeatJob = scope.launch {
            while (isActive) {
                if (running) statusStore.heartbeat()
                delay(HEARTBEAT_MS)
            }
        }

        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            disconnect(userInitiated = true)
            return START_NOT_STICKY
        }

        val uri = intent?.getStringExtra(EXTRA_URI)
            ?: profiles.activeUri().takeIf { it.isNotBlank() }

        if (uri.isNullOrBlank()) {
            publish(TunnelState.Failed, "Belum ada config. Import link vless:// dulu.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasNetwork()) {
            publish(TunnelState.Failed, "Tidak ada jaringan — nyalakan Wi-Fi atau data seluler.")
            stopSelf()
            return START_NOT_STICKY
        }

        val settings = settingsRepo.load()
        profileName = profiles.activeProfile()?.displayName.orEmpty()

        goForeground("Menghubungkan…")
        synchronized(lock) {
            closeCoreLocked()
            running = false
            connecting = true
            failedProbes = 0
            autoReconnects = 0
            terminalFailure = false
            healthyStable = false
        }
        logLines.clear()
        clearStatusLines()
        meterBaseRx = -1L
        meterBaseTx = -1L

        publish(TunnelState.Connecting, "Menghubungkan ke server…")

        scope.launch { connect(uri, settings) }
        return START_STICKY
    }

    override fun onRevoke() {
        disconnect(userInitiated = false)
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away: keep the tunnel alive.
        if (running && !connecting) {
            val restart = Intent(applicationContext, PanVpnService::class.java)
                .putExtra(EXTRA_URI, profiles.activeUri())
            runCatching { startForegroundService(restart) }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val failed = synchronized(lock) { terminalFailure }
        probeJob?.cancel()
        pingJob?.cancel()
        heartbeatJob?.cancel()
        closeCore()
        runCatching { unregisterReceiver(screenReceiver) }
        if (!failed) statusStore.clearSession()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ connect

    private suspend fun connect(uri: String, settings: Settings) {
        try {
            val original = VlessParser.parse(uri)
            log("Target ${original.address}:${original.port} • path ${original.path} • sni ${original.sni}")

            val dialAddress = resolveIpv4(original.address)
            if (dialAddress != original.address) log("DNS ${original.address} → $dialAddress")

            val config = original.copy(address = dialAddress)
            val json = SingboxConfig.build(config, filesDir.absolutePath, settings.debugMode)
            Libbox.checkConfig(json)
            log("Config sing-box valid")

            closeCore()
            val service = Libbox.newService(json, Platform())
            service.start()

            synchronized(lock) {
                if (!connecting) { // user hit DISCONNECT while we were building
                    log("Dibatalkan pengguna")
                    runCatching { service.close() }
                    return
                }
                core = service
                connecting = false
                running = true
            }
            log("Core sing-box berjalan (${coreVersion()})")

            goForeground("Memeriksa internet…")
            publish(TunnelState.Checking, "Memeriksa koneksi lewat tunnel…")

            val failure = awaitHealthyTunnel()
            synchronized(lock) { if (!running) { closeCoreLocked(); return } }

            if (failure != null) {
                fail(failure)
                return
            }

            logLines.clear()
            goForeground("Terhubung")
            publish(TunnelState.Connected, "")
            resetMeter()

            synchronized(lock) { failedProbes = 0; autoReconnects = 0 }
            startProbeLoop(settings)
            startPingLoop(settings)
        } catch (error: Throwable) {
            Log.e(TAG, "connect failed", error)
            fail(TunnelDiagnostics.fromThrowable(error))
        }
    }

    /** Up to [PROBE_ATTEMPTS] probes; returns null when the tunnel really passes traffic. */
    private suspend fun awaitHealthyTunnel(): String? {
        val probe = ProbeClient(SingboxConfig.PROXY_PORT)
        var last = "Internet check gagal"
        for (attempt in 1..PROBE_ATTEMPTS) {
            if (attempt > 1) delay(PROBE_GAP_MS)
            synchronized(lock) { if (!running || connecting) return null }
            goForeground("Memeriksa internet… ($attempt/$PROBE_ATTEMPTS)")
            when (val result = probe.check(HEALTH_URL)) {
                is ProbeClient.Result.Success -> {
                    log("Probe OK: HTTP ${result.code} (${result.elapsedMs} ms)")
                    return null
                }
                is ProbeClient.Result.Failure -> {
                    last = if (result.code > 0) {
                        TunnelDiagnostics.fromHttpStatus(result.code)
                    } else {
                        "Tidak ada respons dari tunnel (${result.reason})."
                    }
                    log("Probe $attempt/$PROBE_ATTEMPTS gagal: $last")
                }
            }
        }
        return last
    }

    private fun startProbeLoop(settings: Settings) {
        probeJob?.cancel()
        probeJob = scope.launch {
            while (isActive) {
                // Stable tunnel -> rare probes (battery). Degraded -> probe sooner to recover.
                delay(if (healthyStable) STABLE_PROBE_MS else RECOVER_PROBE_MS)
                if (!isActive) break
                val healthy = synchronized(lock) { running }
                if (!healthy) break
                val failure = verifyTunnel()
                if (failure == null) {
                    synchronized(lock) { failedProbes = 0; autoReconnects = 0; healthyStable = true }
                    writeMeter()
                    continue
                }
                val shouldReconnect = synchronized(lock) {
                    healthyStable = false
                    failedProbes++
                    if (failedProbes >= PROBE_FAIL_LIMIT && autoReconnects < MAX_AUTO_RECONNECTS) {
                        failedProbes = 0
                        autoReconnects++
                        running = false
                        connecting = true
                        true
                    } else {
                        failedProbes = 0
                        false
                    }
                }
                if (shouldReconnect) {
                    log("Reconnect otomatis ${autoReconnects}/$MAX_AUTO_RECONNECTS…")
                    goForeground("Menyambung ulang…")
                    publish(TunnelState.Reconnecting, "Menyambung ulang…")
                    reconnect()
                    break
                } else {
                    fail(failure)
                    break
                }
            }
        }
    }

    private fun triggerProbeSoon() {
        // Network/screen changed: verify sooner than the steady-state interval.
        scope.launch {
            delay(NETWORK_EVENT_PROBE_DELAY_MS)
            if (synchronized(lock) { running } && verifyTunnel() == null) {
                synchronized(lock) { failedProbes = 0 }
            }
        }
    }

    private fun reconnect() {
        val uri = profiles.activeUri()
        if (uri.isBlank()) { disconnect(userInitiated = false); return }
        synchronized(lock) {
            closeCoreLocked()
            running = false
            connecting = true
        }
        scope.launch { connect(uri, settingsRepo.load()) }
    }

    private fun verifyTunnel(): String? {
        val probe = ProbeClient(SingboxConfig.PROXY_PORT)
        return when (val result = probe.check(HEALTH_URL)) {
            is ProbeClient.Result.Success -> null
            is ProbeClient.Result.Failure ->
                if (result.code > 0) TunnelDiagnostics.fromHttpStatus(result.code)
                else "Tunnel tidak merespons (${result.reason})."
        }
    }

    private fun startPingLoop(settings: Settings) {
        pingJob?.cancel()
        if (!settings.httpPingEnabled) return
        pingJob = scope.launch {
            val probe = ProbeClient(SingboxConfig.PROXY_PORT)
            while (isActive) {
                delay(settings.pingInterval * 1000L)
                if (!isActive) break
                if (!synchronized(lock) { running }) break
                when (val result = probe.ping(settings.httpPingUrl)) {
                    is ProbeClient.Result.Success ->
                        pushPingLine("HTTP ping ${result.code} ${result.phrase} (${result.elapsedMs} ms)")
                    is ProbeClient.Result.Failure ->
                        pushPingLine("HTTP ping gagal — ${result.reason} (${result.elapsedMs} ms)")
                }
            }
        }
    }

    // ------------------------------------------------------------------ teardown

    private fun fail(reason: String) {
        synchronized(lock) {
            connecting = false
            running = false
            terminalFailure = true
        }
        closeCore()
        probeJob?.cancel()
        pingJob?.cancel()
        clearStatusLines()
        publish(TunnelState.Failed, reason)
        stopForegroundCompat()
        stopSelf()
    }

    private fun disconnect(userInitiated: Boolean) {
        synchronized(lock) {
            connecting = false
            running = false
            terminalFailure = false
        }
        probeJob?.cancel()
        pingJob?.cancel()
        writeMeter()
        closeCore()
        clearStatusLines()
        publish(TunnelState.Idle, "")
        statusStore.clearSession()
        stopForegroundCompat()
        stopSelf()
    }

    private fun closeCore() {
        synchronized(lock) { closeCoreLocked() }
    }

    private fun closeCoreLocked() {
        stopNetworkMonitor()
        runCatching { core?.close() }
        core = null
        runCatching { tun?.close() }
        tun = null
    }

    // ------------------------------------------------------------------ libbox platform

    private inner class Platform : PlatformInterface {

        override fun openTun(options: TunOptions): Int {
            val builder = Builder()
                .setSession("PanTunnel")
                .setMtu(options.mtu)
                .addAddress(SingboxConfig.TUN_ADDRESS, SingboxConfig.TUN_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            runCatching { builder.addDisallowedApplication(packageName) }

            return try {
                val descriptor = builder.establish()
                if (descriptor == null) {
                    log("TUN gagal: izin VPN dicabut / VPN lain aktif")
                    -1
                } else {
                    tun = descriptor
                    log("TUN ${SingboxConfig.TUN_ADDRESS}/${SingboxConfig.TUN_PREFIX} mtu ${options.mtu}")
                    descriptor.fd
                }
            } catch (error: Exception) {
                log("TUN gagal: ${error.javaClass.simpleName}")
                -1
            }
        }

        override fun autoDetectInterfaceControl(fd: Int) {
            if (!protect(fd)) Log.w(TAG, "protect() failed for fd $fd")
        }

        override fun clearDNSCache() = Unit

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) = stopNetworkMonitor()

        override fun findConnectionOwner(
            protocol: Int,
            source: String?,
            sourcePort: Int,
            destination: String?,
            destinationPort: Int,
        ): Int = 0

        override fun getInterfaces(): NetworkInterfaceIterator = object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = false
            override fun next(): io.github.sagernet.libbox.libbox.NetworkInterface =
                throw NoSuchElementException()
        }

        override fun includeAllNetworks(): Boolean = false

        override fun packageNameByUid(uid: Int): String = ""

        override fun readWIFIState(): WIFIState? = null

        override fun sendNotification(notification: io.github.sagernet.libbox.libbox.Notification?) = Unit

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) =
            startNetworkMonitor(listener)

        override fun uidByPackageName(packageName: String?): Int = 0

        override fun underNetworkExtension(): Boolean = false

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

        override fun useProcFS(): Boolean = false

        override fun writeLog(message: String?) {
            val line = message ?: return
            Log.i("libbox", line)
            if (!settingsRepo.load().debugMode) return
            val now = System.currentTimeMillis()
            if (now - lastCoreLogAt < CORE_LOG_THROTTLE_MS) return
            val lower = line.lowercase()
            if (lower.contains("ws closed: 1006")) return // expected when a WS stream ends
            val interesting = lower.contains("error") || lower.contains("warn") ||
                lower.contains("fatal") || lower.contains("refused") ||
                lower.contains("timeout") || lower.contains("reset by peer") ||
                (connecting && (lower.contains("dial") || lower.contains("vless") ||
                    lower.contains("tls") || lower.contains("websocket")))
            if (!interesting) return
            lastCoreLogAt = now
            log("core: ${stripAnsi(line).take(110)}")
        }
    }

    // ------------------------------------------------------------------ network monitor

    private fun startNetworkMonitor(listener: InterfaceUpdateListener?) {
        stopNetworkMonitor()
        interfaceListener = listener
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                reportNetwork(network)
                triggerProbeSoon()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                reportNetwork(network)
            }

            override fun onLost(network: Network) {
                triggerProbeSoon()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "network callback", it) }
        networkCallback = callback
        manager.activeNetwork?.let { reportNetwork(it) }
    }

    private fun stopNetworkMonitor() {
        networkCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
        interfaceListener = null
    }

    private fun reportNetwork(network: Network) {
        val listener = interfaceListener ?: return
        runCatching {
            val manager = getSystemService(ConnectivityManager::class.java) ?: return
            val caps = manager.getNetworkCapabilities(network) ?: return
            val link: LinkProperties = manager.getLinkProperties(network) ?: return
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return
            val name = link.interfaceName ?: return
            val item = runCatching { java.net.NetworkInterface.getByName(name) }.getOrNull()
            val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            listener.updateDefaultInterface(name, item?.index ?: 0, metered, false)
        }.onFailure { Log.w(TAG, "report network", it) }
    }

    // ------------------------------------------------------------------ status

    private fun publish(state: TunnelState, detail: String) {
        baseState = state
        baseDetail = detail
        statusStore.writeState(state, detail, profileName)
        TunnelBus.publish(
            TunnelBus.Event(
                state = state,
                detail = detail,
                profileName = profileName,
                logLines = visibleLines(),
            ),
        )
    }

    private fun log(line: String) {
        if (line.isBlank()) return
        val snapshot = synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            logLines.toList()
        }
        statusStore.writeLog(visibleLines())
        if (!settingsRepo.load().debugMode) return
        TunnelBus.publish(
            TunnelBus.Event(
                state = baseState,
                detail = baseDetail,
                profileName = profileName,
                logLines = visibleLines(),
            ),
        )
    }

    /** Keep-alive result line. Always rendered: this is the honest "tunnel still alive" signal. */
    private fun pushPingLine(line: String) {
        synchronized(statusLines) {
            statusLines.addLast("$line ${timestamp()}")
            while (statusLines.size > MAX_PING_LINES) statusLines.removeFirst()
        }
        statusStore.writeLog(visibleLines())
        publish(baseState, baseDetail)
    }

    /** What the status box shows: keep-alive lines always, debug steps only in debug mode. */
    private fun visibleLines(): List<String> = buildList {
        synchronized(statusLines) { addAll(statusLines) }
        if (settingsRepo.load().debugMode) {
            synchronized(logLines) { addAll(logLines) }
        }
    }

    private fun clearStatusLines() {
        synchronized(statusLines) { statusLines.clear() }
    }

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun resetMeter() {
        val rx = android.net.TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0L)
        val tx = android.net.TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0L)
        meterBaseRx = rx
        meterBaseTx = tx
        statusStore.writeMeter(0, 0, rx, tx)
    }

    private fun writeMeter() {
        if (meterBaseRx < 0 || meterBaseTx < 0) return
        val rx = android.net.TrafficStats.getUidRxBytes(Process.myUid())
        val tx = android.net.TrafficStats.getUidTxBytes(Process.myUid())
        if (rx < 0 || tx < 0) return
        statusStore.writeMeter(
            sessionRx = (rx - meterBaseRx).coerceAtLeast(0L),
            sessionTx = (tx - meterBaseTx).coerceAtLeast(0L),
            baseRx = meterBaseRx,
            baseTx = meterBaseTx,
        )
    }

    private fun resolveIpv4(host: String): String = runCatching {
        InetAddress.getAllByName(host)
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    }.getOrNull() ?: host

    private fun coreVersion(): String = runCatching { Libbox.version() }.getOrNull()?.trim().orEmpty()

    private fun hasNetwork(): Boolean = runCatching {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    private fun stripAnsi(value: String): String =
        value.replace(Regex("\u001B?\\[[0-9;]*m"), "").replace('\n', ' ').trim()

    // ------------------------------------------------------------------ notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Status VPN", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Status koneksi PanTunnel"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PanVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(profileName.ifBlank { "PanTunnel" })
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(content)
            .addAction(
                Notification.Action.Builder(null, "Disconnect", stopIntent).build(),
            )
            .setOngoing(true)
            .build()
    }

    private fun goForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    companion object {
        private const val TAG = "PanVpnService"

        const val ACTION_START = "id.jhopanstore.pantunnel.START"
        const val ACTION_STOP = "id.jhopanstore.pantunnel.STOP"
        const val EXTRA_URI = "uri"

        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 7

        private const val HEARTBEAT_MS = 15_000L
        private const val STABLE_PROBE_MS = 90_000L
        private const val RECOVER_PROBE_MS = 30_000L
        private const val NETWORK_EVENT_PROBE_DELAY_MS = 2_000L
        private const val PROBE_ATTEMPTS = 3
        private const val PROBE_GAP_MS = 1_500L
        private const val PROBE_FAIL_LIMIT = 3
        private const val MAX_AUTO_RECONNECTS = 3
        private const val CORE_LOG_THROTTLE_MS = 200L
        private const val MAX_LOG_LINES = 16
        private const val MAX_PING_LINES = 5

        private const val HEALTH_URL = "https://www.gstatic.com/generate_204"

        fun start(context: Context, uri: String) {
            val intent = Intent(context, PanVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URI, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, PanVpnService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}

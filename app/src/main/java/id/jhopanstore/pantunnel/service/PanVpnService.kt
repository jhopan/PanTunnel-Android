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
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import id.jhopanstore.pantunnel.MainActivity
import id.jhopanstore.pantunnel.R
import id.jhopanstore.pantunnel.core.CacheJanitor
import id.jhopanstore.pantunnel.core.ErrorKind
import id.jhopanstore.pantunnel.core.LogBuffer
import id.jhopanstore.pantunnel.core.NetworkErrorClassifier
import id.jhopanstore.pantunnel.core.ProbePolicy
import id.jhopanstore.pantunnel.core.TransientErrorMonitor
import id.jhopanstore.pantunnel.core.ProbeVerdict
import id.jhopanstore.pantunnel.core.SingboxConfig
import id.jhopanstore.pantunnel.core.VpnAppRules
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
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

    /**
     * Without a handler, an exception inside a coroutine vanishes silently: the service keeps
     * reporting "Connected" while probe/ping jobs are already dead. That is exactly the
     * "connected but no internet" state users cannot diagnose, so every uncaught failure is
     * surfaced as a real Failed state.
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "uncaught coroutine failure", error)
        log("Kesalahan internal: ${error.javaClass.simpleName}")
        synchronized(lock) {
            connecting = false
            running = false
        }
        runCatching { core?.close() }
        publish(TunnelState.Failed, TunnelDiagnostics.fromThrowable(error))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val lock = Any()

    private lateinit var statusStore: TunnelStatusStore
    private lateinit var profiles: ProfileRepository
    private lateinit var settingsRepo: SettingsRepository

    private var core: BoxService? = null
    private var tun: ParcelFileDescriptor? = null
    private var interfaceListener: InterfaceUpdateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Watches the not-VPN network, which is the only one that can disappear under the tunnel. */
    private var upstreamCallback: ConnectivityManager.NetworkCallback? = null

    private var running = false
    private var connecting = false
    /** True while the published state is the "waiting for network" one, so it can be lifted once a
     *  probe really passes again. */
    private var degraded = false
    private var failedProbes = 0
    private var autoReconnects = 0
    private var terminalFailure = false

    private var healthyStable = false
    private var probeJob: Job? = null
    private var pingJob: Job? = null
    private var heartbeatJob: Job? = null

    /** Core events. Self-expiring and bounded, so old errors cannot masquerade as live ones. */
    private val logLines = LogBuffer(MAX_LOG_LINES)

    /** Keep-alive lines: always rendered, because they are the user's proof of life. */
    private val statusLines = LogBuffer(MAX_PING_LINES)

    /** Counts dead-connection errors; a burst means the transport died under the live flows. */
    private val transientMonitor = TransientErrorMonitor()

    private var lastLogWriteAt = 0L
    private var reconnecting = false

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

        // sing-box never trims cache.db; drop it before it becomes invisible bloat.
        val pruned = CacheJanitor.pruneIfOversized(filesDir)
        if (pruned > 0) Log.i(TAG, "pruned oversized cache.db (${pruned / 1024} KB)")

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
            disconnect()
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
            degraded = false
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
        disconnect()
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
            val json = SingboxConfig.build(config, filesDir.absolutePath, verboseLogging = false)
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
            synchronized(lock) { reconnecting = false }
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
            // Re-check right before announcing: a DISCONNECT that lands between the check above
            // and here used to be overwritten by this publish, leaving the card stuck on
            // "Terhubung" with a service that was already gone.
            val stillWanted = synchronized(lock) { running && !connecting }
            if (!stillWanted) {
                log("Dibatalkan pengguna")
                closeCoreLocked()
                return
            }
            goForeground("Terhubung")
            publish(TunnelState.Connected, "")
            synchronized(lock) { degraded = false }
            resetMeter()

            synchronized(lock) { failedProbes = 0; autoReconnects = 0 }
            startProbeLoop()
            startPingLoop(settings)
        } catch (cancelled: CancellationException) {
            // The scope was torn down (DISCONNECT, stopSelf, service restart). Reporting this as a
            // connection failure produced a user-facing "JobCancellationException — cek server,
            // port, path" even though nothing was wrong with the profile.
            throw cancelled
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
                    transientMonitor.reset()
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

    private fun startProbeLoop() {
        probeJob?.cancel()
        probeJob = scope.launch {
            while (isActive) {
                // Stable tunnel -> rare probes (battery). Degraded -> probe sooner to recover.
                delay(if (healthyStable) STABLE_PROBE_MS else RECOVER_PROBE_MS)
                if (!isActive) break
                val healthy = synchronized(lock) { running }
                if (!healthy) break
                val failure = probeWithRetry()
                if (failure == null) {
                    val decision = ProbePolicy.onSuccess()
                    val wasDegraded = synchronized(lock) {
                        failedProbes = decision.failedProbes
                        autoReconnects = decision.autoReconnects
                        healthyStable = true
                        val was = degraded
                        degraded = false
                        was
                    }
                    transientMonitor.reset()
                    if (wasDegraded) {
                        // Traffic flows again after a network gap: replace the waiting message instead
                        // of leaving it on screen. A real 204 just came back, so this is honest.
                        goForeground("Terhubung")
                        publish(TunnelState.Connected, "")
                    }
                    writeMeter()
                    continue
                }

                val offline = !hasDefaultNetwork()
                val decision = synchronized(lock) {
                    val d = ProbePolicy.onFailure(
                        failedProbes = failedProbes,
                        autoReconnects = autoReconnects,
                        limit = PROBE_FAIL_LIMIT,
                        maxReconnects = MAX_AUTO_RECONNECTS,
                    )
                    failedProbes = d.failedProbes
                    autoReconnects = d.autoReconnects
                    if (d.markUnstable) healthyStable = false
                    // With no network at all, spending a reconnect only ends in a terminal Failed
                    // the user has to clear by hand; waiting recovers on its own.
                    if (d.verdict == ProbeVerdict.RECONNECT && !offline) {
                        running = false
                        connecting = true
                    }
                    d
                }

                when (decision.verdict) {
                    ProbeVerdict.KEEP_RETRYING -> {
                        // Stay connected: a single slow probe is not proof the tunnel is dead.
                        log("Probe gagal ${decision.failedProbes}/$PROBE_FAIL_LIMIT — tetap tersambung")
                        continue
                    }
                    ProbeVerdict.RECONNECT -> {
                        if (offline) {
                            markWaitingForNetwork()
                            continue
                        }
                        log("Reconnect otomatis ${decision.autoReconnects}/$MAX_AUTO_RECONNECTS...")
                        goForeground("Menyambung ulang...")
                        publish(TunnelState.Reconnecting, "Menyambung ulang...")
                        reconnect()
                        break
                    }
                    ProbeVerdict.GIVE_UP -> {
                        fail(failure)
                        break
                    }
                }
            }
        }
    }

    private fun triggerProbeSoon() {
        // Network/screen changed: verify sooner than the steady-state interval.
        scope.launch {
            delay(NETWORK_EVENT_PROBE_DELAY_MS)
            if (!synchronized(lock) { running }) return@launch
            if (verifyTunnel() == null) {
                val wasDegraded = synchronized(lock) {
                    failedProbes = 0
                    val was = degraded
                    degraded = false
                    was
                }
                if (wasDegraded) {
                    goForeground("Terhubung")
                    publish(TunnelState.Connected, "")
                }
                return@launch
            }
            // The network just changed and the tunnel cannot pass traffic: count it, drop to the
            // short probe cadence, and restart the loop so that cadence applies now instead of
            // after the pending stable interval (was up to 90 s of a green "Terhubung" while
            // every keep-alive ping was already failing).
            synchronized(lock) {
                failedProbes += 1
                healthyStable = false
            }
            if (!hasDefaultNetwork()) markWaitingForNetwork()
            if (probeJob?.isActive == true) {
                probeJob?.cancel()
                startProbeLoop()
            }
        }
    }

    /** True when a non-VPN network can carry traffic. `activeNetwork` alone is not enough: with our
     *  own VPN up it *is* the VPN, so it stays non-null while the device has no upstream at all. */
    private fun hasDefaultNetwork(): Boolean = runCatching {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
        manager.allNetworks.any { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }.getOrDefault(true)

    /**
     * Publishes "waiting for network" once per outage. The tunnel cannot be up while the device has
     * no network at all, so staying green here was the one remaining dishonest state.
     */
    private fun markWaitingForNetwork() {
        val first = synchronized(lock) {
            if (!running || degraded) false else { degraded = true; true }
        }
        if (!first) return
        log("Tidak ada jaringan — menunggu koneksi")
        goForeground("Menunggu jaringan...")
        publish(TunnelState.Reconnecting, "Tidak ada jaringan — menunggu koneksi...")
    }

    private fun reconnect() {
        val uri = profiles.activeUri()
        if (uri.isBlank()) { disconnect(); return }
        synchronized(lock) {
            closeCoreLocked()
            running = false
            connecting = true
        }
        scope.launch { connect(uri, settingsRepo.load()) }
    }

    /**
     * One retry before a probe is counted as a failure.
     *
     * Mobile networks drop a single packet often enough that a bare probe produces false
     * negatives; a second attempt two seconds later filters that noise out.
     */
    private suspend fun probeWithRetry(): String? {
        val first = verifyTunnel() ?: return null   // healthy: nothing to retry
        delay(PROBE_RETRY_GAP_MS)
        if (!synchronized(lock) { running }) return null
        val second = verifyTunnel()
        if (second == null) {
            log("Probe gagal sekali, retry berhasil")
            return null
        }
        return second.ifBlank { first }
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

    private fun disconnect() {
        synchronized(lock) {
            connecting = false
            running = false
            terminalFailure = false
            reconnecting = false
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

            // Android forbids mixing allow and disallow lists on one builder, so the exact set
            // is decided up-front by VpnAppRules (allowlist OR blocklist, never both).
            val splitError = applySplitTunnel(builder)
            if (splitError != null) {
                log("TUN gagal: $splitError")
                return -1
            }

            return try {
                val descriptor = builder.establish()
                if (descriptor == null) {
                    // establish() returns null for three very different causes; guessing wrong
                    // sends the user to the wrong Settings screen.
                    val reason = diagnoseEstablishFailure()
                    Log.e(TAG, "establish() null: $reason")
                    log("TUN gagal: $reason")
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
            val now = System.currentTimeMillis()
            if (now - lastCoreLogAt < CORE_LOG_THROTTLE_MS) return
            val kind = NetworkErrorClassifier.classify(line, connecting)
            if (kind == ErrorKind.IGNORE) return
            lastCoreLogAt = now
            // 160 chars: the actual reason ("connection reset by peer") sits at the end of the line
            // and used to be cut off by the old 110-char limit.
            log("core: ${stripAnsi(line).take(160)}", kind)

            // A burst of dead-connection errors means the WebSocket died and the open flows are
            // stuck. Rebuild before the user notices their apps have no internet.
            if (kind == ErrorKind.TRANSIENT && transientMonitor.record(now)) {
                transientMonitor.reset()
                reconnectAfterTransportFailure()
            }
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
        // Once our own VPN is up it *is* the default network, so the callback above stops seeing the
        // underlying Wi-Fi/cellular. Losing that upstream never reached the service, which is why a
        // dead tunnel could stay green for minutes. Watch the not-VPN network explicitly.
        val upstream = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = triggerProbeSoon()
            override fun onLost(network: Network) = triggerProbeSoon()
        }
        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                upstream,
            )
        }.onFailure { Log.w(TAG, "upstream callback", it) }
        upstreamCallback = upstream
        manager.activeNetwork?.let { reportNetwork(it) }
    }

    private fun stopNetworkMonitor() {
        networkCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            }
        }
        upstreamCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
        upstreamCallback = null
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
            // A network can disappear between the callback and this lookup (cellular handover
            // renames rmnet_data*). Handing sing-box an unresolvable name makes it log
            // "find updated interface: no such network interface" and drop its binding, so keep
            // the last good interface instead.
            if (item == null) {
                Log.w(TAG, "interface $name tidak ditemukan, lewati update")
                return
            }
            val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            listener.updateDefaultInterface(name, item.index, metered, false)
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

    private fun log(line: String, kind: ErrorKind = ErrorKind.IGNORE) {
        if (line.isBlank()) return
        val label = NetworkErrorClassifier.label(kind)
        logLines.add(if (label.isEmpty()) line else "$line ($label)", System.currentTimeMillis())
        persistLog()
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
        statusLines.add("$line ${timestamp()}", System.currentTimeMillis())
        persistLog()
        publish(baseState, baseDetail)
    }

    /** What the status box shows. Both halves expire, so the box reflects the present only. */
    private fun visibleLines(): List<String> {
        val now = System.currentTimeMillis()
        return statusLines.recent(now, PING_TTL_MS) + logLines.recent(now, LOG_TTL_MS)
    }

    private fun clearStatusLines() = statusLines.clear()

    /**
     * Throttled status write. The ping loop fires every few seconds and every call used to hit
     * SharedPreferences; this keeps the box current without writing on each tick.
     */
    private fun persistLog() {
        val now = System.currentTimeMillis()
        if (now - lastLogWriteAt < LOG_WRITE_THROTTLE_MS) return
        lastLogWriteAt = now
        statusStore.writeLog(visibleLines())
    }

    /**
     * Rebuilds the tunnel after the transport dies underneath the open flows.
     *
     * The HTTP probe cannot detect this on its own: it opens a fresh socket to the local proxy and
     * succeeds even while every existing flow is wedged on a dead WebSocket. Reconnecting drops
     * those flows and they are re-established on a new socket.
     */
    private fun reconnectAfterTransportFailure() {
        if (!hasDefaultNetwork()) {
            // Dead transport because the device lost its network: waiting is the honest state, and a
            // reconnect here would only burn the budget and end in a terminal Failed.
            markWaitingForNetwork()
            return
        }
        val proceed = synchronized(lock) {
            if (!running || reconnecting || connecting) return@synchronized false
            reconnecting = true
            true
        }
        if (!proceed) return
        log("Transport mati — menyambung ulang", ErrorKind.FATAL)
        scope.launch {
            goForeground("Menyambung ulang…")
            publish(TunnelState.Reconnecting, "Menyambung ulang…")
            reconnect()
            synchronized(lock) { reconnecting = false }
        }
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

    /** Distinguishes "no consent" from "another VPN is active" so the message is actionable. */
    private fun diagnoseEstablishFailure(): String {
        val consentMissing = android.net.VpnService.prepare(this) != null
        // Active network already carrying a VPN transport means another client owns the tunnel.
        val anotherVpn = runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            val active = cm?.activeNetwork ?: return@runCatching false
            cm.getNetworkCapabilities(active)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }.getOrDefault(false)

        return when {
            consentMissing -> "izin VPN belum diberikan — buka ulang aplikasi lalu setujui"
            anotherVpn -> "VPN lain sedang aktif — matikan dulu VPN lain"
            else -> "TUN ditolak sistem — cek Always-on VPN di Pengaturan"
        }
    }

    /**
     * Applies the split-tunnel selection to the TUN builder.
     *
     * @return null on success, or a user-facing error message. Android rejects mixing
     *   `addAllowedApplication` with `addDisallowedApplication`, so exactly one list is applied;
     *   a rejected package is reported instead of being swallowed.
     */
    private fun applySplitTunnel(builder: Builder): String? {
        val split = settingsRepo.load().splitTunnel.sanitized(packageName)
        val rules = VpnAppRules.forSplit(split, packageName)

        var applied = 0
        for (pkg in rules.allowed) {
            val error = runCatching { builder.addAllowedApplication(pkg) }.exceptionOrNull()
            if (error == null) applied++ else Log.w(TAG, "allow $pkg failed", error)
        }
        for (pkg in rules.disallowed) {
            val error = runCatching { builder.addDisallowedApplication(pkg) }.exceptionOrNull()
            if (error == null) applied++ else Log.w(TAG, "disallow $pkg failed", error)
        }

        // If the user picked a list but nothing could be bound, the tunnel would silently behave
        // as "everything through VPN" — refuse instead of lying.
        if (split.isActive && applied == 0) {
            return "split tunnel gagal diterapkan — tidak ada aplikasi yang bisa diikat"
        }
        if (split.isActive) {
            val mode = if (split.allowMode) "hanya" else "kecuali"
            log("split tunnel: $mode $applied app")
        }
        return null
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
        private const val PROBE_RETRY_GAP_MS = 2_000L
        private const val PROBE_GAP_MS = 1_500L
        private const val PROBE_FAIL_LIMIT = 3
        private const val MAX_AUTO_RECONNECTS = 3
        private const val CORE_LOG_THROTTLE_MS = 200L
        private const val MAX_LOG_LINES = 10
        private const val MAX_PING_LINES = 3

        /** How long an entry stays in the status box before it ages out. */
        private const val LOG_TTL_MS = 60_000L
        private const val PING_TTL_MS = 30_000L
        private const val LOG_WRITE_THROTTLE_MS = 1_500L

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

        /**
         * Asks the running service to disconnect.
         *
         * When the service is already gone, `startService` can throw (Android 8+ refuses a
         * background service start). That exception used to escape the click listener and the UI
         * stayed frozen on the previous state, so the board is cleared here as a fallback.
         */
        fun stop(context: Context) {
            val intent = Intent(context, PanVpnService::class.java).setAction(ACTION_STOP)
            val delivered = runCatching { context.startService(intent) }.isSuccess
            if (delivered) return
            Log.w(TAG, "stop: service unreachable, clearing status board")
            runCatching { TunnelStatusStore(context).clearSession() }
            TunnelBus.publish(TunnelBus.Event(state = TunnelState.Idle))
        }
    }
}

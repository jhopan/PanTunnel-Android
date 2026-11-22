package id.jhopanstore.pantunnel

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.text.method.ScrollingMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import id.jhopanstore.pantunnel.core.CacheJanitor
import id.jhopanstore.pantunnel.core.DiagnosticsReport
import id.jhopanstore.pantunnel.core.License
import id.jhopanstore.pantunnel.core.LicenseCodec
import id.jhopanstore.pantunnel.core.Profile
import id.jhopanstore.pantunnel.core.ProfileBook
import id.jhopanstore.pantunnel.core.TunnelDiagnostics
import id.jhopanstore.pantunnel.core.StatusFreshness
import id.jhopanstore.pantunnel.core.TunnelState
import id.jhopanstore.pantunnel.core.VlessConfig
import id.jhopanstore.pantunnel.core.VlessParser
import id.jhopanstore.pantunnel.data.ProfileRepository
import id.jhopanstore.pantunnel.data.Settings as TunnelSettings
import id.jhopanstore.pantunnel.data.SettingsRepository
import id.jhopanstore.pantunnel.databinding.ActivityMainBinding
import id.jhopanstore.pantunnel.databinding.DialogAboutBinding
import id.jhopanstore.pantunnel.databinding.DialogExportLicenseBinding
import id.jhopanstore.pantunnel.databinding.DialogSettingsBinding
import id.jhopanstore.pantunnel.service.PanVpnService
import id.jhopanstore.pantunnel.service.TunnelBus
import id.jhopanstore.pantunnel.service.TunnelStatusStore
import id.jhopanstore.pantunnel.ui.TrafficMeter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single screen: profile cards, one big CONNECT button, honest status box.
 *
 * The activity never invents state — it renders [TunnelBus] events, and falls back to the
 * persisted [TunnelStatusStore] snapshot when it starts while the service is already running.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var profiles: ProfileRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var statusStore: TunnelStatusStore
    private lateinit var meter: TrafficMeter
    private var stateWasConnected = false
    private lateinit var adapter: ProfileAdapter

    private var settings: TunnelSettings = TunnelSettings()
    private var license: License? = null
    private var hwid: String = ""

    private var connected = false

    /**
     * Data string of the deep link already consumed. The task keeps its launch intent, and with
     * `CLEAR_TOP` the system hands that same intent back through [onNewIntent] every time this
     * activity returns to the top — previously that reopened the editor after each save and
     * produced duplicate profiles.
     */
    private var handledLink: String? = null

    private var pendingLicensePayload: String? = null
    private var pendingExportPayload: String? = null

    // ------------------------------------------------------------------ launchers

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startTunnel()
        }

    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { readImport(it) }
        }

    private val exportLicenseLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(JVS_MIME)) { uri ->
            val payload = pendingLicensePayload
            pendingLicensePayload = null
            if (uri == null) return@registerForActivityResult
            if (payload != null) {
                writeText(uri, payload, "Locked config diexport")
            } else {
                toast("Export gagal: data lisensi hilang, coba lagi")
            }
        }

    /** Diagnostic report — copied to the clipboard, never written to a file. */
    private fun exportDiagnostics() {
        val report = buildDiagnosticsReport()
        if (report.isNullOrBlank()) {
            toast("Gagal membuat laporan")
            return
        }
        // Clipboard has a practical size ceiling and long text gets truncated by some apps, so
        // the report is trimmed to MAX_CLIPBOARD_CHARS from the tail (newest lines survive).
        val trimmed = if (report.length <= MAX_CLIPBOARD_CHARS) {
            report
        } else {
            "…(dipotong)\n" + report.takeLast(MAX_CLIPBOARD_CHARS - 12)
        }
        copyToClipboard(trimmed, "Log diagnosa dicopy (${trimmed.length} karakter)")
    }

    /**
     * Plain (unencrypted) .pantunnel: just the vless:// link, importable on any device.
     * The payload is recomputed from the active profile instead of trusting a field, so a
     * configuration change while the file picker is open cannot produce an empty file.
     */
    private val exportFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(JVS_MIME)) { uri ->
            if (uri == null) return@registerForActivityResult
            val payload = pendingExportPayload
                ?: runCatching { activeUri() }.getOrNull()
            pendingExportPayload = null
            if (payload.isNullOrBlank()) {
                toast("Export gagal: tidak ada config aktif")
            } else {
                writeText(uri, payload, "File .pantunnel diexport")
            }
        }

    private val editProfileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshProfiles()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        profiles = ProfileRepository(this)
        settingsRepo = SettingsRepository(this)
        statusStore = TunnelStatusStore(this)
        meter = TrafficMeter(statusStore)
        settings = settingsRepo.load()
        hwid = profiles.hwid()

        setupList()
        setupConnectButton()
        renderVersion()
        applyTrafficVisibility(settings.showTraffic)

        requestNotificationPermission()
        refreshProfiles()
        loadLicense()
        observeTunnel()
        // First creation only: a recreate (configuration change, theme flip) replays this same
        // intent, and handling it again reopened the editor right after the user saved.
        if (savedInstanceState == null) {
            handledLink = intent?.dataString
            handleIncomingIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        settings = settingsRepo.load()
        applyTrafficVisibility(settings.showTraffic)
        renderSnapshot(statusStore.snapshot())
        startTrafficLoop()
        batteryGuard()
    }

    override fun onPause() {
        super.onPause()
        // One loop per resume, never two: a leaked loop would double the TrafficStats sampling.
        trafficJob?.cancel()
        trafficJob = null
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        // A redelivered launch intent must not be handled twice (see [handledLink]).
        val data = intent.dataString
        if (data != null && data == handledLink) return
        handledLink = data
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_import_clipboard -> { importFromClipboard(); true }
        R.id.action_import_file -> { openImportFile(); true }
        R.id.action_export_clipboard -> { copyToClipboard(activeUri(), "Link dicopy"); true }
        R.id.action_export_file -> { exportPlainFile(); true }
        R.id.action_export_license -> { showExportLicenseDialog(); true }
        R.id.action_export_log -> { exportDiagnostics(); true }
        R.id.action_split_tunnel -> {
            startActivity(Intent(this, SplitTunnelActivity::class.java))
            true
        }
        R.id.action_settings -> { showSettingsDialog(); true }
        R.id.action_clear -> { confirmClearConfig(); true }
        R.id.action_about -> { showAboutDialog(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------ wiring

    private fun setupList() {
        adapter = ProfileAdapter(
            onSelect = { profile -> selectProfile(profile) },
            onEdit = { profile -> openEditor(profile.id) },
            onDelete = { profile -> confirmDeleteProfile(profile) },
            onAdd = { openEditor(null) },
        )
        binding.profileList.layoutManager = LinearLayoutManager(this)
        binding.profileList.adapter = adapter
    }

    private fun setupConnectButton() {
        binding.connect.setOnClickListener {
            if (connected) stopTunnel() else requestConnect()
        }
    }

    private fun renderVersion() {
        val name = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        binding.version.text = "v$name • PanTunnel"
    }

    private fun observeTunnel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TunnelBus.state.collect { event ->
                    if (event.revision == 0L) return@collect
                    renderState(event.state, event.detail, event.logLines)
                }
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun renderSnapshot(snapshot: TunnelStatusStore.Snapshot) {
        val stale = StatusFreshness.isStale(
            snapshot.state,
            snapshot.lastSeen,
            System.currentTimeMillis(),
        )
        val state = if (stale) TunnelState.Failed else snapshot.state
        val detail = when {
            stale -> "VPN berhenti — service tidak berjalan lagi."
            else -> snapshot.detail
        }
        renderState(state, detail, snapshot.logLines)
        license?.let { renderLicenseBanner(it) }

        // Only nag when the tunnel was up and the process died without us tearing it down.
        if (stale) killedBySystemHint()
    }

    /**
     * The service heartbeats every 15s. If the last state was Connected but the heartbeat is
     * older than [TunnelStatusStore.Snapshot.STALE_AFTER_MS], the OS killed us — the single
     * most common "VPN mati sendiri" cause on MIUI/HyperOS/ColorOS.
     */
    private fun killedBySystemHint() {
        if (killedHintShown || isFinishing) return
        killedHintShown = true

        val powerManager = getSystemService(PowerManager::class.java)
        val ignoring = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        val acknowledged = settingsRepo.flag(SettingsRepository.KEY_BATTERY_ACK)

        val message = if (!ignoring && !acknowledged) {
            "Android atau penghemat daya mematikan VPN saat aplikasi tidak dipakai.\n\n" +
                "Agar tetap hidup 24/7:\n" +
                "1. Atur penghemat daya PanTunnel ke \"Tidak dibatasi\"\n" +
                "2. Kunci aplikasi di Recents (tahan kartu → ikon gembok)\n" +
                "3. Jangan swipe aplikasi dari Recents"
        } else {
            "Penghemat daya sudah diatur, tapi VPN tetap berhenti.\n\n" +
                "Di MIUI/HyperOS/ColorOS, langkah paling efektif:\n" +
                "1. Kunci aplikasi di Recents (tahan kartu → ikon gembok)\n" +
                "2. Izinkan Autostart (Settings → Apps → PanTunnel)\n" +
                "3. Jangan swipe aplikasi dari Recents"
        }

        AlertDialog.Builder(this)
            .setTitle("VPN dimatikan sistem")
            .setMessage(message)
            .setPositiveButton("Buka penghemat daya") { _, _ -> openBatterySetting() }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun openBatterySetting() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }
        }
    }

    private fun renderState(state: TunnelState, detail: String, logLines: List<String>) {
        val label = when (state) {
            TunnelState.Idle -> "Terputus"
            TunnelState.Connecting -> "Menghubungkan…"
            TunnelState.Checking -> "Memeriksa internet…"
            TunnelState.Connected -> "Terhubung"
            TunnelState.Reconnecting -> "Menyambung ulang…"
            TunnelState.Failed -> "Gagal"
        }

        connected = state.isActive
        binding.connect.text = getString(if (connected) R.string.disconnect else R.string.connect)
        // Always tappable. While Connecting/Checking the label already reads "DISCONNECT", and
        // that tap is the only way out of a slow handshake. Disabling it on !isBusy left users
        // staring at a button that said DISCONNECT but did nothing.
        binding.connect.isEnabled = true
        binding.connect.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (connected) R.color.danger else R.color.accent,
        )

        val box = buildString {
            append(label)
            if (detail.isNotBlank()) append('\n').append(detail)
            logLines.forEach { append('\n').append(it) }
        }
        binding.status.text = box
        binding.status.movementMethod = ScrollingMovementMethod()
        binding.status.setTextColor(
            ContextCompat.getColor(
                this,
                when (state) {
                    TunnelState.Connected -> R.color.accent
                    TunnelState.Failed -> R.color.danger
                    TunnelState.Reconnecting -> R.color.warning
                    else -> R.color.text_secondary
                },
            ),
        )

        if (state == TunnelState.Idle || state == TunnelState.Failed) {
            meter.clear()
            binding.traffic.text = getString(R.string.traffic_placeholder)
        } else if (state == TunnelState.Connected && !stateWasConnected) {
            // Only a real transition seeds a new baseline; doing it on every repaint made the
            // speed read "0 B/s" forever because two samples never spanned an interval.
            meter.reset()
        }
        stateWasConnected = state == TunnelState.Connected
    }

    private fun renderLicenseBanner(license: License) {
        val locked = license.lock
        binding.lockBanner.visibility = if (locked) android.view.View.VISIBLE else android.view.View.GONE
        if (!locked) return
        val expiry = if (license.expiry > 0) {
            "\nBerlaku s.d. " + SimpleDateFormat.getDateInstance(SimpleDateFormat.MEDIUM)
                .format(Date(license.expiry))
        } else {
            "\nTanpa batas waktu"
        }
        binding.lockBanner.text = (license.name.ifBlank { "PanTunnel" }) + expiry
    }

    private fun applyTrafficVisibility(visible: Boolean) {
        binding.traffic.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ------------------------------------------------------------------ traffic

    private fun startTrafficLoop() {
        trafficJob?.cancel()
        trafficJob = lifecycleScope.launch {
            while (isActive) {
                if (connected && settings.showTraffic) updateTraffic()
                delay(TRAFFIC_TICK_MS)
            }
        }
    }

    private fun updateTraffic() {
        val reading = meter.sample() ?: return
        binding.traffic.text = reading.format()
    }

    // ------------------------------------------------------------------ profiles

    private fun refreshProfiles() {
        // ensureDefault() also re-points the active id at a real profile, which keeps
        // CONNECT from targeting a profile that was removed on another screen.
        val book = profiles.edit { it.ensureDefault() }
        val rows = buildList {
            book.all.forEach { profile ->
                add(
                    ProfileAdapter.Row.Item(
                        profile = profile,
                        isActive = profile.id == book.activeId(),
                        canDelete = book.count > 1,
                    ),
                )
            }
            add(ProfileAdapter.Row.Add)
        }
        adapter.submitList(rows)
        binding.emptyState.visibility =
            if (book.all.none { it.hasConfig }) android.view.View.VISIBLE else android.view.View.GONE
        renderActiveLicenseHint()
    }

    private fun selectProfile(profile: Profile) {
        val wasConnected = connected
        if (wasConnected) stopTunnel()

        profiles.edit { it.setActive(profile.id) }
        refreshProfiles()
        toast("Profil: ${profile.displayName}")

        if (wasConnected) {
            binding.root.postDelayed({
                if (!connected && profile.hasConfig) requestConnect()
            }, RECONNECT_AFTER_SWITCH_MS)
        }
    }

    private fun openEditor(profileId: String?) {
        val intent = Intent(this, ProfileEditActivity::class.java)
        if (profileId != null) intent.putExtra(ProfileEditActivity.EXTRA_PROFILE_ID, profileId)
        editProfileLauncher.launch(intent)
    }

    private fun confirmDeleteProfile(profile: Profile) {
        val book = profiles.loadBook()
        if (book.count <= 1) { toast("Minimal harus ada 1 profil"); return }
        if (connected) { toast("Putuskan VPN dulu sebelum menghapus profil"); return }

        val message = buildString {
            append("Hapus profil '${profile.displayName}'?")
            if (profile.hasConfig && book.countWithConfig == 1) {
                append("\n\nIni satu-satunya profil yang punya config. Setelah dihapus kamu harus import lagi sebelum bisa connect.")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Hapus profil")
            .setMessage(message)
            .setPositiveButton("Hapus") { _, _ ->
                profiles.edit { it.remove(profile.id) }
                refreshProfiles()
                toast("Profil dihapus")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun renderActiveLicenseHint() {
        val license = license
        if (license != null) renderLicenseBanner(license)
        else binding.lockBanner.visibility = android.view.View.GONE
    }

    // ------------------------------------------------------------------ connect

    private fun requestConnect() {
        if (connected) return
        val uri = runCatching { activeUri() }.getOrElse {
            toast(it.message ?: "Config tidak valid")
            return
        }
        if (uri.isBlank()) { toast("Belum ada config. Import link vless:// dulu."); return }
        runCatching { VlessParser.parse(uri) }.onFailure {
            toast(TunnelDiagnostics.fromConfigError(it.message)); return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) startTunnel() else vpnPermissionLauncher.launch(prepareIntent)
    }

    private fun startTunnel() {
        val uri = runCatching { activeUri() }.getOrElse {
            toast(it.message ?: "Config tidak valid")
            return
        }
        if (uri.isBlank()) { toast("Belum ada config"); return }
        PanVpnService.start(this, uri)
    }

    private fun stopTunnel() {
        PanVpnService.stop(this)
    }

    /** URI for the active profile, or the locked license payload when one is installed. */
    private fun activeUri(): String {
        license?.let { license ->
            if (license.isExpired) throw IllegalStateException("Lisensi kedaluwarsa")
            return license.vless
        }
        return profiles.activeUri()
    }

    // ------------------------------------------------------------------ license

    private fun loadLicense() {
        val stored = profiles.storedLicense()
        if (stored == null) {
            license = null
            binding.lockBanner.visibility = android.view.View.GONE
            return
        }
        if (stored.isExpired) {
            profiles.clearLicense()
            license = null
            toast("Lisensi kedaluwarsa")
            return
        }
        license = stored
        renderLicenseBanner(stored)
    }

    private fun showExportLicenseDialog() {
        val uri = activeUri()
        runCatching { VlessParser.parse(uri) }.onFailure {
            toast(TunnelDiagnostics.fromConfigError(it.message)); return
        }

        val form = DialogExportLicenseBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Export locked config")
            .setView(form.root)
            .create()

        var expiryAt = 0L
        form.licExpiry.setOnClickListener {
            val calendar = Calendar.getInstance()
            android.app.DatePickerDialog(
                this,
                { _, year, month, day ->
                    val picked = Calendar.getInstance().apply {
                        set(year, month, day, 23, 59, 59)
                    }
                    expiryAt = picked.timeInMillis
                    form.licExpiry.text = String.format(Locale.US, "%02d/%02d/%04d", day, month + 1, year)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
            ).show()
        }
        form.licExpiry.setOnLongClickListener {
            expiryAt = 0L
            form.licExpiry.text = "dd/mm/yyyy (kosong = selamanya)"
            toast("Tanggal dikosongkan")
            true
        }

        form.licCancel.setOnClickListener { dialog.dismiss() }
        form.licCreate.setOnClickListener {
            val customerHwid = form.licHwid.text.toString().trim().uppercase(Locale.US)
            if (customerHwid.length != 24) { toast("HWID harus 24 karakter"); return@setOnClickListener }
            val payload = runCatching {
                LicenseCodec.encode(
                    License(
                        vless = uri,
                        name = form.licName.text.toString().trim(),
                        hwid = customerHwid,
                        lock = form.licLock.isChecked,
                        expiry = expiryAt,
                    ),
                )
            }.getOrElse { toast("Export gagal: ${it.javaClass.simpleName}"); return@setOnClickListener }

            pendingLicensePayload = payload
            exportLicenseLauncher.launch("pantunnel-locked.pantunnel")
            dialog.dismiss()
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ import / export

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        // A vless:// link arrives as the URI itself; a .pantunnel file arrives as a content URI.
        if (uri.scheme.equals("vless", ignoreCase = true)) {
            uri.toString().takeIf { it.isNotBlank() }?.let { importText(it) }
        } else {
            readImport(uri)
        }
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) { toast("Clipboard kosong"); return }
        importText(text.trim())
    }

    private fun importText(raw: String) {
        if (LicenseCodec.isEncoded(raw)) { installLicense(raw); return }

        val config = runCatching { VlessParser.parse(raw) }.getOrElse {
            toast(TunnelDiagnostics.fromConfigError(it.message)); return
        }
        // Importing your own link releases any locked license on this device.
        if (license != null) {
            profiles.clearLicense()
            license = null
            renderActiveLicenseHint()
        }
        openEditorWithConfig(config)
    }

    /**
     * A clipboard import always creates a new profile. Passing EXTRA_RAW_URI without a profile id
     * is what makes the editor treat it as "new" instead of overwriting the active profile.
     */
    private fun openEditorWithConfig(config: VlessConfig) {
        val intent = Intent(this, ProfileEditActivity::class.java)
            .putExtra(ProfileEditActivity.EXTRA_RAW_URI, VlessParser.export(config))
        editProfileLauncher.launch(intent)
    }

    private fun installLicense(payload: String) {
        val incoming = profiles.installLicense(payload)
        if (incoming == null) { toast("File bukan untuk HWID device ini"); return }
        if (incoming.isExpired) {
            profiles.clearLicense()
            toast("Lisensi kedaluwarsa")
            return
        }
        license = incoming
        renderLicenseBanner(incoming)
        toast("Lisensi terpasang" + if (incoming.lock) " (config terkunci)" else "")
    }

    /**
     * Plain `.pantunnel` export: the raw `vless://` link, nothing encrypted.
     * Works on any device — unlike "Export locked config" which is bound to one HWID.
     */
    private fun exportPlainFile() {
        val uri = runCatching { activeUri() }.getOrElse {
            toast(it.message ?: "Config tidak valid")
            return
        }
        if (uri.isBlank()) { toast("Belum ada config untuk diexport"); return }
        runCatching { VlessParser.parse(uri) }.onFailure {
            toast(TunnelDiagnostics.fromConfigError(it.message))
            return
        }
        pendingExportPayload = uri
        val name = profiles.activeProfile()?.displayName
            ?.replace(Regex("[^A-Za-z0-9._-]"), "-")
            ?.takeIf { it.isNotBlank() }
            ?: "pantunnel"
        exportFileLauncher.launch("$name.pantunnel")
    }

    /** Rebuildable on demand so an activity recreation cannot produce an empty file. */
    private fun buildDiagnosticsReport(): String? {
        val snapshot = statusStore.snapshot()
        val profile = profiles.activeProfile()
        val summary = profile?.let {
            val config = runCatching { VlessParser.parse(it.toUri()) }.getOrNull()
            if (config == null) {
                "${it.displayName} (belum ada config)"
            } else {
                "${it.displayName} • ${config.address}:${config.port} • " +
                    "uuid ${DiagnosticsReport.redactUuid(config.uuid)} • path ${config.path} • sni ${config.sni}"
            }
        } ?: "(tidak ada profil aktif)"

        return runCatching { DiagnosticsReport.build(this, snapshot, summary) }
            .onFailure { toast("Gagal membuat laporan: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private fun openImportFile() {
        runCatching { importFileLauncher.launch(arrayOf(JVS_MIME)) }
            .onFailure { importFileLauncher.launch(arrayOf("text/plain", "*/*")) }
    }

    /**
     * Reads the whole file, not just line 1: a shared note may carry blank lines or a
     * multi-line paste before the link, and a locked payload is a single long line anyway.
     */
    private fun readImport(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.trim()

        if (text.isNullOrBlank()) { toast("File kosong"); return }

        val candidate = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("vless://", true) || LicenseCodec.isEncoded(it) }
            ?: text

        importText(candidate)
    }

    /**
     * Writes [text] to a SAF uri and reports failure honestly.
     *
     * A null stream from openOutputStream() used to look like success (`?.use{}` just returns
     * null without throwing), which produced a 0-byte file with an "exported" toast. Now a
     * missing stream or a short write is an explicit failure.
     */
    private fun writeText(uri: Uri, text: String, successMessage: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val written = runCatching {
            val stream = contentResolver.openOutputStream(uri, "wt")
                ?: error("Tidak bisa membuka file tujuan")
            stream.use { it.write(bytes); it.flush() }
            bytes.size
        }.getOrElse {
            toast("Export gagal: ${it.message ?: it.javaClass.simpleName}")
            return
        }
        toast(if (written > 0) successMessage else "Export gagal: file kosong")
    }

    private fun copyToClipboard(text: String, message: String) {
        if (text.isBlank()) { toast("Tidak ada config untuk dicopy"); return }
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("PanTunnel", text))
        toast(message)
    }

    // ------------------------------------------------------------------ dialogs

    private fun showSettingsDialog() {
        val form = DialogSettingsBinding.inflate(layoutInflater)
        val current = settingsRepo.load()

        form.setHwid.text = hwid
        form.setPing.isChecked = current.httpPingEnabled
        form.setPingInterval.setText(current.httpPingIntervalSeconds.toString())
        form.setPingUrl.setText(current.httpPingUrl)
        form.setTraffic.isChecked = current.showTraffic
        form.setAutoReconnect.isChecked = current.autoReconnect
        applyPingFormState(form, current.httpPingEnabled)
        form.setPing.setOnCheckedChangeListener { _, checked -> applyPingFormState(form, checked) }

        val dialog = AlertDialog.Builder(this).setView(form.root).create()

        form.setCopyHwid.setOnClickListener { copyToClipboard(hwid, "HWID dicopy") }
        form.setCancel.setOnClickListener { dialog.dismiss() }
        form.setDone.setOnClickListener {
            val seconds = form.setPingInterval.text.toString().trim().toIntOrNull() ?: 3
            val pingUrl = form.setPingUrl.text.toString().trim()
                .ifBlank { TunnelSettings.DEFAULT_PING_URL }
            val updated = TunnelSettings(
                httpPingEnabled = form.setPing.isChecked,
                httpPingIntervalSeconds = seconds.coerceAtLeast(1),
                httpPingUrl = pingUrl,
                showTraffic = form.setTraffic.isChecked,
                autoReconnect = form.setAutoReconnect.isChecked,
            )
            settingsRepo.save(updated)
            settings = updated
            applyTrafficVisibility(updated.showTraffic)
            dialog.dismiss()
            toast("Pengaturan disimpan")
        }
        dialog.show()
    }

    private fun applyPingFormState(form: DialogSettingsBinding, enabled: Boolean) {
        form.setPingInterval.isEnabled = enabled
        form.setPingUrl.isEnabled = enabled
        form.setPingInterval.alpha = if (enabled) 1f else 0.4f
        form.setPingUrl.alpha = if (enabled) 1f else 0.4f
    }

    private fun showAboutDialog() {
        val form = DialogAboutBinding.inflate(layoutInflater)
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        form.aboutVersion.text = "v$version"
        val dialog = AlertDialog.Builder(this).setView(form.root).create()
        form.aboutTelegram.setOnClickListener { openUrl("https://t.me/jhopan_05") }
        form.aboutWebsite.setOnClickListener { openUrl("https://jhopanstore.my.id") }
        dialog.show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("Tidak ada browser") }
    }

    private fun confirmClearConfig() {
        if (connected) { toast("Putuskan VPN dulu sebelum clear config"); return }
        AlertDialog.Builder(this)
            .setTitle("Clear config")
            .setMessage("Hapus semua profil, config, dan lisensi?")
            .setPositiveButton("Hapus") { _, _ ->
                profiles.clearConfig()
                CacheJanitor.clear(filesDir)
                license = null
                binding.lockBanner.visibility = android.view.View.GONE
                refreshProfiles()
                toast("Config dibersihkan")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ------------------------------------------------------------------ battery guard

    private fun batteryGuard() {
        if (batteryGuardShown) return
        batteryGuardShown = true
        val powerManager = getSystemService(PowerManager::class.java)
        val ignoring = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        val acknowledged = settingsRepo.flag(SettingsRepository.KEY_BATTERY_ACK)
        if (ignoring || acknowledged) return

        AlertDialog.Builder(this)
            .setTitle("Mode 24/7")
            .setMessage(
                "Agar VPN tetap hidup saat layar mati, atur penghemat daya PanTunnel ke " +
                    "\"Tidak dibatasi\".",
            )
            .setPositiveButton("Buka penghemat daya") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }.onFailure {
                    runCatching {
                        startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }
            .setNegativeButton("Nanti") { _, _ ->
                // Remember the dismissal, otherwise this prompt returns on every launch.
                settingsRepo.setFlag(SettingsRepository.KEY_BATTERY_ACK, true)
            }
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val JVS_MIME = "application/x-pantunnel"
        /** Keeps the copied report well under clipboard limits while staying useful. */
        const val MAX_CLIPBOARD_CHARS = 4_000
        const val TRAFFIC_TICK_MS = 2_000L
        const val RECONNECT_AFTER_SWITCH_MS = 1_500L
    }

    private var batteryGuardShown = false
    private var killedHintShown = false
    private var trafficJob: Job? = null
}

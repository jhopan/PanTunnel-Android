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
import id.jhopanstore.pantunnel.core.License
import id.jhopanstore.pantunnel.core.LicenseCodec
import id.jhopanstore.pantunnel.core.Profile
import id.jhopanstore.pantunnel.core.ProfileBook
import id.jhopanstore.pantunnel.core.TunnelDiagnostics
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
    private lateinit var adapter: ProfileAdapter

    private var settings: TunnelSettings = TunnelSettings()
    private var license: License? = null
    private var hwid: String = ""

    private var connected = false
    private var meterBaselineSeeded = false
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastSampleAt = 0L

    private var pendingLicensePayload: String? = null

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
            if (uri != null && payload != null) writeText(uri, payload, "Locked config diexport")
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
        handleIncomingIntent(intent)
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
        setIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_import_clipboard -> { importFromClipboard(); true }
        R.id.action_import_file -> { openImportFile(); true }
        R.id.action_export_clipboard -> { copyToClipboard(activeUri(), "Link dicopy"); true }
        R.id.action_export_license -> { showExportLicenseDialog(); true }
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
        val state = if (snapshot.isAlive()) snapshot.state else {
            if (snapshot.state == TunnelState.Connected) TunnelState.Failed else snapshot.state
        }
        val detail = if (snapshot.isAlive()) snapshot.detail
        else if (snapshot.state == TunnelState.Connected) "VPN berhenti — sistem menutup service." else snapshot.detail
        renderState(state, detail, snapshot.logLines)
        license?.let { renderLicenseBanner(it) }
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
        binding.connect.isEnabled = !state.isBusy
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
            meterBaselineSeeded = false
            binding.traffic.text = getString(R.string.traffic_placeholder)
        }
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
        val snapshot = statusStore.snapshot()
        val uid = Process.myUid()
        val rx = android.net.TrafficStats.getUidRxBytes(uid)
        val tx = android.net.TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) return

        val now = System.currentTimeMillis()
        val baseRx = snapshot.meterBaseRx
        val baseTx = snapshot.meterBaseTx
        val sessionRx = if (baseRx >= 0 && rx >= baseRx) rx - baseRx else snapshot.sessionRx
        val sessionTx = if (baseTx >= 0 && tx >= baseTx) tx - baseTx else snapshot.sessionTx

        var downRate = 0L
        var upRate = 0L
        if (!meterBaselineSeeded) {
            lastRx = rx; lastTx = tx; lastSampleAt = now
            meterBaselineSeeded = true
        } else {
            val elapsed = (now - lastSampleAt).coerceAtLeast(1L)
            downRate = ((rx - lastRx).coerceAtLeast(0L)) * 1000 / elapsed
            upRate = ((tx - lastTx).coerceAtLeast(0L)) * 1000 / elapsed
            lastRx = rx; lastTx = tx; lastSampleAt = now
        }

        binding.traffic.text = "↓ ${formatBytes(sessionRx)} (${formatBytes(downRate)}/s)" +
            "   ↑ ${formatBytes(sessionTx)} (${formatBytes(upRate)}/s)"
    }

    private fun formatBytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", value / (1024.0 * 1024.0))
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

    private fun openImportFile() {
        runCatching { importFileLauncher.launch(arrayOf(JVS_MIME)) }
            .onFailure { importFileLauncher.launch(arrayOf("text/plain", "*/*")) }
    }

    private fun readImport(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readLine() }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { importText(it.trim()) }
            ?: toast("Import gagal")
    }

    private fun writeText(uri: Uri, text: String, successMessage: String) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }.onSuccess { toast(successMessage) }.onFailure { toast("Export gagal") }
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
        form.setDebug.isChecked = current.debugMode
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
                debugMode = form.setDebug.isChecked,
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
            .setNegativeButton("Nanti", null)
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
        const val TRAFFIC_TICK_MS = 2_000L
        const val RECONNECT_AFTER_SWITCH_MS = 1_500L
    }

    private var batteryGuardShown = false
    private var trafficJob: Job? = null
}

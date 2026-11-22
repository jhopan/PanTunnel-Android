package id.jhopanstore.pantunnel

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import id.jhopanstore.pantunnel.core.SplitTunnel
import id.jhopanstore.pantunnel.data.Settings
import id.jhopanstore.pantunnel.data.SettingsRepository
import id.jhopanstore.pantunnel.databinding.ActivitySplitTunnelBinding
import id.jhopanstore.pantunnel.databinding.ItemAppPickBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

/**
 * Picks which apps are tunnelled.
 *
 * The heavy part (enumerating installed packages + loading icons) runs off the main thread, and
 * icons are loaded lazily per bound row so a 300-app device does not jank.
 */
class SplitTunnelActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplitTunnelBinding
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var adapter: AppPickerAdapter

    private var allApps: List<AppEntry> = emptyList()
    private var selected: MutableSet<String> = mutableSetOf()
    private var allowMode = true
    private var enabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplitTunnelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.splitToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.splitToolbar.setNavigationOnClickListener { finish() }

        settingsRepo = SettingsRepository(this)
        val current = settingsRepo.load().splitTunnel
        enabled = current.enabled
        allowMode = current.allowMode
        selected = current.packages.toMutableSet()

        adapter = AppPickerAdapter(
            selection = { selected },
            onToggle = { entry ->
                if (!selected.add(entry.packageName)) selected.remove(entry.packageName)
                renderSummary()
                val index = allApps.indexOf(entry)
                if (index >= 0) adapter.notifyItemChanged(index)
            },
        )
        binding.splitList.layoutManager = LinearLayoutManager(this)
        binding.splitList.adapter = adapter

        binding.splitEnabled.isChecked = enabled
        binding.splitEnabled.setOnCheckedChangeListener { _, checked ->
            enabled = checked
            applyEnabledState()
            renderSummary()
        }
        if (allowMode) binding.splitAllow.isChecked = true else binding.splitBlock.isChecked = true
        binding.splitMode.setOnCheckedChangeListener { _, id ->
            allowMode = id == R.id.splitAllow
            renderSummary()
        }

        binding.splitSearch.doAfterTextChanged { query ->
            adapter.submitList(filter(query?.toString().orEmpty()))
        }

        binding.splitSave.setOnClickListener { save() }

        applyEnabledState()
        renderSummary()
        loadApps()
    }

    private fun applyEnabledState() {
        val on = enabled
        binding.splitMode.alpha = if (on) 1f else 0.4f
        binding.splitAllow.isEnabled = on
        binding.splitBlock.isEnabled = on
        binding.splitSearch.isEnabled = on
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { queryApps() }
            allApps = apps
            adapter.submitList(filter(binding.splitSearch.text?.toString().orEmpty()))
            renderSummary()
            applyEnabledState()
            warnIfSuspiciouslyFew(apps)
        }
    }

    /**
     * Some OEM builds still hide third-party apps even with QUERY_ALL_PACKAGES. Showing only
     * system apps is not a valid state for a split-tunnel picker, so say so instead of letting
     * the user think their apps vanished.
     */
    private fun warnIfSuspiciouslyFew(apps: List<AppEntry>) {
        if (apps.isEmpty()) {
            Toast.makeText(this, "Tidak ada aplikasi terbaca", Toast.LENGTH_SHORT).show()
            return
        }
        val userApps = apps.count { !it.isSystem }
        if (userApps == 0) {
            binding.splitSummary.text =
                "Hanya app sistem yang terbaca — HP ini membatasi daftar aplikasi."
        }
    }

    private fun queryApps(): List<AppEntry> {
        val pm = packageManager
        val self = packageName
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != self }
            .filter { it.packageName !in SplitTunnel.ALWAYS_EXCLUDED }
            .map { info ->
                AppEntry(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedWith(compareByDescending<AppEntry> { !it.isSystem }.thenBy { it.label.lowercase() })
            .toList()
    }

    private fun filter(query: String): List<AppEntry> {
        if (query.isBlank()) return allApps
        val q = query.trim().lowercase()
        return allApps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    private fun renderSummary() {
        val count = selected.size
        binding.splitSummary.text = when {
            !enabled -> "Split tunnel nonaktif — semua app lewat VPN."
            count == 0 -> "Belum ada app dipilih."
            allowMode -> "$count app lewat VPN, sisanya langsung (tanpa VPN)."
            else -> "$count app dilewatkan — langsung tanpa VPN, sisanya lewat VPN."
        }
    }

    private fun save() {
        val split = SplitTunnel(enabled = enabled, allowMode = allowMode, packages = selected)
            .sanitized(packageName)
        val current = settingsRepo.load()
        settingsRepo.save(current.copy(splitTunnel = split))

        if (split.isActive) {
            Toast.makeText(
                this,
                "Disimpan — sambungkan ulang VPN agar berlaku",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            Toast.makeText(this, "Disimpan", Toast.LENGTH_SHORT).show()
        }
        setResult(RESULT_OK)
        finish()
    }

    data class AppEntry(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
    )
}

/** Simple picker list; icons are resolved lazily so scrolling stays smooth. */
private class AppPickerAdapter(
    private val selection: () -> Set<String>,
    private val onToggle: (SplitTunnelActivity.AppEntry) -> Unit,
) : ListAdapter<SplitTunnelActivity.AppEntry, AppPickerAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAppPickBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val b: ItemAppPickBinding) : RecyclerView.ViewHolder(b.root) {
        private var icon: Drawable? = null
        private var iconPackage: String? = null

        fun bind(entry: SplitTunnelActivity.AppEntry) {
            b.appLabel.text = entry.label
            b.appPackage.text = entry.packageName

            if (iconPackage != entry.packageName) {
                iconPackage = entry.packageName
                icon = runCatching {
                    b.root.context.packageManager.getApplicationIcon(entry.packageName)
                }.getOrNull()
            }
            if (icon != null) b.appIcon.setImageDrawable(icon) else b.appIcon.setImageDrawable(null)

            b.appCheck.isChecked = entry.packageName in selection()
            b.root.setOnClickListener { onToggle(entry) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<SplitTunnelActivity.AppEntry>() {
            override fun areItemsTheSame(
                oldItem: SplitTunnelActivity.AppEntry,
                newItem: SplitTunnelActivity.AppEntry,
            ): Boolean = oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(
                oldItem: SplitTunnelActivity.AppEntry,
                newItem: SplitTunnelActivity.AppEntry,
            ): Boolean = oldItem == newItem
        }
    }
}

package id.jhopanstore.pantunnel

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import id.jhopanstore.pantunnel.core.Profile
import id.jhopanstore.pantunnel.core.ProfileBook
import id.jhopanstore.pantunnel.core.VlessConfig
import id.jhopanstore.pantunnel.core.VlessParser
import id.jhopanstore.pantunnel.data.ProfileRepository
import id.jhopanstore.pantunnel.databinding.ActivityProfileEditBinding

/**
 * Full-screen editor for one profile: name + the five VLESS fields.
 * Also the landing page for a clipboard import (prefilled from the URI).
 */
class ProfileEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileEditBinding
    private lateinit var profiles: ProfileRepository
    private var profileId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.editToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        profiles = ProfileRepository(this)
        profileId = intent.getStringExtra(EXTRA_PROFILE_ID)

        loadExisting()
        prefillFromUri(intent.getStringExtra(EXTRA_RAW_URI))

        binding.editSave.setOnClickListener { save() }
        binding.editToolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadExisting() {
        val existing = profileId?.let { profiles.loadBook().get(it) }
        if (existing == null) {
            supportActionBar?.title = "Profil baru"
            binding.editPath.setText("/")
            return
        }
        supportActionBar?.title = if (existing.name.isBlank()) "Edit profil" else "Edit: ${existing.name}"
        binding.editName.setText(existing.name)
        binding.editAddress.setText(existing.address)
        binding.editUuid.setText(existing.uuid)
        binding.editPath.setText(existing.path.ifBlank { "/" })
        binding.editSni.setText(existing.sni)
        binding.editHost.setText(existing.host)
    }

    private fun prefillFromUri(rawUri: String?) {
        if (rawUri.isNullOrBlank()) return
        runCatching { VlessParser.parse(rawUri) }
            .onSuccess { config ->
                binding.editAddress.setText(config.hostPort)
                binding.editUuid.setText(config.uuid)
                binding.editPath.setText(config.path)
                binding.editSni.setText(config.sni)
                binding.editHost.setText(config.host)
                if (binding.editName.text.isBlank()) {
                    binding.editName.setText(config.address)
                }
            }
            .onFailure { toast(it.message ?: "Config tidak valid") }
    }

    private fun save() {
        val name = binding.editName.text.toString().trim()
        val address = binding.editAddress.text.toString().trim()
        val uuid = binding.editUuid.text.toString().trim()
        val path = binding.editPath.text.toString().trim().ifBlank { "/" }
        val sni = binding.editSni.text.toString().trim()
        val host = binding.editHost.text.toString().trim()

        if (address.isBlank() || uuid.isBlank()) {
            toast("Address dan UUID wajib diisi")
            return
        }

        val (server, port) = VlessParser.splitHostPort(address)
        val serverName = sni.ifBlank { server }
        val config = VlessConfig(
            address = server,
            port = port,
            uuid = uuid,
            path = path,
            sni = serverName,
            host = host.ifBlank { serverName },
            allowInsecure = true,
        )

        // Validate exactly what the tunnel will dial before touching storage.
        runCatching { VlessParser.parse(VlessParser.export(config)) }
            .onFailure { toast(it.message ?: "Config tidak valid"); return }

        val book = profiles.loadBook()
        val targetId = profileId ?: Profile.newId()
        val previous = book.get(targetId)
        val finalName = name.ifBlank {
            previous?.name?.takeIf { it.isNotBlank() } ?: "Profile ${book.count + 1}"
        }

        val stored = profiles.edit { editing ->
            val saved = editing.put(Profile.fromConfig(targetId, finalName, config))
            if (saved) editing.setActive(targetId)
            saved
        }
        if (stored.get(targetId) == null) {
            toast("Maksimal ${ProfileBook.MAX_PROFILES} profil")
            return
        }
        profiles.edit { it.setActive(targetId) }
        setResult(RESULT_OK)
        finish()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_RAW_URI = "raw_uri"
    }
}

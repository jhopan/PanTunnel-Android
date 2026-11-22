package id.jhopanstore.pantunnel.core

import android.content.Context
import android.os.Build
import id.jhopanstore.pantunnel.service.TunnelStatusStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a plain-text diagnostic report the user can share when a connection fails.
 *
 * Deliberately **redacts secrets**: the UUID is truncated and the full `vless://` link is never
 * written, so a report can be pasted into a chat without leaking the customer's account.
 */
object DiagnosticsReport {

    fun build(context: Context, status: TunnelStatusStore.Snapshot, profileSummary: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("=== PanTunnel diagnostic report ===")
            appendLine("waktu      : $timestamp")
            appendLine("app        : ${versionName(context)}")
            appendLine("package    : ${context.packageName}")
            appendLine()
            appendLine("--- perangkat ---")
            appendLine("model      : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android    : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("abi        : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("vendor os  : ${vendorOs()}")
            appendLine()
            appendLine("--- status tunnel ---")
            appendLine("state      : ${status.state.name}")
            appendLine("detail     : ${status.detail.ifBlank { "-" }}")
            appendLine("profil     : $profileSummary")
            appendLine("last seen  : ${formatAge(status.lastSeen)}")
            appendLine("sesi rx/tx : ${status.sessionRx} / ${status.sessionTx} bytes")
            appendLine()
            appendLine("--- log ---")
            if (status.logLines.isEmpty()) {
                appendLine("(kosong — belum ada aktivitas koneksi pada sesi ini)")
            } else {
                status.logLines.forEach { appendLine(it) }
            }
        }
    }

    /** Truncates a UUID so a shared report cannot be used to hijack the account. */
    fun redactUuid(uuid: String): String =
        if (uuid.length <= 8) "****" else "${uuid.take(8)}…${uuid.takeLast(4)}"

    private fun formatAge(lastSeen: Long): String {
        if (lastSeen <= 0) return "belum pernah"
        val ageSeconds = (System.currentTimeMillis() - lastSeen) / 1000
        return "$ageSeconds detik lalu"
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun vendorOs(): String = listOf(
        "ro.miui.ui.version.name",
        "ro.mi.os.version.name",
        "ro.build.version.opporom",
        "ro.vivo.os.version",
        "ro.build.version.emui",
    ).mapNotNull { key ->
        runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }.firstOrNull() ?: "stock"
}

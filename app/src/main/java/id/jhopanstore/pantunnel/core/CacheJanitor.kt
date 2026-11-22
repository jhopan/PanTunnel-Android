package id.jhopanstore.pantunnel.core

import java.io.File

/**
 * sing-box writes `cache.db` (DNS cache + rule-set metadata) into the app files dir and never
 * trims it. On a long-lived 24/7 install that file only grows and is invisible to the user, so
 * it is pruned here: wiped on "clear config" and reset whenever it exceeds [MAX_BYTES].
 */
object CacheJanitor {

    const val CACHE_FILE = "cache.db"
    const val MAX_BYTES = 5L * 1024 * 1024 // 5 MB

    /** @return true when a file was actually deleted. */
    fun clear(dir: File): Boolean {
        val target = File(dir, CACHE_FILE)
        return runCatching { target.exists() && target.delete() }.getOrDefault(false)
    }

    /**
     * Deletes the cache when it grew past [MAX_BYTES]. Safe to call on every service start.
     * @return size in bytes before deletion, or 0 when nothing was removed.
     */
    fun pruneIfOversized(dir: File): Long {
        val target = File(dir, CACHE_FILE)
        val size = runCatching { if (target.exists()) target.length() else 0L }.getOrDefault(0L)
        if (size <= MAX_BYTES) return 0L
        val deleted = runCatching { target.delete() }.getOrDefault(false)
        return if (deleted) size else 0L
    }

    /** Current cache size, for diagnostics. */
    fun size(dir: File): Long =
        runCatching { File(dir, CACHE_FILE).let { if (it.exists()) it.length() else 0L } }
            .getOrDefault(0L)
}

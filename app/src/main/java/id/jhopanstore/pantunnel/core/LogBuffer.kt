package id.jhopanstore.pantunnel.core

/**
 * Bounded, self-expiring log buffer.
 *
 * Two problems it solves:
 *  - The status box used to keep the last N lines for the whole session, so an error from twenty
 *    minutes ago sat next to the current keep-alive line and looked like a live fault. Entries now
 *    age out after [ttlMs], so the box only ever shows what is actually happening now.
 *  - Each buffer has a hard cap as a second guard, so memory cannot grow if the clock jumps.
 */
class LogBuffer(private val max: Int) {

    private val entries = ArrayDeque<Entry>()

    private data class Entry(val text: String, val at: Long)

    @Synchronized
    fun add(text: String, at: Long) {
        if (text.isBlank()) return
        entries.addLast(Entry(text, at))
        while (entries.size > max) entries.removeFirst()
    }

    /** Drops anything older than [ttlMs] and returns what is left, oldest first. */
    @Synchronized
    fun recent(now: Long, ttlMs: Long): List<String> {
        expire(now, ttlMs)
        return entries.map { it.text }
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size

    private fun expire(now: Long, ttlMs: Long) {
        while (entries.isNotEmpty() && now - entries.first().at > ttlMs) {
            entries.removeFirst()
        }
    }
}
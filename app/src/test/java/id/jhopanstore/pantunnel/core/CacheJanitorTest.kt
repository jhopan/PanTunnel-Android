package id.jhopanstore.pantunnel.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** sing-box never trims cache.db; the janitor is what keeps a 24/7 install from bloating. */
class CacheJanitorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `clear deletes an existing cache file`() {
        val dir = temp.newFolder()
        File(dir, CacheJanitor.CACHE_FILE).writeBytes(ByteArray(128))
        assertTrue(CacheJanitor.clear(dir))
        assertFalse(File(dir, CacheJanitor.CACHE_FILE).exists())
    }

    @Test
    fun `clear on missing file is a no-op`() {
        val dir = temp.newFolder()
        assertFalse(CacheJanitor.clear(dir))
    }

    @Test
    fun `prune keeps a small cache`() {
        val dir = temp.newFolder()
        File(dir, CacheJanitor.CACHE_FILE).writeBytes(ByteArray(1024))
        assertEquals(0L, CacheJanitor.pruneIfOversized(dir))
        assertTrue(File(dir, CacheJanitor.CACHE_FILE).exists())
    }

    @Test
    fun `prune deletes a cache past the limit and reports old size`() {
        val dir = temp.newFolder()
        val big = (CacheJanitor.MAX_BYTES + 1024).toInt()
        File(dir, CacheJanitor.CACHE_FILE).writeBytes(ByteArray(big))
        val reported = CacheJanitor.pruneIfOversized(dir)
        assertEquals(big.toLong(), reported)
        assertFalse(File(dir, CacheJanitor.CACHE_FILE).exists())
    }

    @Test
    fun `size reports zero when absent`() {
        assertEquals(0L, CacheJanitor.size(temp.newFolder()))
    }

    @Test
    fun `size reports the real length`() {
        val dir = temp.newFolder()
        File(dir, CacheJanitor.CACHE_FILE).writeBytes(ByteArray(4096))
        assertEquals(4096L, CacheJanitor.size(dir))
    }
}

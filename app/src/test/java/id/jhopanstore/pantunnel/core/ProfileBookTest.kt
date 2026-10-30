package id.jhopanstore.pantunnel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBookTest {

    private fun profile(id: String, withConfig: Boolean = true) = Profile(
        id = id,
        name = "p-$id",
        address = if (withConfig) "example.com:443" else "",
        uuid = if (withConfig) "11111111-2222-3333-4444-555555555555" else "",
    )

    @Test
    fun `ensureDefault creates one slot and makes it active`() {
        val book = ProfileBook()
        val created = book.ensureDefault()
        assertEquals(1, book.count)
        assertEquals(created.id, book.activeId())
    }

    @Test
    fun `put replaces by id and enforces limit`() {
        val book = ProfileBook()
        book.put(profile("a"))
        book.put(profile("a", withConfig = false))
        assertEquals(1, book.count)
        assertFalse(book.get("a")!!.hasConfig)

        repeat(ProfileBook.MAX_PROFILES - 1) { book.put(profile("extra-$it")) }
        assertEquals(ProfileBook.MAX_PROFILES, book.count)
        assertFalse(book.put(profile("overflow")))
    }

    @Test
    fun `remove picks the nearest surviving slot that has a config`() {
        val book = ProfileBook()
        book.put(profile("a"))
        book.put(profile("b", withConfig = false))
        book.put(profile("c"))
        book.setActive("b")

        book.remove("b")
        // "b" sat at index 1; in the trimmed list index 1 is now "c", which holds a config.
        assertEquals("c", book.activeId())
    }

    @Test
    fun `remove prefers a neighbour with a config over an empty one`() {
        val book = ProfileBook()
        book.put(profile("empty", withConfig = false))
        book.put(profile("real"))
        book.setActive("empty")

        book.remove("empty")
        assertEquals("real", book.activeId())
    }

    @Test
    fun `remove last active falls back to remaining`() {
        val book = ProfileBook()
        book.put(profile("only"))
        book.setActive("only")
        book.remove("only")
        assertNull(book.activeId())
        assertEquals(0, book.count)
    }

    @Test
    fun `active falls back to first profile with config`() {
        val book = ProfileBook()
        book.put(profile("empty", withConfig = false))
        book.put(profile("real"))
        book.setActive("missing")
        assertEquals("real", book.active()?.id)
    }

    @Test
    fun `toUri round trips through the parser`() {
        val book = ProfileBook()
        book.put(profile("a"))
        val uri = book.get("a")!!.toUri()
        val parsed = VlessParser.parse(uri)
        assertEquals("example.com", parsed.address)
        assertEquals(443, parsed.port)
        assertEquals("11111111-2222-3333-4444-555555555555", parsed.uuid)
        assertEquals("/", parsed.path)
    }

    @Test
    fun `blank profile has no uri`() {
        assertEquals("", profile("x", withConfig = false).toUri())
        assertNull(ProfileBook().get("nope"))
    }

    @Test
    fun `hwid is 24 upper case hex chars and stable`() {
        val first = Hwid.fromInstallId("install-1")
        assertEquals(24, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'A'..'F' })
        assertEquals(first, Hwid.fromInstallId("install-1"))
        assertNotNull(Hwid.randomInstallId())
    }
}

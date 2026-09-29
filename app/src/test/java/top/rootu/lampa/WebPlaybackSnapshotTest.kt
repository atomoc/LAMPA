package top.rootu.lampa

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class WebPlaybackSnapshotTest {
    private val sample = """{"key":"42","title":"Episode","subtitle":"Series",
        "artwork":"","tmdbPath":"/poster.jpg","state":3,"position":12.5,
        "duration":90,"rate":1,"seekable":true}"""

    @Test fun parsesBrowserResult() {
        val value = WebPlaybackSnapshot.parse(Gson().toJson(sample))!!
        assertEquals("Episode", value.title)
        assertEquals("Series", value.subtitle)
        assertEquals("/poster.jpg", value.tmdbPath)
        assertEquals(12500L, value.position)
        assertEquals(90000L, value.duration)
        assertTrue(value.seekable)
    }

    @Test fun acceptsUnquotedSnapshot() {
        assertEquals(3, WebPlaybackSnapshot.parse(sample)!!.state)
    }

    @Test fun nullAndMalformedDataDoNotCreatePlayback() {
        for (raw in listOf("null", "\"null\"", "[]", "{}", "bad", "{\"state\":99}"))
            assertNull(raw, WebPlaybackSnapshot.parse(raw))
    }

    @Test fun boundsPositionsAndRates() {
        val value = WebPlaybackSnapshot.parse(
            """{"state":2,"position":-9,"duration":1e100,"rate":100,"seekable":"yes"}""")!!
        assertEquals(0L, value.position)
        assertEquals(31536000000L, value.duration)
        assertEquals(16f, value.rate, 0f)
        assertFalse(value.seekable)
    }

    @Test fun allSupportedPlaybackStatesArePreserved() {
        for (state in listOf(1, 2, 3, 6, 7))
            assertEquals(state, WebPlaybackSnapshot.parse("{\"state\":$state}")!!.state)
    }

    @Test fun rejectsOversizedInputAndCleansDisplayText() {
        assertNull(WebPlaybackSnapshot.parse(" ".repeat(24001)))
        val value = WebPlaybackSnapshot.parse("""{"state":3,"title":" A\nB\u0000C "}""")!!
        assertEquals("A B C", value.title)
    }
}

package com.ets2gps

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckTest {
    @Test fun versions() {
        assertEquals(listOf(1, 11, 0), UpdateCheck.version("v1.11.0"))
        assertEquals(listOf(1, 11, 0), UpdateCheck.version("1.11"))
        assertNull(UpdateCheck.version("latest"))
        assertTrue(UpdateCheck.newer(listOf(1, 11, 0), listOf(1, 9, 9)))
        assertFalse(UpdateCheck.newer(listOf(1, 11, 0), listOf(1, 11, 0)))
    }

    private fun rel(tag: String, apk: Boolean = true, pre: Boolean = false, url: String = "https://github.com/x/y/releases/tag/$tag") =
        """{"tag_name":"$tag","draft":false,"prerelease":$pre,"html_url":"$url","assets":[{"name":"${if (apk) "app.apk" else "setup.exe"}"}]}"""

    @Test fun newerRelease() {
        assertEquals("1.11.1", UpdateCheck.fromLatest(rel("v1.11.1"), "1.11.0")?.latest)
        assertEquals("1.12.0", UpdateCheck.fromLatest(rel("v1.12.0"), "1.11.9")?.latest)
        assertNull(UpdateCheck.fromLatest(rel("v1.11.0"), "1.11.0"))
        assertNull(UpdateCheck.fromLatest(rel("v1.10.2"), "1.11.0"))
    }

    @Test fun ignoresOtherReleases() {
        assertNull(UpdateCheck.fromLatest(rel("v2.0.0", apk = false), "1.11.0"))
        assertNull(UpdateCheck.fromLatest(rel("v2.0.0", pre = true), "1.11.0"))
        assertEquals(UpdateCheck.RELEASES_PAGE, UpdateCheck.fromLatest(rel("v2.0.0", url = "https://evil.example/x"), "1.11.0")?.pageUrl)
    }
}

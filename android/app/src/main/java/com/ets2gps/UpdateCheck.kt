package com.ets2gps

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A newer release found on GitHub. */
class UpdateInfo(val latest: String, val tag: String, val pageUrl: String)

/**
 * Asks GitHub which release is the newest one (one small request to the public API; nothing about you or your device is sent beyond a normal web request).
 * It never downloads or installs anything: the user is shown the version and chooses to open the release page.
 */
object UpdateCheck {
    const val REPO = "johnevansau320/ETS2GPS"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    /** "v1.11.0" -> [1, 11, 0]; null when it is not a version. */
    fun version(tag: String): List<Int>? {
        val parts = tag.trim().trimStart('v', 'V').split('.')
        if (parts.size < 2 || parts.size > 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return (nums + listOf(0, 0, 0)).take(3)
    }

    /** true when [a] is a newer version than [b]. */
    fun newer(a: List<Int>, b: List<Int>): Boolean {
        for (i in 0 until 3) { if (a[i] != b[i]) return a[i] > b[i] }
        return false
    }

    /** The newer release described by a GitHub "latest release" answer, or null (also for a draft, a pre-release or a release without an .apk). */
    fun fromLatest(json: String, current: String): UpdateInfo? {
        val cur = version(current) ?: return null
        val r = JSONObject(json)
        if (r.optBoolean("draft") || r.optBoolean("prerelease")) return null
        val assets = r.optJSONArray("assets") ?: return null
        var hasApk = false
        for (k in 0 until assets.length()) if (assets.optJSONObject(k)?.optString("name", "")?.endsWith(".apk", ignoreCase = true) == true) hasApk = true
        if (!hasApk) return null
        val tag = r.optString("tag_name", "")
        val v = version(tag) ?: return null
        var url = r.optString("html_url", RELEASES_PAGE)
        if (!url.startsWith("https://github.com/")) url = RELEASES_PAGE // only ever open a page on github.com
        return if (newer(v, cur)) UpdateInfo(v.joinToString("."), tag, url) else null
    }

    /** Blocking: call from a background thread. Throws when GitHub cannot be reached. */
    fun check(current: String): UpdateInfo? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "ETS2-GPS-Android/$current")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        return fromLatest(body, current)
    }
}

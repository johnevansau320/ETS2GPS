package com.ets2gps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Company logos and country flags from the game, packed by the Windows app's map builder (etsassets.bin), decoded when first needed. */
class MapAssets private constructor(private val logos: Map<String, ByteArray>, private val flags: Map<String, ByteArray>) {
    private val cache = HashMap<String, Bitmap?>()

    val logoCount get() = logos.size
    val flagCount get() = flags.size

    private fun decode(key: String, png: ByteArray): Bitmap? = cache.getOrPut(key) {
        try { BitmapFactory.decodeByteArray(png, 0, png.size) } catch (_: Throwable) { null }
    }

    fun logo(id: String): Bitmap? = logos[id.lowercase()]?.let { decode("l:" + id.lowercase(), it) }

    fun flag(cc: String): Bitmap? {
        var k = cc.lowercase()
        var png = flags[k]
        if (png == null) { k = if (k == "gb") "uk" else if (k == "uk") "gb" else k; png = flags[k] }
        return png?.let { decode("f:$k", it) }
    }

    companion object {
        fun load(f: File): MapAssets? {
            return try {
                val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4); b.get(magic)
                if (String(magic, Charsets.US_ASCII) != "ETSA") return null
                b.position(8)
                val n = b.int
                val logos = HashMap<String, ByteArray>(); val flags = HashMap<String, ByteArray>()
                for (i in 0 until n) {
                    val kind = b.get().toInt() and 0xFF
                    val kl = b.get().toInt() and 0xFF
                    val kb = ByteArray(kl); b.get(kb)
                    val len = b.int
                    val png = ByteArray(len); b.get(png)
                    (if (kind == 0) logos else flags)[String(kb, Charsets.UTF_8)] = png
                }
                MapAssets(logos, flags)
            } catch (_: Throwable) { null }
        }
    }
}

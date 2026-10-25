package com.tikikeeve.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Reads a PBR texture ZIP (base color, normal, roughness, metallic, AO or a packed ORM/ARM map).
 * Files are matched by name. Textures are downsampled to max_texture_px to protect the memory budget.
 * Nothing is invented: a ZIP without recognizable images is an error.
 */
object PbrZip {
    private val IMG = Regex(".*\\.(png|jpg|jpeg)$")

    fun load(file: File, maxPx: Int): MatData {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.lowercase().matches(IMG) && !it.name.contains("__MACOSX") }.toList()

            fun find(vararg keys: String, avoid: String? = null): ZipEntry? {
                for (k in keys) {
                    entries.firstOrNull {
                        val n = it.name.lowercase().substringAfterLast('/')
                        n.contains(k) && (avoid == null || !n.contains(avoid))
                    }?.let { return it }
                }
                return null
            }

            val base = find("basecolor", "base_color", "albedo", "diffuse", "_col", "color")
            val normal = find("normalgl", "normal_gl", "normal", "_nor", avoid = "dx")
            val rough = find("roughness", "_rough")
            val metal = find("metallic", "metalness", "_metal")
            val ao = find("ambientocclusion", "ambient_occlusion", "occlusion", "_ao")
            val packed = find("_orm", "_arm", "orm.", "arm.")

            if (base == null && normal == null && rough == null && metal == null && ao == null && packed == null)
                throw IllegalArgumentException("No PBR images recognized in ${file.name}. Expected names containing basecolor/albedo, normal, roughness, metallic, ambientocclusion (png/jpg).")

            val baseBytes = base?.let { png(decode(zip, it, maxPx)) }
            val normalBytes = normal?.let { png(decode(zip, it, maxPx)) }

            var orm: ByteArray? = null
            var hasRough = false
            var hasMetal = false
            if (packed != null) {
                orm = png(decode(zip, packed, maxPx)); hasRough = true; hasMetal = true
            } else if (rough != null || metal != null || ao != null) {
                val bmps = listOf(ao, rough, metal).map { e -> e?.let { decode(zip, it, maxPx) } }
                val ref = bmps.first { it != null }!!
                val w = ref.width; val h = ref.height
                val chans = bmps.map { b ->
                    b?.let {
                        val s = if (it.width == w && it.height == h) it else Bitmap.createScaledBitmap(it, w, h, true)
                        IntArray(w * h).also { arr -> s.getPixels(arr, 0, w, 0, 0, w, h) }
                    }
                }
                val out = IntArray(w * h) { i ->
                    val r = chans[0]?.let { (it[i] shr 8) and 0xFF } ?: 255
                    val g = chans[1]?.let { (it[i] shr 8) and 0xFF } ?: 255
                    val b = chans[2]?.let { (it[i] shr 8) and 0xFF } ?: 0
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                val ob = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
                orm = png(ob)
                bmps.forEach { it?.recycle() }
                hasRough = rough != null
                hasMetal = metal != null
            }
            return MatData(base = baseBytes, orm = orm, normal = normalBytes, metalFromMap = hasMetal, roughFromMap = hasRough)
        }
    }

    private fun decode(zip: ZipFile, e: ZipEntry, maxPx: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(e).use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > maxPx) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        return zip.getInputStream(e).use { BitmapFactory.decodeStream(it, null, o2) }
            ?: throw IllegalArgumentException("Cannot decode ${e.name}")
    }

    private fun png(b: Bitmap): ByteArray {
        val bo = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, bo)
        b.recycle()
        return bo.toByteArray()
    }
}

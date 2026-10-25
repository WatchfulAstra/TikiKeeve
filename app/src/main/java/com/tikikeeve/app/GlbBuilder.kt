package com.tikikeeve.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MeshData(
    val pos: FloatArray,
    val nrm: FloatArray? = null,
    val uv: FloatArray? = null,
    val col: FloatArray? = null, // RGBA per vertex
    val idx: IntArray,
)

data class MatData(
    val color: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    val metallic: Float = 0f,
    val rough: Float = 0.8f,
    val base: ByteArray? = null,   // PNG
    val orm: ByteArray? = null,    // PNG: R=occlusion G=roughness B=metallic
    val normal: ByteArray? = null, // PNG, OpenGL (+Y) convention
    val metalFromMap: Boolean = false,
    val roughFromMap: Boolean = false,
)

/** Builds an in-memory binary glTF so primitives, Python meshes and FBX all go through Filament's glTF path. */
object GlbBuilder {
    fun build(m: MeshData, mat: MatData): ByteArray {
        val vCount = m.pos.size / 3
        require(m.pos.size % 3 == 0 && vCount > 0) { "mesh needs vertices (x,y,z)" }
        require(m.idx.isNotEmpty() && m.idx.size % 3 == 0) { "mesh needs triangle indices (multiple of 3)" }
        require(m.idx.all { it in 0 until vCount }) { "mesh index out of range (vertex count = $vCount)" }
        m.uv?.let { require(it.size == vCount * 2) { "uvs must have 2 values per vertex" } }
        m.col?.let { require(it.size == vCount * 4) { "colors must have 4 values per vertex" } }
        m.nrm?.let { require(it.size == vCount * 3) { "normals must have 3 values per vertex" } }
        val nrm = m.nrm ?: computeNormals(m.pos, m.idx)

        val bin = ByteArrayOutputStream()
        val views = JSONArray()
        val accs = JSONArray()
        val images = JSONArray()
        val textures = JSONArray()

        fun addView(bytes: ByteArray, target: Int): Int {
            while (bin.size() % 4 != 0) bin.write(0)
            val off = bin.size()
            bin.write(bytes)
            val v = JSONObject().put("buffer", 0).put("byteOffset", off).put("byteLength", bytes.size)
            if (target != 0) v.put("target", target)
            views.put(v)
            return views.length() - 1
        }
        fun addAcc(view: Int, comp: Int, count: Int, type: String, mn: DoubleArray? = null, mx: DoubleArray? = null): Int {
            val a = JSONObject().put("bufferView", view).put("componentType", comp).put("count", count).put("type", type)
            if (mn != null && mx != null) {
                a.put("min", JSONArray().also { j -> mn.forEach { j.put(it) } })
                a.put("max", JSONArray().also { j -> mx.forEach { j.put(it) } })
            }
            accs.put(a)
            return accs.length() - 1
        }
        fun f(a: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            b.asFloatBuffer().put(a); return b.array()
        }
        fun ints(a: IntArray): ByteArray {
            val b = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            b.asIntBuffer().put(a); return b.array()
        }
        fun tex(png: ByteArray): Int {
            val v = addView(png, 0)
            images.put(JSONObject().put("bufferView", v).put("mimeType", "image/png"))
            textures.put(JSONObject().put("sampler", 0).put("source", images.length() - 1))
            return textures.length() - 1
        }

        val mn = DoubleArray(3) { Double.MAX_VALUE }
        val mx = DoubleArray(3) { -Double.MAX_VALUE }
        for (i in m.pos.indices) { val c = i % 3; mn[c] = min(mn[c], m.pos[i].toDouble()); mx[c] = max(mx[c], m.pos[i].toDouble()) }

        val attrs = JSONObject()
        attrs.put("POSITION", addAcc(addView(f(m.pos), 34962), 5126, vCount, "VEC3", mn, mx))
        attrs.put("NORMAL", addAcc(addView(f(nrm), 34962), 5126, vCount, "VEC3"))
        m.uv?.let { attrs.put("TEXCOORD_0", addAcc(addView(f(it), 34962), 5126, vCount, "VEC2")) }
        m.col?.let { attrs.put("COLOR_0", addAcc(addView(f(it), 34962), 5126, vCount, "VEC4")) }
        val idxAcc = addAcc(addView(ints(m.idx), 34963), 5125, m.idx.size, "SCALAR")

        val pbr = JSONObject()
            .put("baseColorFactor", JSONArray().also { j -> mat.color.forEach { j.put(it.toDouble()) } })
            .put("metallicFactor", (if (mat.metalFromMap) 1f else mat.metallic).toDouble())
            .put("roughnessFactor", (if (mat.roughFromMap) 1f else mat.rough).toDouble())
        val material = JSONObject().put("pbrMetallicRoughness", pbr).put("doubleSided", true)
        mat.base?.let { pbr.put("baseColorTexture", JSONObject().put("index", tex(it))) }
        mat.orm?.let {
            val t = tex(it)
            pbr.put("metallicRoughnessTexture", JSONObject().put("index", t))
            material.put("occlusionTexture", JSONObject().put("index", t))
        }
        mat.normal?.let { material.put("normalTexture", JSONObject().put("index", tex(it))) }
        if (mat.color[3] < 0.999f) material.put("alphaMode", "BLEND")

        val root = JSONObject()
            .put("asset", JSONObject().put("version", "2.0").put("generator", "TikiKeeve"))
            .put("scene", 0)
            .put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
            .put("nodes", JSONArray().put(JSONObject().put("mesh", 0)))
            .put("meshes", JSONArray().put(JSONObject().put("primitives", JSONArray().put(
                JSONObject().put("attributes", attrs).put("indices", idxAcc).put("material", 0)))))
            .put("materials", JSONArray().put(material))
            .put("accessors", accs)
            .put("bufferViews", views)
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.size())))
        if (textures.length() > 0) {
            root.put("samplers", JSONArray().put(JSONObject()
                .put("magFilter", 9729).put("minFilter", 9987).put("wrapS", 10497).put("wrapT", 10497)))
            root.put("textures", textures).put("images", images)
        }

        var json = root.toString().toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - json.size % 4) % 4
        val binBytes = bin.toByteArray()
        val binPad = (4 - binBytes.size % 4) % 4
        val total = 12 + 8 + json.size + jsonPad + 8 + binBytes.size + binPad
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(total)
        out.putInt(json.size + jsonPad).putInt(0x4E4F534A).put(json); repeat(jsonPad) { out.put(0x20) }
        out.putInt(binBytes.size + binPad).putInt(0x004E4942).put(binBytes); repeat(binPad) { out.put(0) }
        return out.array()
    }

    fun computeNormals(pos: FloatArray, idx: IntArray): FloatArray {
        val n = FloatArray(pos.size)
        var t = 0
        while (t < idx.size) {
            val a = idx[t] * 3; val b = idx[t + 1] * 3; val c = idx[t + 2] * 3
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (k in intArrayOf(a, b, c)) { n[k] += nx; n[k + 1] += ny; n[k + 2] += nz }
            t += 3
        }
        var i = 0
        while (i < n.size) {
            val l = sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2])
            if (l > 1e-12f) { n[i] /= l; n[i + 1] /= l; n[i + 2] /= l } else { n[i + 1] = 1f }
            i += 3
        }
        return n
    }
}

/** Unit primitives: box 1x1x1, sphere diameter 1, plane 1x1 (XZ, facing +Y). Scaled by the object transform. */
object Prims {
    private val FACES = arrayOf(
        // normal, u, v  (u x v = normal)
        floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
        floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f),
        floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f),
        floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
        floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f),
        floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f),
    )

    private fun faces(list: List<FloatArray>, rep: Float): MeshData {
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>()
        val corners = arrayOf(floatArrayOf(-1f, -1f, 0f, 1f), floatArrayOf(1f, -1f, 1f, 1f), floatArrayOf(1f, 1f, 1f, 0f), floatArrayOf(-1f, 1f, 0f, 0f))
        for (fc in list) {
            val base = pos.size / 3
            for (c in corners) {
                pos.add(fc[0] * 0.5f + fc[3] * 0.5f * c[0] + fc[6] * 0.5f * c[1])
                pos.add(fc[1] * 0.5f + fc[4] * 0.5f * c[0] + fc[7] * 0.5f * c[1])
                pos.add(fc[2] * 0.5f + fc[5] * 0.5f * c[0] + fc[8] * 0.5f * c[1])
                nrm.add(fc[0]); nrm.add(fc[1]); nrm.add(fc[2])
                uv.add(c[2] * rep); uv.add(c[3] * rep)
            }
            idx.addAll(listOf(base, base + 1, base + 2, base, base + 2, base + 3))
        }
        return MeshData(pos.toFloatArray(), nrm.toFloatArray(), uv.toFloatArray(), null, idx.toIntArray())
    }

    fun box(rep: Float) = faces(FACES.toList(), rep)
    fun plane(rep: Float): MeshData {
        val m = faces(listOf(FACES[2]), rep)
        return MeshData(FloatArray(m.pos.size) { i -> if (i % 3 == 1) 0f else m.pos[i] }, m.nrm, m.uv, null, m.idx)
    }

    fun sphere(rep: Float, w: Int = 28, h: Int = 18): MeshData {
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>()
        for (i in 0..h) {
            val th = PI.toFloat() * i / h
            for (j in 0..w) {
                val ph = 2f * PI.toFloat() * j / w
                val x = sin(th) * cos(ph); val y = cos(th); val z = sin(th) * sin(ph)
                pos.add(x * 0.5f); pos.add(y * 0.5f); pos.add(z * 0.5f)
                nrm.add(x); nrm.add(y); nrm.add(z)
                uv.add(j.toFloat() / w * rep); uv.add(i.toFloat() / h * rep)
            }
        }
        for (i in 0 until h) for (j in 0 until w) {
            val a = i * (w + 1) + j; val b = a + w + 1
            idx.addAll(listOf(a, a + 1, b, a + 1, b + 1, b))
        }
        return MeshData(pos.toFloatArray(), nrm.toFloatArray(), uv.toFloatArray(), null, idx.toIntArray())
    }
}

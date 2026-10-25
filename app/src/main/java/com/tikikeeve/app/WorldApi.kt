package com.tikikeeve.app

import android.util.Log
import com.google.android.filament.gltfio.FilamentAsset
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Bridge object handed to Python (tiki.core wraps it). Plain types only; all numbers are Double. */
class WorldApi(
    private val gl: FilamentScene,
    private val worldDir: File,
    private val maxTex: Int,
    private val heavyCheck: () -> Unit,
    private val inputProvider: () -> DoubleArray,
    private val hudSink: (String) -> Unit,
) {
    private val objects = HashMap<String, FilamentAsset>()
    private val zipCache = HashMap<String, MatData>()

    fun log(msg: String) { Log.i("TikiWorld", msg) }
    fun hud(text: String) { hudSink(text) }
    fun input(): DoubleArray = inputProvider()

    fun sky(r: Double, g: Double, b: Double) { gl.setSky(r.toFloat(), g.toFloat(), b.toFloat()) }
    fun sun(dx: Double, dy: Double, dz: Double, intensity: Double, r: Double, g: Double, b: Double) {
        gl.setSun(dx.toFloat(), dy.toFloat(), dz.toFloat(), intensity.toFloat(), r.toFloat(), g.toFloat(), b.toFloat())
    }
    fun ambient(intensity: Double) { gl.setAmbient(intensity.toFloat()) }
    fun camera(ex: Double, ey: Double, ez: Double, cx: Double, cy: Double, cz: Double, fov: Double) {
        gl.setCamera(ex, ey, ez, cx, cy, cz, fov)
    }

    fun has(id: String): Boolean = objects.containsKey(id)

    fun remove(id: String) {
        objects.remove(id)?.let { gl.remove(it) }
    }

    fun addPrimitive(
        id: String, shape: String, r: Double, g: Double, b: Double, a: Double,
        metallic: Double, roughness: Double, material: String?, uvRepeat: Double,
    ) {
        val rep = uvRepeat.toFloat()
        val mesh = when (shape) {
            "box" -> Prims.box(rep)
            "sphere" -> Prims.sphere(rep)
            "plane" -> Prims.plane(rep)
            else -> throw IllegalArgumentException("Unknown shape '$shape' (box, sphere, plane)")
        }
        put(id, GlbBuilder.build(mesh, mat(r, g, b, a, metallic, roughness, material)))
    }

    fun addMesh(
        id: String, pos: DoubleArray, idx: IntArray, nrm: DoubleArray?, uv: DoubleArray?, col: DoubleArray?,
        r: Double, g: Double, b: Double, a: Double, metallic: Double, roughness: Double, material: String?,
    ) {
        if (material != null && uv == null) throw IllegalArgumentException("A material ZIP needs uvs on a custom mesh")
        val mesh = MeshData(f(pos), nrm?.let { f(it) }, uv?.let { f(it) }, col?.let { f(it) }, idx)
        put(id, GlbBuilder.build(mesh, mat(r, g, b, a, metallic, roughness, material)))
    }

    /** .glb (as-is) or .fbx (static geometry). Paths are relative to the world folder. */
    fun addModel(id: String, path: String, material: String?) {
        val file = resolve(path)
        when (file.extension.lowercase()) {
            "glb" -> put(id, file.readBytes())
            "fbx" -> {
                val m = FbxReader.read(file)
                if (material != null && m.uv == null) throw IllegalArgumentException("${file.name} has no UVs, cannot apply a material ZIP")
                put(id, GlbBuilder.build(m, mat(1.0, 1.0, 1.0, 1.0, 0.0, 0.8, material)))
            }
            else -> throw IllegalArgumentException("Unsupported model type '.${file.extension}' (use .glb or .fbx)")
        }
    }

    fun setTransform(
        id: String, px: Double, py: Double, pz: Double, rx: Double, ry: Double, rz: Double,
        sx: Double, sy: Double, sz: Double,
    ) {
        val a = objects[id] ?: throw IllegalArgumentException("Unknown object '$id'")
        gl.setTransform(a, trs(px, py, pz, rx, ry, rz, sx, sy, sz))
    }

    fun shutdown() {
        for (a in objects.values) runCatching { gl.remove(a) }
        objects.clear()
        zipCache.clear()
    }

    // ---- internals ----
    private fun put(id: String, glb: ByteArray) {
        remove(id)
        objects[id] = gl.addGlb(glb)
        heavyCheck()
    }

    private fun mat(r: Double, g: Double, b: Double, a: Double, metallic: Double, rough: Double, material: String?): MatData {
        val base = MatData(floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat(), a.toFloat()), metallic.toFloat(), rough.toFloat())
        if (material == null) return base
        val z = zipCache.getOrPut(material) { PbrZip.load(resolve(material), maxTex).also { heavyCheck() } }
        return z.copy(color = base.color, metallic = base.metallic, rough = base.rough)
    }

    private fun resolve(path: String): File {
        val f = File(path).let { if (it.isAbsolute) it else File(worldDir, path) }
        if (!f.exists()) throw IllegalArgumentException("File not found: ${f.path} (see README: adding assets)")
        return f
    }

    private fun f(d: DoubleArray) = FloatArray(d.size) { d[it].toFloat() }

    /** Column-major TRS; rotation order Y(yaw) * X(pitch) * Z(roll), degrees. */
    private fun trs(px: Double, py: Double, pz: Double, rx: Double, ry: Double, rz: Double, sx: Double, sy: Double, sz: Double): FloatArray {
        val ax = Math.toRadians(rx); val ay = Math.toRadians(ry); val az = Math.toRadians(rz)
        val cx = cos(ax); val sxn = sin(ax); val cy = cos(ay); val syn = sin(ay); val cz = cos(az); val szn = sin(az)
        val r00 = cy * cz + syn * sxn * szn; val r01 = -cy * szn + syn * sxn * cz; val r02 = syn * cx
        val r10 = cx * szn;                  val r11 = cx * cz;                    val r12 = -sxn
        val r20 = -syn * cz + cy * sxn * szn; val r21 = syn * szn + cy * sxn * cz; val r22 = cy * cx
        return floatArrayOf(
            (r00 * sx).toFloat(), (r10 * sx).toFloat(), (r20 * sx).toFloat(), 0f,
            (r01 * sy).toFloat(), (r11 * sy).toFloat(), (r21 * sy).toFloat(), 0f,
            (r02 * sz).toFloat(), (r12 * sz).toFloat(), (r22 * sz).toFloat(), 0f,
            px.toFloat(), py.toFloat(), pz.toFloat(), 1f,
        )
    }
}

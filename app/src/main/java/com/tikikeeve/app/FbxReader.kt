package com.tikikeeve.app

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

/**
 * Minimal BINARY FBX reader: static mesh geometry only (positions, normals, UVs).
 * Ignores node transforms, materials, embedded textures, skins and animation.
 */
object FbxReader {
    private class Node(val name: String, val props: List<Any>, val children: List<Node>) {
        fun child(n: String) = children.firstOrNull { it.name == n }
    }

    fun read(file: File): MeshData {
        val data = file.readBytes()
        val magic = "Kaydara FBX Binary  "
        require(data.size > 27 && String(data, 0, 20, Charsets.ISO_8859_1) == magic) {
            "Only binary FBX is supported (this looks like ASCII FBX). Re-export as Binary FBX, or use GLB."
        }
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(23)
        val wide = bb.int >= 7500
        val top = ArrayList<Node>()
        while (bb.remaining() >= 13) top.add(readNode(bb, wide) ?: break)

        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val uv = ArrayList<Float>()
        var allNormals = true; var allUv = true; var geoms = 0

        for (g in top.firstOrNull { it.name == "Objects" }?.children.orEmpty().filter { it.name == "Geometry" }) {
            val verts = g.child("Vertices")?.props?.firstOrNull() as? DoubleArray ?: continue
            val pvi = g.child("PolygonVertexIndex")?.props?.firstOrNull() as? IntArray ?: continue
            geoms++
            val normals = g.child("LayerElementNormal")?.child("Normals")?.props?.firstOrNull() as? DoubleArray
            val uvs = g.child("LayerElementUV")?.child("UV")?.props?.firstOrNull() as? DoubleArray
            val uvIdx = g.child("LayerElementUV")?.child("UVIndex")?.props?.firstOrNull() as? IntArray
            val useN = normals != null && normals.size == pvi.size * 3
            val useUv = uvs != null && (uvIdx != null || uvs.size == pvi.size * 2)
            if (!useN) allNormals = false
            if (!useUv) allUv = false

            fun emit(corner: Int) {
                val vi = if (pvi[corner] < 0) pvi[corner].inv() else pvi[corner]
                pos.add(verts[vi * 3].toFloat()); pos.add(verts[vi * 3 + 1].toFloat()); pos.add(verts[vi * 3 + 2].toFloat())
                if (useN) { nrm.add(normals!![corner * 3].toFloat()); nrm.add(normals[corner * 3 + 1].toFloat()); nrm.add(normals[corner * 3 + 2].toFloat()) }
                else { nrm.add(0f); nrm.add(0f); nrm.add(0f) }
                if (useUv) {
                    val ui = if (uvIdx != null) uvIdx[corner] else corner
                    uv.add(uvs!![ui * 2].toFloat()); uv.add(1f - uvs[ui * 2 + 1].toFloat())
                } else { uv.add(0f); uv.add(0f) }
            }

            var start = 0
            for (i in pvi.indices) {
                if (pvi[i] < 0) {               // last corner of a polygon: fan-triangulate
                    for (k in 1 until (i - start)) { emit(start); emit(start + k); emit(start + k + 1) }
                    start = i + 1
                }
            }
        }
        require(geoms > 0 && pos.isNotEmpty()) { "No mesh geometry found in ${file.name}" }
        val n = pos.size / 3
        return MeshData(
            pos.toFloatArray(),
            if (allNormals) nrm.toFloatArray() else null,   // null -> flat normals computed
            if (allUv) uv.toFloatArray() else null,
            null,
            IntArray(n) { it },
        )
    }

    private fun readNode(bb: ByteBuffer, wide: Boolean): Node? {
        val end = if (wide) bb.long else bb.int.toLong() and 0xFFFFFFFFL
        val numProps = if (wide) bb.long else bb.int.toLong() and 0xFFFFFFFFL
        if (wide) bb.long else bb.int // property list length (unused)
        val nameLen = bb.get().toInt() and 0xFF
        if (end == 0L) return null
        val nb = ByteArray(nameLen); bb.get(nb)
        val props = ArrayList<Any>()
        repeat(numProps.toInt()) { props.add(readProp(bb)) }
        val kids = ArrayList<Node>()
        while (bb.position() < end) kids.add(readNode(bb, wide) ?: break)
        bb.position(end.toInt())
        return Node(String(nb, Charsets.ISO_8859_1), props, kids)
    }

    private fun readProp(bb: ByteBuffer): Any = when (val t = bb.get().toInt().toChar()) {
        'Y' -> bb.short
        'C' -> bb.get()
        'I' -> bb.int
        'F' -> bb.float
        'D' -> bb.double
        'L' -> bb.long
        'f', 'd', 'l', 'i', 'b' -> readArray(bb, t)
        'S', 'R' -> { val b = ByteArray(bb.int); bb.get(b); if (t == 'S') String(b, Charsets.ISO_8859_1) else b }
        else -> throw IllegalArgumentException("Unsupported FBX property type '$t'")
    }

    private fun readArray(bb: ByteBuffer, t: Char): Any {
        val len = bb.int; val enc = bb.int; val clen = bb.int
        val esz = when (t) { 'd', 'l' -> 8; 'f', 'i' -> 4; else -> 1 }
        val raw = ByteArray(if (enc == 0) len * esz else clen); bb.get(raw)
        val bytes = if (enc == 1) InflaterInputStream(ByteArrayInputStream(raw)).readBytes() else raw
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (t) {
            'd' -> DoubleArray(len) { b.double }
            'f' -> FloatArray(len) { b.float }
            'i' -> IntArray(len) { b.int }
            'l' -> LongArray(len) { b.long }
            else -> bytes
        }
    }
}

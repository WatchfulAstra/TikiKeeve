package com.tikikeeve.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.Environment
import org.json.JSONObject
import java.io.File

/** Where everything user-editable lives, plus first-run copying of bundled defaults. */
object Home {
    fun hasAllFiles(): Boolean = Environment.isExternalStorageManager()

    /** /sdcard/TikiKeeve if "all files access" is granted, otherwise the app's own external folder. */
    fun root(ctx: Context): File {
        val dir = if (hasAllFiles()) File(Environment.getExternalStorageDirectory(), "TikiKeeve")
        else (ctx.getExternalFilesDir(null) ?: ctx.filesDir)
        dir.mkdirs()
        return dir
    }

    fun projects(ctx: Context): File = File(root(ctx), "projects").apply { mkdirs() }

    /** Copies bundled defaults (assets/home) into the root. Never overwrites existing files. */
    fun bootstrap(ctx: Context) = copyAssets(ctx, "home", root(ctx))

    fun copyAssets(ctx: Context, assetPath: String, dest: File) {
        val children = ctx.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            if (!dest.exists()) {
                dest.parentFile?.mkdirs()
                ctx.assets.open(assetPath).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            }
        } else {
            dest.mkdirs()
            for (c in children) copyAssets(ctx, "$assetPath/$c", File(dest, c))
        }
    }

    fun config(ctx: Context): JSONObject =
        try { JSONObject(File(root(ctx), "config.json").readText()) } catch (e: Exception) { JSONObject() }

    fun decodeSampled(path: String, maxPx: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > maxPx) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }
}

class BudgetExceeded(val usedMb: Int, val budgetMb: Int) :
    RuntimeException("Memory budget exceeded: $usedMb MB used, budget $budgetMb MB")

/** Measures process PSS (Java + native + graphics) against the configured budget. */
class MemoryGuard(val budgetMb: Int) {
    @Volatile var trippedMb = -1

    fun usedMb(): Int {
        val i = Debug.MemoryInfo()
        Debug.getMemoryInfo(i)
        return i.totalPss / 1024
    }

    /** Throws BudgetExceeded when over budget. */
    fun check(): Int {
        val u = usedMb()
        if (u > budgetMb) {
            trippedMb = u
            throw BudgetExceeded(u, budgetMb)
        }
        return u
    }
}

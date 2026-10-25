package com.tikikeeve.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/**
 * Black splash. Customize without rebuilding: <root>/splash/splash.json and <root>/splash/splash.png
 * {"background":"#000000","duration_ms":1800,"image":"splash.png","scale":"fit"|"fill"}
 */
class SplashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { Home.bootstrap(this) }

        val dir = File(Home.root(this), "splash")
        val cfg = try { JSONObject(File(dir, "splash.json").readText()) } catch (e: Exception) { JSONObject() }
        val bg = try { Color.parseColor(cfg.optString("background", "#000000")) } catch (e: Exception) { Color.BLACK }

        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        val img = File(dir, cfg.optString("image", "splash.png"))
        val bmp = if (img.exists()) runCatching { Home.decodeSampled(img.path, 2048) }.getOrNull() else null

        if (bmp != null) {
            root.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = if (cfg.optString("scale", "fit") == "fill") ImageView.ScaleType.CENTER_CROP
                else ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            // No splash image provided yet: plain text, nothing faked.
            root.addView(TextView(this).apply {
                text = "TIKI KEEVE"
                setTextColor(Color.WHITE)
                textSize = 28f
                letterSpacing = 0.25f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, ProjectsActivity::class.java))
            finish()
        }, cfg.optLong("duration_ms", 1800))
    }
}

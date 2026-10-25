package com.tikikeeve.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import kotlin.math.min

class WorldActivity : Activity() {
    companion object { private var enginePath: String? = null }

    private lateinit var gl: FilamentScene
    private lateinit var api: WorldApi
    private lateinit var controls: ControlsView
    private lateinit var guard: MemoryGuard
    private lateinit var overlay: LinearLayout
    private lateinit var overlayTitle: TextView
    private lateinit var overlayBody: TextView

    private var rt: PyObject? = null
    private var started = false
    private var failed = false
    private var alive = true
    private var lastNs = 0L
    private var frames = 0
    private var fpsStartNs = 0L

    private val memThread = HandlerThread("tiki-mem").apply { start() }
    private val memHandler by lazy { Handler(memThread.looper) }
    private val memTick = object : Runnable {
        override fun run() {
            if (!alive || failed) return
            val u = guard.usedMb()
            if (u > guard.budgetMb) { guard.trippedMb = u; runOnUiThread { failOom() } }
            else memHandler.postDelayed(this, 1000)
        }
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            if (failed || !started || !alive) return
            val dt = if (lastNs == 0L) 0.016 else min(0.05, (ns - lastNs) / 1e9)
            lastNs = ns
            try {
                if (!rt!!.callAttr("update", dt).toBoolean()) { failPython(); return }
                gl.render(ns)
            } catch (e: BudgetExceeded) { failOom(); return }
            catch (e: OutOfMemoryError) { guard.trippedMb = guard.usedMb(); failOom(); return }
            catch (t: Throwable) { fail("Error", t.stackTraceToString()); return }

            frames++
            if (fpsStartNs == 0L) fpsStartNs = ns
            if (ns - fpsStartNs >= 1_000_000_000L) {
                controls.debug = "${frames} fps  ${guard.usedMb()} / ${guard.budgetMb} MB"
                frames = 0; fpsStartNs = ns
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val dirPath = intent.getStringExtra("dir")
        if (dirPath == null) { finish(); return }
        val dir = File(dirPath)

        val cfg = Home.config(this)
        guard = MemoryGuard(cfg.optInt("memory_budget_mb", 700))
        val maxTex = cfg.optInt("max_texture_px", 1024)

        val surface = SurfaceView(this)
        gl = FilamentScene(surface)
        controls = ControlsView(this).apply { onExit = { finish() } }
        api = WorldApi(gl, dir, maxTex, { guard.check() }, { controls.snapshot() }, { controls.hud = it; controls.postInvalidate() })

        overlayTitle = TextView(this).apply { textSize = 24f; setTextColor(0xFFFF5555.toInt()) }
        overlayBody = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.MONOSPACE }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2000000.toInt())
            setPadding(48, 80, 48, 48)
            visibility = View.GONE
            addView(overlayTitle)
            addView(ScrollView(context).apply { addView(overlayBody) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(Button(context).apply { text = "Back to projects"; setOnClickListener { finish() } })
            setOnTouchListener { _, _ -> true }
        }

        setContentView(FrameLayout(this).apply {
            addView(surface, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
            addView(overlay, FrameLayout.LayoutParams(-1, -1))
        })

        controls.debug = "loading…"
        surface.post { startWorld(dir) }
    }

    private fun startWorld(dir: File) {
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
            val py = Python.getInstance()
            val engine = File(Home.root(this), "engine").absolutePath
            if (enginePath != engine) {
                val path = py.getModule("sys").get("path")!!
                enginePath?.let { path.callAttr("remove", it) }
                path.callAttr("insert", 0, engine)
                enginePath = engine
            }
            val r = py.getModule("tiki.runtime")
            rt = r
            if (!r.callAttr("start", dir.absolutePath, api).toBoolean()) { failPython(); return }
            guard.check()
            started = true
            Choreographer.getInstance().postFrameCallback(frame)
            memHandler.postDelayed(memTick, 1000)
        } catch (e: BudgetExceeded) { failOom()
        } catch (e: OutOfMemoryError) { guard.trippedMb = guard.usedMb(); failOom()
        } catch (t: Throwable) { fail("Could not start the world", t.stackTraceToString()) }
    }

    private fun failPython() {
        if (guard.trippedMb > 0) { failOom(); return }
        fail("Python error in world", rt?.get("last_error")?.toString() ?: "unknown error")
    }

    private fun failOom() {
        val used = if (guard.trippedMb > 0) guard.trippedMb else guard.usedMb()
        fail(
            "Out of memory budget",
            "Used about $used MB; the budget is ${guard.budgetMb} MB.\n\n" +
                "The world was stopped and its objects freed so your phone stays responsive.\n\n" +
                "Try: smaller textures (max_texture_px in config.json), fewer or simpler objects, " +
                "or raise memory_budget_mb in config.json if your phone has the headroom."
        )
    }

    private fun fail(title: String, body: String) {
        if (failed) return
        failed = true
        Choreographer.getInstance().removeFrameCallback(frame)
        runCatching { rt?.callAttr("stop") }
        runCatching { api.shutdown() }
        overlayTitle.text = title
        overlayBody.text = body
        overlay.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onResume() {
        super.onResume()
        if (started && !failed) { lastNs = 0L; Choreographer.getInstance().postFrameCallback(frame) }
    }

    override fun onDestroy() {
        alive = false
        Choreographer.getInstance().removeFrameCallback(frame)
        memThread.quitSafely()
        runCatching { rt?.callAttr("stop") }
        runCatching { api.shutdown() }
        runCatching { gl.destroy() }
        super.onDestroy()
    }
}

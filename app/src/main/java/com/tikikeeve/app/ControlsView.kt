package com.tikikeeve.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Transparent touch overlay: floating joystick (left), look-drag (right), jump button, exit button. */
class ControlsView(ctx: Context) : View(ctx) {
    var onExit: (() -> Unit)? = null
    var hud: String = ""
    var debug: String = ""

    private val d = resources.displayMetrics.density
    private val joyR = 60 * d
    private val jumpR = 38 * d
    private val exitSize = 56 * d

    private var joyId = -1; private var joyOx = 0f; private var joyOy = 0f; private var joyX = 0f; private var joyY = 0f
    private var lookId = -1; private var lastX = 0f; private var lastY = 0f
    private var lookDx = 0f; private var lookDy = 0f
    private var jumpId = -1

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = 0x66FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x44FFFFFF }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * d; alpha = 200 }

    private fun jumpCx() = width - jumpR - 40 * d
    private fun jumpCy() = height - jumpR - 40 * d

    /** [moveX, moveY, lookDx, lookDy, jump] — look values in dp since last call. */
    fun snapshot(): DoubleArray {
        var mx = 0f; var my = 0f
        if (joyId != -1) {
            mx = (joyX - joyOx) / joyR; my = -(joyY - joyOy) / joyR
            val l = hypot(mx, my)
            if (l > 1f) { mx /= l; my /= l }
        }
        val out = doubleArrayOf(mx.toDouble(), my.toDouble(), (lookDx / d).toDouble(), (lookDy / d).toDouble(), if (jumpId != -1) 1.0 else 0.0)
        lookDx = 0f; lookDy = 0f
        return out
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                if (x < exitSize && y < exitSize) { onExit?.invoke(); return true }
                if (hypot(x - jumpCx(), y - jumpCy()) < jumpR * 1.3f) jumpId = id
                else if (x < width / 2 && joyId == -1) { joyId = id; joyOx = x; joyOy = y; joyX = x; joyY = y }
                else if (lookId == -1) { lookId = id; lastX = x; lastY = y }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                if (id == joyId) { joyX = e.getX(i); joyY = e.getY(i) }
                else if (id == lookId) {
                    lookDx += e.getX(i) - lastX; lookDy += e.getY(i) - lastY
                    lastX = e.getX(i); lastY = e.getY(i)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == joyId) joyId = -1
                if (id == lookId) lookId = -1
                if (id == jumpId) jumpId = -1
            }
            MotionEvent.ACTION_CANCEL -> { joyId = -1; lookId = -1; jumpId = -1 }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawText("\u2715", exitSize * 0.3f, exitSize * 0.65f, text)
        if (joyId != -1) {
            c.drawCircle(joyOx, joyOy, joyR, stroke)
            var dx = joyX - joyOx; var dy = joyY - joyOy
            val l = hypot(dx, dy)
            if (l > joyR) { dx = dx / l * joyR; dy = dy / l * joyR }
            c.drawCircle(joyOx + dx, joyOy + dy, joyR * 0.4f, fill)
        }
        c.drawCircle(jumpCx(), jumpCy(), jumpR, if (jumpId != -1) fill else stroke)
        if (hud.isNotEmpty()) c.drawText(hud, exitSize, 24 * d, text)
        if (debug.isNotEmpty()) c.drawText(debug, width - text.measureText(debug) - 12 * d, 24 * d, text)
    }
}

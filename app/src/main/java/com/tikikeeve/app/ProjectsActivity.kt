package com.tikikeeve.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

class ProjectsActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var info: TextView
    private lateinit var grant: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }
        scroll.addView(col)

        col.addView(TextView(this).apply {
            text = "Tiki Keeve"; textSize = 28f; setTextColor(Color.WHITE)
        })
        info = TextView(this).apply { textSize = 12f; setTextColor(0xFF9A9A9A.toInt()) }
        col.addView(info)

        grant = Button(this).apply {
            text = "Grant all-files access (edit worlds from any file manager)"
            setOnClickListener { requestAllFiles() }
        }
        col.addView(grant)

        col.addView(Button(this).apply {
            text = "+ New world"
            setOnClickListener { newWorldDialog() }
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        runCatching { Home.bootstrap(this) }
        refresh()
    }

    private fun requestAllFiles() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun refresh() {
        info.text = "Worlds folder:\n${Home.projects(this).absolutePath}"
        grant.visibility = if (Home.hasAllFiles()) android.view.View.GONE else android.view.View.VISIBLE
        list.removeAllViews()
        val dirs = Home.projects(this).listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        if (dirs.isEmpty()) {
            list.addView(TextView(this).apply { text = "No worlds yet."; setTextColor(Color.GRAY); setPadding(0, dp(16), 0, 0) })
        }
        for (d in dirs) list.addView(row(d))
    }

    private fun row(dir: File): LinearLayout {
        val meta = try { JSONObject(File(dir, "project.json").readText()) } catch (e: Exception) { JSONObject() }
        val hasScript = File(dir, "world.py").exists()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(0xFF161616.toInt()); cornerRadius = dp(10).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            addView(TextView(context).apply { text = meta.optString("name", dir.name); textSize = 18f; setTextColor(Color.WHITE) })
            addView(TextView(context).apply {
                text = if (hasScript) meta.optString("description", dir.name) else "Missing world.py"
                setTextColor(if (hasScript) 0xFF9A9A9A.toInt() else Color.RED)
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(Button(context).apply {
                    text = "Delete"
                    setOnClickListener {
                        AlertDialog.Builder(this@ProjectsActivity)
                            .setTitle("Delete \"${dir.name}\"?")
                            .setMessage("This permanently deletes the folder and everything inside it.")
                            .setPositiveButton("Delete") { _, _ -> dir.deleteRecursively(); refresh() }
                            .setNegativeButton("Cancel", null).show()
                    }
                })
                addView(Button(context).apply {
                    text = "Open"; isEnabled = hasScript
                    setOnClickListener {
                        startActivity(Intent(this@ProjectsActivity, WorldActivity::class.java).putExtra("dir", dir.absolutePath))
                    }
                })
            })
        }
    }

    private fun newWorldDialog() {
        val input = EditText(this).apply { hint = "World name"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("New world").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                val slug = name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
                if (slug.isEmpty()) { Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val dir = File(Home.projects(this), slug)
                if (dir.exists()) { Toast.makeText(this, "Already exists", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                Home.copyAssets(this, "template", dir)
                File(dir, "project.json").writeText(JSONObject().put("name", name).put("description", "New world").toString(2))
                refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }
}

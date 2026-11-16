package com.densityfold

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.densityfold.engine.DirectDensityDiag
import com.densityfold.engine.DisplayUtils

class DirectDiagActivity : Activity() {

    private lateinit var reportView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
        }

        val title = TextView(this).apply {
            text = "Density Fold — Direct API диагностика"
            textSize = 20f
            setPadding(0, 0, 0, 16)
        }
        content.addView(title)

        content.addView(makeButton("Проверить API") {
            runProbe()
        })

        content.addView(makeButton("Обновить сведения об устройстве") {
            reportView.text = deviceReport()
        })

        content.addView(makeButton("Скопировать отчёт") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("DirectDiag report", reportView.text))
        })

        reportView = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = deviceReport()
        }

        val scroll = ScrollView(this)
        scroll.addView(reportView)
        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(content)
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            gravity = Gravity.CENTER
            setOnClickListener { action() }
        }
    }

    private fun deviceReport(): String {
        val info = DisplayUtils.getActiveDisplay(this)
        val display = if (info != null) {
            "${info.width}x${info.height}, DPI=${info.density}, external=${info.isExternal}"
        } else {
            "не удалось получить активный дисплей"
        }

        val permission = checkSelfPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        return buildString {
            appendLine("PACKAGE: $packageName")
            appendLine("SDK: ${Build.VERSION.SDK_INT}")
            appendLine("RELEASE: ${Build.VERSION.RELEASE}")
            appendLine("TARGET SDK: ${applicationInfo.targetSdkVersion}")
            appendLine("PID: ${android.os.Process.myPid()}")
            appendLine("WRITE_SECURE_SETTINGS: $permission")
            appendLine("DISPLAY: $display")
            appendLine()
            appendLine("Нажмите «Проверить API» для reflection-диагностики.")
        }
    }

    private fun runProbe() {
        reportView.text = deviceReport() + "\nВыполняется проверка..."
        Thread {
            val result = DirectDensityDiag.run(this)
            runOnUiThread {
                reportView.text = deviceReport() + "\n\n" + result
            }
        }.start()
    }
}

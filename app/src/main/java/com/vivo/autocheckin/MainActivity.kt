package com.vivo.autocheckin

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer

/**
 * 主界面
 *
 * 职责：
 * 1. 启动时检测无障碍服务是否开启，未开启则引导跳转系统设置。
 * 2. 提供「开始签到 / 停止」按钮，触发 [MyAccessibilityService.startCheckin]。
 * 3. 通过 [Logger] 的 LiveData 实时展示日志、进度、当前任务。
 * 4. 拉起 [CheckinService] 前台保活服务。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvAccessibilityState: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvCurrentTask: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnStart: View
    private lateinit var btnStop: View
    private lateinit var btnOpenAccessibility: View
    private lateinit var btnCopyLog: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupLog()

        // 申请通知权限（Android 13+），用于前台保活通知
        requestNotificationPermission()

        // 拉起前台保活服务
        CheckinService.start(this)

        btnStart.setOnClickListener {
            val service = MyAccessibilityService.instance
            if (service == null) {
                Logger.error("无障碍服务未开启，无法开始签到。")
                promptOpenAccessibility()
                return@setOnClickListener
            }
            Logger.clear()
            service.startCheckin()
        }

        btnStop.setOnClickListener {
            MyAccessibilityService.instance?.stopCheckin()
        }

        btnOpenAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        btnCopyLog.setOnClickListener {
            val text = tvLog.text?.toString().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("VivoAutoCheckin_Log", text))
            Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityState()
    }

    private fun bindViews() {
        tvAccessibilityState = findViewById(R.id.tvAccessibilityState)
        tvProgress = findViewById(R.id.tvProgress)
        tvCurrentTask = findViewById(R.id.tvCurrentTask)
        tvLog = findViewById(R.id.tvLog)
        logScroll = findViewById(R.id.logScroll)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnCopyLog = findViewById(R.id.btnCopyLog)
    }

    private fun setupLog() {
        // 让 TextView 内部可滚动
        tvLog.movementMethod = ScrollingMovementMethod.getInstance()

        Logger.log.observe(this, Observer { text ->
            tvLog.text = text
            // 滚动到底部，展示最新日志
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        })

        Logger.progress.observe(this, Observer { done ->
            val total = Logger.total.value ?: 4
            tvProgress.text = "$done / $total"
        })

        Logger.total.observe(this, Observer { total ->
            val done = Logger.progress.value ?: 0
            tvProgress.text = "$done / $total"
        })

        Logger.currentTask.observe(this, Observer { task ->
            tvCurrentTask.text = "当前任务：$task"
        })
    }

    /** 检测本应用的无障碍服务是否已启用。 */
    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        if (enabled != 1) return false
        val services = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val componentName = "$packageName/${MyAccessibilityService::class.java.name}"
        return services.contains(componentName)
    }

    private fun refreshAccessibilityState() {
        val ok = isAccessibilityEnabled()
        if (ok) {
            tvAccessibilityState.text = "已开启"
            tvAccessibilityState.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            btnOpenAccessibility.visibility = View.GONE
            btnStart.isEnabled = true
        } else {
            tvAccessibilityState.text = "未开启"
            tvAccessibilityState.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
            btnOpenAccessibility.visibility = View.VISIBLE
            btnStart.isEnabled = false
            Logger.warn("无障碍服务未开启，请先授权。")
        }
    }

    private fun promptOpenAccessibility() {
        openAccessibilitySettings()
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            // 兜底：跳转通用设置
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() &&
            grantResults[0] != PackageManager.PERMISSION_GRANTED
        ) {
            Logger.warn("未授予通知权限，前台保活通知可能无法显示。")
        }
    }
}

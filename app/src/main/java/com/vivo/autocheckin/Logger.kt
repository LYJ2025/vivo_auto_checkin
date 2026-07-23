package com.vivo.autocheckin

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局日志中心
 *
 * - 使用 [MutableLiveData] 持有全部日志文本，UI（MainActivity）通过 observe 自动刷新。
 * - 所有日志追加在主线程，保证 TextView 设置文本线程安全。
 * - 同时提供 [progress] 进度数据（已完成任务数 / 总任务数）。
 */
object Logger {

    /** 完整日志文本（带时间戳），UI 直接绑定。 */
    private val _log = MutableLiveData("")
    val log: LiveData<String> get() = _log

    /** 已完成任务数。 */
    private val _progress = MutableLiveData(0)
    val progress: LiveData<Int> get() = _progress

    /** 总任务数（固定 4）。 */
    private val _total = MutableLiveData(4)
    val total: LiveData<Int> get() = _total

    /** 当前正在执行的任务描述。 */
    private val _currentTask = MutableLiveData("空闲")
    val currentTask: LiveData<String> get() = _currentTask

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 追加一行日志。 */
    fun log(message: String, level: Level = Level.INFO) {
        val time = timeFmt.format(Date())
        val tag = when (level) {
            Level.INFO -> "INFO"
            Level.SUCCESS -> " OK "
            Level.WARN -> "WARN"
            Level.ERROR -> "ERR "
        }
        val line = "[$time][$tag] $message"
        mainHandler.post {
            val old = _log.value ?: ""
            val new = if (old.isEmpty()) line else "$old\n$line"
            // 控制日志长度，避免无限增长导致 OOM
            val trimmed = if (new.length > 8000) new.takeLast(8000) else new
            _log.value = trimmed
        }
    }

    fun info(msg: String) = log(msg, Level.INFO)
    fun success(msg: String) = log(msg, Level.SUCCESS)
    fun warn(msg: String) = log(msg, Level.WARN)
    fun error(msg: String) = log(msg, Level.ERROR)

    fun setProgress(done: Int) {
        mainHandler.post { _progress.value = done }
    }

    fun setTotal(total: Int) {
        mainHandler.post { _total.value = total }
    }

    fun setCurrentTask(task: String) {
        mainHandler.post { _currentTask.value = task }
    }

    /** 清空日志（不重置进度）。 */
    fun clear() {
        mainHandler.post { _log.value = "" }
    }

    enum class Level { INFO, SUCCESS, WARN, ERROR }
}

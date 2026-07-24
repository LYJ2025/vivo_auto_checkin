package com.vivo.autocheckin

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 核心无障碍服务
 *
 * 职责：
 * 1. 维护自身静态实例，供 [MainActivity] 触发签到。
 * 2. 顺序执行 [TaskManager.tasks] 中的 4 个签到任务。
 * 3. 通过 AccessibilityNodeInfo 遍历节点定位签到按钮（viewId 优先 → text → desc）。
 * 4. 去重判断、弹窗处理、超时降级、未安装跳过。
 * 5. 全过程通过 [Logger] 实时回传日志与进度。
 *
 * 注意：本服务不使用 HTTP，所有点击均通过 AccessibilityNodeInfo.performAction
 * 或 dispatchGesture 模拟用户真实操作。
 */
class MyAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MyAccessibilityService? = null
            private set

        /** 服务是否已连接（onServiceConnected 后置 true）。 */
        @Volatile
        var isConnected: Boolean = false
            private set

        /** 当前是否正在执行签到流程。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val TAG = "VivoAutoCheckin"
        private const val PAGE_TIMEOUT_MS = 8000L      // 单页加载最长等待
        private const val CLICK_STABLE_DELAY = 1200L    // 点击后稳定等待
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    /** 最近一次窗口状态变化事件所属包名，用于页面加载等待判断。 */
    @Volatile
    private var lastEventPackage: String? = null

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isConnected = true
        Logger.info("无障碍服务已连接，准备就绪。")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        isConnected = false
        isRunning = false
        serviceScope.cancel()
        releaseWakeLock()
        Logger.warn("无障碍服务已断开。")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isConnected = false
        isRunning = false
        serviceScope.cancel()
        releaseWakeLock()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 仅记录最近窗口包名，主流程不依赖事件驱动，避免被频繁事件干扰。
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastEventPackage = event.packageName?.toString()
        }
    }

    override fun onInterrupt() {
        Logger.warn("无障碍服务被系统中断。")
    }

    // ------------------------------------------------------------------
    // 对外暴露：启动 / 停止
    // ------------------------------------------------------------------

    /**
     * 启动签到流程。由 [MainActivity] 调用。
     * 使用 [isRunning] 防止重复执行。
     */
    fun startCheckin() {
        if (isRunning) {
            Logger.warn("已有签到任务正在执行，请等待完成。")
            return
        }
        acquireWakeLock()
        serviceScope.launch {
            isRunning = true
            try {
                runAllTasks()
            } catch (t: Throwable) {
                Logger.error("签到流程异常：${t.message}")
            } finally {
                isRunning = false
                Logger.setCurrentTask("空闲")
                releaseWakeLock()
                Logger.success("===== 全部任务结束 =====")
            }
        }
    }

    /** 停止签到流程。 */
    fun stopCheckin() {
        if (!isRunning) return
        serviceScope.cancel()
        isRunning = false
        Logger.warn("用户主动停止签到。")
    }

    // ------------------------------------------------------------------
    // 任务编排
    // ------------------------------------------------------------------

    private suspend fun runAllTasks() {
        val tasks = TaskManager.tasks
        Logger.setTotal(tasks.size)
        var done = 0
        Logger.setProgress(done)
        Logger.success("===== 开始执行 ${tasks.size} 个签到任务 =====")

        for ((index, task) in tasks.withIndex()) {
            if (!isRunning) {
                Logger.warn("签到流程已被中止。")
                return
            }
            Logger.setCurrentTask("(${index + 1}/${tasks.size}) ${task.name}")
            Logger.info("---- [${index + 1}/${tasks.size}] 开始：${task.name} ----")

            val result = performSingleTask(task)
            if (result == TaskResult.SUCCESS || result == TaskResult.SKIPPED) {
                done++
                Logger.setProgress(done)
            }
            // 任务间隔，给系统一点喘息
            delay(1200L)
        }

        Logger.success("签到结束，完成 $done/${tasks.size} 个任务。")
    }

    private enum class TaskResult { SUCCESS, SKIPPED, FAILED }

    /**
     * 执行单个签到任务。
     * 完整流程：
     *   检查安装 → 拉起 App → 等待加载 → 关弹窗
     *   → 【导航 tab】(若有 preClickTabs) → 关弹窗 → 去重判断
     *   → 【点入口卡片】(若有 entryKeywords) → 等待 → 关弹窗 → 再次去重
     *   → 查找签到按钮 → 【滚动查找】(若 needsScroll 且未找到) → 去重 → 点击 → 验证
     */
    private suspend fun performSingleTask(task: CheckinTask): TaskResult {
        // 1. 选择已安装的目标包
        val targetPkg = pickInstalledPackage(task.packages)
        if (targetPkg == null) {
            Logger.warn("${task.name}：目标 App 未安装，跳过。")
            return TaskResult.SKIPPED
        }
        Logger.info("${task.name}：目标包名 $targetPkg，正在打开…")

        // 2. 拉起目标 App 主界面；若所有候选包名都启动失败，尝试 fallbackUrl
        var launched = launchApp(targetPkg)
        var isBrowserFallback = false
        if (!launched && task.fallbackUrl != null) {
            Logger.info("${task.name}：包名启动失败，尝试用浏览器打开 ${task.fallbackUrl}")
            launched = launchUrl(task.fallbackUrl)
            isBrowserFallback = launched
        }
        if (!launched) {
            Logger.error("${task.name}：无法启动 App，跳过。")
            return TaskResult.FAILED
        }

        // 3. 等待目标 App 前台加载（带超时）
        //   浏览器兜底场景下不强求包名匹配，只要任意非自机包名进入前台即可
        val loaded = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
            if (isBrowserFallback) waitUntilAnyForeground(targetPkg)
            else waitUntilForeground(targetPkg)
        }
        if (loaded == null || !isRunning) {
            Logger.error("${task.name}：页面加载超时（>${PAGE_TIMEOUT_MS}ms），跳过本任务。")
            backToHomeSafe()
            return TaskResult.FAILED
        }
        // 给 H5 / 动画额外渲染时间
        delay(task.extraStableDelayMs)
        dismissPopupsIfNeeded()

        // 4. 【导航 tab】切换到签到入口所在页面（如"我的"/"会员中心"）
        if (task.preClickTabs.isNotEmpty()) {
            navigateToTab(task)
            delay(1000L)
            dismissPopupsIfNeeded()
        }

        // 5. 首次去重判断
        var root = rootInActiveWindowSafe()
        if (root != null && containsAnyKeyword(root, task.skipKeywords)) {
            Logger.success("${task.name}：检测到已完成（已签到/已领取），跳过。")
            backToHomeSafe()
            return TaskResult.SKIPPED
        }

        // 6. 【点击入口卡片】如"每日签到"卡片，点进去才是签到页
        if (task.entryKeywords.isNotEmpty()) {
            val entered = clickEntryCard(task)
            if (entered) {
                delay(1500L)              // 等待签到页加载
                dismissPopupsIfNeeded()
                // 入口卡片可能本身就是签到入口，点击后再次去重
                root = rootInActiveWindowSafe()
                if (root != null && containsAnyKeyword(root, task.skipKeywords)) {
                    Logger.success("${task.name}：入口点击后检测到已完成，跳过。")
                    backToHomeSafe()
                    return TaskResult.SKIPPED
                }
            } else {
                Logger.info("${task.name}：未找到签到入口卡片，尝试直接查找签到按钮…")
            }
        }

        // 7. 查找签到按钮（含短轮询等待 H5 渲染）
        var button = findCheckinNodeSafe(task)
        if (button == null) {
            Logger.info("${task.name}：未立即找到签到按钮，继续等待页面渲染…")
            val deadline = System.currentTimeMillis() + 4000L
            while (System.currentTimeMillis() < deadline && isRunning) {
                delay(500L)
                dismissPopupsIfNeeded()
                val r = rootInActiveWindowSafe()
                if (r != null) {
                    if (containsAnyKeyword(r, task.skipKeywords)) {
                        Logger.success("${task.name}：检测到已完成，跳过。")
                        backToHomeSafe()
                        return TaskResult.SKIPPED
                    }
                    button = findCheckinNode(r, task)
                    if (button != null) break
                }
            }
        }

        // 8. 【滚动查找】签到按钮可能在页面下方，需下滑
        if (button == null && task.needsScroll) {
            Logger.info("${task.name}：当前屏未找到，开始下滑查找…")
            for (scrollIdx in 1..3) {
                if (!isRunning) break
                scrollDown()
                delay(800L)
                dismissPopupsIfNeeded()
                val r = rootInActiveWindowSafe() ?: continue
                if (containsAnyKeyword(r, task.skipKeywords)) {
                    Logger.success("${task.name}：滚动后检测到已完成，跳过。")
                    backToHomeSafe()
                    return TaskResult.SKIPPED
                }
                button = findCheckinNode(r, task)
                if (button != null) {
                    Logger.info("${task.name}：第 $scrollIdx 次滚动后找到签到按钮。")
                    break
                }
            }
        }

        if (button == null) {
            Logger.error("${task.name}：未找到签到按钮，跳过本任务。")
            backToHomeSafe()
            return TaskResult.FAILED
        }

        // 9. 点击前再次确认按钮非完成态
        val btnText = nodeText(button)
        if (task.skipKeywords.any { btnText.contains(it) }) {
            Logger.success("${task.name}：按钮已为「$btnText」，跳过。")
            backToHomeSafe()
            return TaskResult.SKIPPED
        }

        // 10. 执行点击
        Logger.info("${task.name}：定位到签到按钮「${btnText.ifEmpty { "签到" }}」，模拟点击…")
        val clicked = performClick(button)
        delay(CLICK_STABLE_DELAY)

        // 11. 点击后关闭弹窗 & 验证结果
        dismissPopupsIfNeeded()
        val verified = verifyAfterClick(task)

        if (clicked && verified) {
            Logger.success("${task.name}：签到成功！")
        } else if (clicked) {
            Logger.success("${task.name}：已点击签到按钮（结果未明确确认，请以 App 内显示为准）。")
        } else {
            Logger.error("${task.name}：点击失败。")
        }

        backToHomeSafe()
        return if (clicked) TaskResult.SUCCESS else TaskResult.FAILED
    }

    /** 获取根节点并查找签到按钮的便捷封装。 */
    private fun findCheckinNodeSafe(task: CheckinTask): AccessibilityNodeInfo? {
        val root = rootInActiveWindowSafe() ?: return null
        return findCheckinNode(root, task)
    }

    /**
     * 【导航 tab】点击底部 tab（如"我的"/"会员中心"）。
     * 按候选 tab 文字顺序尝试，命中第一个即点击并返回。
     */
    private suspend fun navigateToTab(task: CheckinTask) {
        for (tabText in task.preClickTabs) {
            if (!isRunning) return
            val root = rootInActiveWindowSafe() ?: continue
            val tabNode = findTextNodeExact(root, tabText)
            if (tabNode != null) {
                val target = if (tabNode.isClickable) tabNode else findClickableAncestor(tabNode)
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Logger.info("${task.name}：已切换到「$tabText」tab。")
                    delay(1200L)
                    return
                }
            }
        }
        Logger.warn("${task.name}：未找到底部 tab（${task.preClickTabs.joinToString("/")}），使用当前页。")
    }

    /**
     * 【点击入口卡片】点击右上角「积分」胶囊 / 积分文字，进入签到页面。
     *
     * 智能选择策略（精准定位右上角胶囊，排除广告/长文本）：
     * 1. 收集所有 text/desc 包含关键词的节点
     * 2. 评分：可见 + 可点击 + 顶部位置 + 文本短 → 高分
     *          广告长文本 / 含"新人/享/借" → 大幅扣分
     * 3. 选中后点击（ACTION_CLICK → 手势兜底）
     *
     * @return true 表示成功点击了入口
     */
    private suspend fun clickEntryCard(task: CheckinTask): Boolean {
        for (keyword in task.entryKeywords) {
            if (!isRunning) return false
            val root = rootInActiveWindowSafe() ?: continue

            // 收集所有匹配节点
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectMatchingNodes(root, keyword, candidates)
            if (candidates.isEmpty()) continue

            // 屏幕尺寸（用于判断右上角）
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels

            // 广告类关键词：命中则大幅扣分
            val adWords = listOf(
                "新人", "享", "借", "领券", "福利", "立减", "折扣", "元", "￥",
                "商城", "兑换", "过期", "快来", "领取~", "马上", "立即抢", "低价",
                "满减", "专享", "省", "赚", "送", "免费"
            )

            // 按优先级评分排序
            val scored = candidates.map { node ->
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val cx = rect.exactCenterX()
                val cy = rect.exactCenterY()
                val txt = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")

                var score = 0
                // 可点击 +1000
                if (findClickableTarget(node) != null) score += 1000

                // 可见性判定：坐标必须在屏幕范围内（排除滚动到页面外的节点）
                val visible = cy in 0f..screenH.toFloat() && cx in 0f..screenW.toFloat()
                if (visible) score += 800 else score -= 1500  // 不可见直接出局

                // 【关键】位置判定：真正的积分胶囊位于屏幕顶部状态栏下方（顶部 15% 内）。
                // 用户反馈钱包误点 y=823 的页面中部"积分"文字（实际是页面下方蓝框区域），
                // 而真正的胶囊在屏幕顶部红框位置。因此对非顶部节点大幅扣分。
                if (visible && cy < screenH * 0.15f) {
                    // 顶部 15% 内：胶囊核心区，强加分
                    score += 1500
                    // 右上角再加成（x 在右侧 50%）
                    if (cx > screenW * 0.5f) score += 500
                } else if (visible && cy < screenH * 0.3f) {
                    // 顶部 15%~30%：勉强算顶部，小幅加分
                    score += 200
                } else {
                    // 顶部 30% 以下：明显不是顶部胶囊（页面中部内容），强制出局
                    score -= 3000
                }

                // 文本短加分（胶囊通常 1~6 字，广告文案 10+ 字）
                val textLen = txt.length
                if (textLen in 1..6) score += 300
                else if (textLen > 10) score -= 2000  // 长文本大概率是广告

                // 严格排除：文本含"积分"但后面跟其他字（如"积分商城"/"积分明细"）
                // 真正的胶囊应该是"积分"或"我的积分"或"数字+积分"
                if (txt.contains("积分")) {
                    val after = txt.substringAfter("积分")
                    if (after.isNotEmpty() && after != " ") {
                        // "积分商城"/"积分明细"等 → 大幅扣分
                        score -= 4000
                    }
                }

                // 广告词扣分
                if (adWords.any { txt.contains(it) }) score -= 3000

                Triple(node, score, txt)
            }.filter { it.second >= 200 }  // 低于 200 分直接放弃（避免硬选广告）

            if (scored.isEmpty()) {
                Logger.info("${task.name}：找到「$keyword」节点但都不符合右上角胶囊特征，跳过入口点击。")
                continue
            }

            val best = scored.maxBy { it.second }
            val bestNode = best.first
            val bestScore = best.second
            val bestTxt = best.third
            val target = findClickableTarget(bestNode)
            if (target != null) {
                val rect = android.graphics.Rect()
                target.getBoundsInScreen(rect)
                Logger.info("${task.name}：点击积分入口「$bestTxt」(score=$bestScore,x=${rect.exactCenterX().toInt()},y=${rect.exactCenterY().toInt()})…")
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                if (gestureClick(rect.exactCenterX(), rect.exactCenterY())) return true
            }
        }
        return false
    }

    /** 收集所有 text 或 contentDescription 包含 [keyword] 的节点。 */
    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo,
        keyword: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if ((text.isNotEmpty() && text.contains(keyword)) ||
            (desc.isNotEmpty() && desc.contains(keyword))
        ) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectMatchingNodes(child, keyword, out)
        }
    }

    /** 获取节点的可点击目标：本身可点击则返回自己，否则返回最近可点击祖先。 */
    private fun findClickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return if (node.isClickable) node else findClickableAncestor(node)
    }

    /** 向下滑动一屏（用于查找下方签到入口）。 */
    private fun scrollDown() {
        try {
            val displayMetrics = resources.displayMetrics
            val w = displayMetrics.widthPixels.toFloat()
            val h = displayMetrics.heightPixels.toFloat()
            val path = android.graphics.Path().apply {
                moveTo(w / 2f, h * 0.7f)
                lineTo(w / 2f, h * 0.3f)
            }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                path, 0, 400
            )
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Throwable) {
        }
    }

    /** 递归查找 text 完全等于 [text] 的节点。 */
    private fun findTextNodeExact(
        node: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        if (node.text?.toString()?.trim() == text) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = findTextNodeExact(child, text)
            if (r != null) return r
        }
        return null
    }

    /** 递归查找 text 包含 [text] 的节点。 */
    private fun findTextNodeContains(
        node: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        val t = node.text?.toString().orEmpty()
        if (t.isNotEmpty() && t.contains(text)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = findTextNodeContains(child, text)
            if (r != null) return r
        }
        return null
    }

    // ------------------------------------------------------------------
    // 安装检测 & 启动
    // ------------------------------------------------------------------

    private fun pickInstalledPackage(candidates: List<String>): String? {
        val pm = packageManager
        return candidates.firstOrNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    private fun launchApp(pkg: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                Logger.error("无法获取 $pkg 的启动 Intent。")
                return false
            }
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            startActivity(intent)
            true
        } catch (t: Throwable) {
            Logger.error("启动 $pkg 失败：${t.message}")
            false
        }
    }

    /** 用 ACTION_VIEW 打开 URL，由系统选择浏览器。 */
    private fun launchUrl(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (t: Throwable) {
            Logger.error("打开 URL $url 失败：${t.message}")
            false
        }
    }

    /** 轮询直到目标包进入前台，超时由调用方 withTimeoutOrNull 控制。 */
    private suspend fun waitUntilForeground(targetPkg: String) {
        while (isRunning) {
            val root = rootInActiveWindowSafe()
            val pkg = root?.packageName ?: lastEventPackage
            if (pkg == targetPkg) return
            delay(300L)
        }
    }

    /**
     * 浏览器兜底场景使用：等待任意非自机包名进入前台。
     * 只要前台包名不再是本应用自己（com.vivo.autocheckin），就视为浏览器已打开。
     */
    private suspend fun waitUntilAnyForeground(selfPkg: String) {
        val self = "com.vivo.autocheckin"
        while (isRunning) {
            val root = rootInActiveWindowSafe()
            val pkg = (root?.packageName ?: lastEventPackage)?.toString().orEmpty()
            if (pkg != self && pkg.isNotEmpty()) return
            delay(300L)
        }
    }

    // ------------------------------------------------------------------
    // 节点查找与点击
    // ------------------------------------------------------------------

    /** 安全获取当前活跃窗口根节点（可能为 null）。 */
    private fun rootInActiveWindowSafe(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 定位签到节点。优先级：viewId → text → contentDescription。
     * 命中去重关键词的节点会被排除。
     */
    private fun findCheckinNode(
        root: AccessibilityNodeInfo,
        task: CheckinTask
    ): AccessibilityNodeInfo? {
        // 1. viewId 优先
        for (viewId in task.viewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (nodes != null) {
                for (node in nodes) {
                    if (isClickableLike(node) && !isSkipNode(node, task)) {
                        return node
                    }
                }
            }
        }

        // 2. 文本匹配（深度遍历）
        val textMatch = findByText(root, task.textKeywords, task)
        if (textMatch != null) return textMatch

        // 3. contentDescription 匹配
        val descMatch = findByDesc(root, task.descKeywords, task)
        if (descMatch != null) return descMatch

        return null
    }

    /** 递归查找 text 命中关键词且可点击的节点。 */
    private fun findByText(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        task: CheckinTask
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString().orEmpty()
        if (text.isNotEmpty() && keywords.any { text.contains(it) } && !isSkipNode(node, task)) {
            if (isClickableLike(node)) return node
            // 当前节点不可点击时，向上找最近的可点击祖先
            val clickableParent = findClickableAncestor(node)
            if (clickableParent != null) return clickableParent
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = findByText(child, keywords, task)
            if (r != null) return r
        }
        return null
    }

    /** 递归查找 contentDescription 命中关键词且可点击的节点。 */
    private fun findByDesc(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        task: CheckinTask
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString().orEmpty()
        if (desc.isNotEmpty() && keywords.any { desc.contains(it) } && !isSkipNode(node, task)) {
            if (isClickableLike(node)) return node
            val clickableParent = findClickableAncestor(node)
            if (clickableParent != null) return clickableParent
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = findByDesc(child, keywords, task)
            if (r != null) return r
        }
        return null
    }

    /** 节点是否含去重关键词（text 或 desc）。 */
    private fun isSkipNode(node: AccessibilityNodeInfo, task: CheckinTask): Boolean {
        val t = node.text?.toString().orEmpty()
        val d = node.contentDescription?.toString().orEmpty()
        return task.skipKeywords.any { t.contains(it) || d.contains(it) }
    }

    /** 判断节点是否"类可点击"。 */
    private fun isClickableLike(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || node.isEnabled
    }

    /** 向上查找最近的可点击祖先节点。 */
    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = node.parent
        var guard = 0
        while (p != null && guard < 12) {
            if (p.isClickable) return p
            p = p.parent
            guard++
        }
        return null
    }

    /**
     * 执行点击：优先 ACTION_CLICK；不可点击则点击其可点击祖先；
     * 全部失败时使用 dispatchGesture 点击节点中心坐标兜底。
     */
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        // 1. 直接 ACTION_CLICK
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        // 2. 点击可点击祖先
        val ancestor = findClickableAncestor(node)
        if (ancestor != null && ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        // 3. 父节点 ACTION_CLICK 兜底
        val parent = node.parent
        if (parent != null && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        // 4. dispatchGesture 坐标点击兜底
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return gestureClick(rect.exactCenterX(), rect.exactCenterY())
    }

    /** 使用 dispatchGesture 在屏幕坐标执行点击。 */
    private fun gestureClick(x: Float, y: Float): Boolean {
        return try {
            val path = android.graphics.Path().apply { moveTo(x, y) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                path, 0, 60
            )
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Logger.error("手势点击失败：${t.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // 弹窗处理
    // ------------------------------------------------------------------

    /** 检测并关闭常见弹窗。 */
    private fun dismissPopupsIfNeeded() {
        val root = rootInActiveWindowSafe() ?: return
        for (keyword in TaskManager.POPUP_DISMISS_KEYWORDS) {
            // 关闭按钮文字可能是 "×"，单独处理
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (nodes == null) continue
            for (node in nodes) {
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val hit = text == keyword || text.contains(keyword) ||
                    desc == keyword || desc.contains(keyword) ||
                    text.trim() == "×" || desc.trim() == "×"
                if (!hit) continue
                // 仅对"关闭/取消/暂不"类按钮执行点击，避免误点正文
                if (isClickableLike(node) || findClickableAncestor(node) != null) {
                    val target = if (node.isClickable) node else findClickableAncestor(node)
                    if (target != null) {
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Logger.info("已关闭弹窗（命中「$keyword」）。")
                        return
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 验证 & 收尾
    // ------------------------------------------------------------------

    /** 点击后再次扫描：若出现完成态关键词或弹窗已确认，视为成功。 */
    private suspend fun verifyAfterClick(task: CheckinTask): Boolean {
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline && isRunning) {
            val root = rootInActiveWindowSafe()
            if (root == null) {
                delay(300L)
                continue
            }
            if (containsAnyKeyword(root, task.skipKeywords)) return true
            // 出现"签到成功/领取成功"提示
            if (containsAnyKeyword(
                    root,
                    listOf("签到成功", "领取成功", "打卡成功", "签到积分")
                )
            ) return true
            delay(400L)
        }
        return false
    }

    /** 递归判断窗口中是否存在任意关键词（text 或 desc）。 */
    private fun containsAnyKeyword(
        node: AccessibilityNodeInfo,
        keywords: List<String>
    ): Boolean {
        val t = node.text?.toString().orEmpty()
        val d = node.contentDescription?.toString().orEmpty()
        if (keywords.any { t.contains(it) || d.contains(it) }) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsAnyKeyword(child, keywords)) return true
        }
        return false
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        return node.text?.toString()
            ?: node.contentDescription?.toString()
            ?: ""
    }

    /** 返回桌面，避免影响后续任务窗口判断。 */
    private fun backToHomeSafe() {
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(200)
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // 唤醒锁（防 CPU 休眠）
    // ------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VivoAutoCheckin::CheckinWakeLock"
            ).apply { acquire(10 * 60 * 1000L /* 10 min */) }
        } catch (t: Throwable) {
            Logger.warn("唤醒锁获取失败：${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }
}

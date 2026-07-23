package com.vivo.autocheckin

/**
 * 任务清单与匹配规则定义
 *
 * 每个签到任务包含：
 * - [name]        人类可读名称（用于日志/状态栏）
 * - [packages]    目标包名候选（部分 App 在不同机型上包名不同，按顺序尝试）
 * - [viewIds]     优先用 resourceId 定位签到按钮的候选列表
 * - [textKeywords]  其次用按钮文字匹配的关键词
 * - [descKeywords]  再次用 contentDescription 匹配的关键词
 * - [skipKeywords]  去重关键词：节点命中即视为今日已完成，跳过
 *
 * 关键词与 viewId 均为基于实际 vivo 会员中心截图整理的常见值，
 * 部分机型可能略有差异，可在本文件中按需补充。
 */
data class CheckinTask(
    val name: String,
    val packages: List<String>,
    val viewIds: List<String>,
    val textKeywords: List<String>,
    val descKeywords: List<String>,
    val skipKeywords: List<String>,
    /** 进入 App 后额外等待的稳定时长（毫秒），给 H5/动画渲染留时间。 */
    val extraStableDelayMs: Long = 1500L
)

object TaskManager {

    /** 全局去重关键词：任意任务命中均视为已完成。 */
    val GLOBAL_SKIP_KEYWORDS = listOf(
        "已完成", "已签到", "已领取", "今日已签", "明天再来", "已打卡"
    )

    /** 弹窗关闭关键词：检测到即先关闭。 */
    val POPUP_DISMISS_KEYWORDS = listOf("关闭", "取消", "暂不", "我知道了", "知道了", "残忍拒绝", "稍后")

    /** 4 个任务按顺序执行。 */
    val tasks: List<CheckinTask> = listOf(
        // 任务 A：vivo 钱包签到
        CheckinTask(
            name = "钱包签到",
            packages = listOf("com.vivo.wallet"),
            viewIds = listOf(
                "com.vivo.wallet:id/tv_sign",
                "com.vivo.wallet:id/sign_in",
                "com.vivo.wallet:id/btn_checkin"
            ),
            textKeywords = listOf("签到", "立即签到", "领取", "打卡"),
            descKeywords = listOf("签到", "领取"),
            skipKeywords = GLOBAL_SKIP_KEYWORDS
        ),
        // 任务 B：游戏中心签到
        CheckinTask(
            name = "游戏中心签到",
            packages = listOf("com.vivo.game"),
            viewIds = listOf(
                "com.vivo.game:id/sign_btn",
                "com.vivo.game:id/tv_sign",
                "com.vivo.game:id/checkin_btn"
            ),
            textKeywords = listOf("签到", "立即签到", "领取", "打卡"),
            descKeywords = listOf("签到", "领取"),
            skipKeywords = GLOBAL_SKIP_KEYWORDS
        ),
        // 任务 C：应用商店签到
        CheckinTask(
            name = "应用商店签到",
            packages = listOf("com.vivo.appstore"),
            viewIds = listOf(
                "com.vivo.appstore:id/sign_btn",
                "com.vivo.appstore:id/tv_sign",
                "com.vivo.appstore:id/checkin"
            ),
            textKeywords = listOf("签到", "立即签到", "领取", "打卡"),
            descKeywords = listOf("签到", "领取"),
            skipKeywords = GLOBAL_SKIP_KEYWORDS
        ),
        // 任务 D：官网登录 / 账号签到
        CheckinTask(
            name = "官网登录签到",
            packages = listOf("com.bbk.account", "com.vivo.website"),
            viewIds = listOf(
                "com.bbk.account:id/sign_btn",
                "com.vivo.website:id/sign_btn",
                "com.bbk.account:id/tv_sign"
            ),
            textKeywords = listOf("签到", "登录签到", "立即签到", "领取", "打卡"),
            descKeywords = listOf("签到", "领取"),
            skipKeywords = GLOBAL_SKIP_KEYWORDS
        )
    )
}

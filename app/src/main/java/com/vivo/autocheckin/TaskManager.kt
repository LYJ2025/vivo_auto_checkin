package com.vivo.autocheckin

/**
 * 任务清单与匹配规则定义
 *
 * 每个签到任务包含：
 * - [name]              人类可读名称（用于日志/状态栏）
 * - [packages]          目标包名候选（部分 App 在不同机型上包名不同，按顺序尝试）
 * - [preClickTabs]      打开 App 后先点击的底部 tab 文字（如"我的"、"会员"），
 *                       用于导航到签到入口所在页面。null 表示不需要切 tab。
 * - [entryKeywords]     签到"入口卡片"文字（如"每日签到"、"签到有礼"），
 *                       点击后才会进入真正的签到页面。空列表表示直接找签到按钮。
 * - [viewIds]           优先用 resourceId 定位签到按钮的候选列表
 * - [textKeywords]      其次用按钮文字匹配的关键词
 * - [descKeywords]      再次用 contentDescription 匹配的关键词
 * - [skipKeywords]      去重关键词：节点命中即视为今日已完成，跳过
 * - [needsScroll]       找不到签到按钮时是否滚动屏幕继续查找
 * - [extraStableDelayMs] 进入 App 后额外等待的稳定时长（毫秒）
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
    val preClickTabs: List<String> = emptyList(),
    val entryKeywords: List<String> = emptyList(),
    val needsScroll: Boolean = true,
    val extraStableDelayMs: Long = 1500L,
    /** 当所有候选包名都启动失败时，尝试用浏览器打开此 URL（如 vivo 官网）。 */
    val fallbackUrl: String? = null,
    /** 节点文本命中这些词时排除（如「已签收」是订单状态，不是签到按钮）。 */
    val excludeTextKeywords: List<String> = emptyList()
)

object TaskManager {

    /** 全局去重关键词：任意任务命中均视为已完成。 */
    val GLOBAL_SKIP_KEYWORDS = listOf(
        "已完成", "已签到", "已领取", "今日已签", "明天再来", "已打卡", "已签到+"
    )

    /** 弹窗关闭关键词：检测到即先关闭。 */
    val POPUP_DISMISS_KEYWORDS = listOf(
        "关闭", "取消", "暂不", "我知道了", "知道了", "残忍拒绝", "稍后",
        "下次再说", "不感兴趣", "跳过", "不再提示", "确定", "确认"
    )

    /** 4 个任务按顺序执行（用户指定顺序：游戏中心 → vivo 官网 → 应用商店 → 钱包）。 */
    val tasks: List<CheckinTask> = listOf(
        // 任务 1：游戏中心签到
        //  流程：先点「我的」tab → 再点右上角积分胶囊 → 找签到按钮
        CheckinTask(
            name = "游戏中心签到",
            packages = listOf("com.vivo.game"),
            viewIds = listOf(
                "com.vivo.game:id/sign_btn",
                "com.vivo.game:id/tv_sign",
                "com.vivo.game:id/checkin_btn"
            ),
            textKeywords = listOf("签到", "立即签到", "领取", "打卡", "已签"),
            descKeywords = listOf("签到", "领取", "积分"),
            skipKeywords = GLOBAL_SKIP_KEYWORDS,
            preClickTabs = listOf("我的"),            // 先切到「我的」tab
            entryKeywords = listOf("我的积分", "积分"),  // 再点右上角积分胶囊
            needsScroll = true
        ),
        // 任务 2：vivo 官网签到
        //  签到入口：「我的」tab 里的「积分」文字位置
        CheckinTask(
            name = "官网登录签到",
            packages = listOf(
                "com.vivo.space",                    // vivo 官网正确包名
                "com.vivo.website"                   // 旧版本兜底
            ),
            viewIds = listOf(
                "com.vivo.space:id/sign_btn",
                "com.vivo.space:id/tv_sign",
                "com.vivo.website:id/sign_btn",
                "com.vivo.website:id/tv_sign"
            ),
            textKeywords = listOf("签到", "登录签到", "立即签到", "领取", "打卡", "做任务", "登录", "已签"),
            descKeywords = listOf("签到", "领取", "积分"),
            skipKeywords = GLOBAL_SKIP_KEYWORDS,
            preClickTabs = listOf("我的", "会员中心", "会员", "个人中心"),
            entryKeywords = listOf("我的积分", "积分"),
            needsScroll = true,
            fallbackUrl = "https://www.vivo.com.cn/",
            excludeTextKeywords = listOf("已签收")  // 排除订单状态文字
        ),
        // 任务 3：应用商店签到
        //  流程：先点「我的」tab → 再点右上角积分胶囊 → 找签到按钮
        //  （与游戏中心同款修复：应用商店主页无积分入口，需切到「我的」tab）
        CheckinTask(
            name = "应用商店签到",
            packages = listOf(
                "com.vivo.appstore",
                "com.iqoo.appstore",
                "com.bbk.appstore"
            ),
            viewIds = listOf(
                "com.vivo.appstore:id/sign_btn",
                "com.vivo.appstore:id/tv_sign",
                "com.vivo.appstore:id/checkin",
                "com.vivo.appstore:id/tv_check_in"
            ),
            textKeywords = listOf("签到", "立即签到", "领取", "打卡", "做任务", "已签"),
            descKeywords = listOf("签到", "领取", "积分"),
            skipKeywords = GLOBAL_SKIP_KEYWORDS,
            preClickTabs = listOf("我的"),            // 先切到「我的」tab
            entryKeywords = listOf("我的积分", "积分"),  // 再点右上角积分胶囊
            needsScroll = true
        ),
        // 任务 4：vivo 钱包签到
        //  签到入口：主页右上角「积分」胶囊，点击后进入签到页
        CheckinTask(
            name = "钱包签到",
            packages = listOf("com.vivo.wallet"),
            viewIds = listOf(
                "com.vivo.wallet:id/tv_sign",
                "com.vivo.wallet:id/sign_in",
                "com.vivo.wallet:id/btn_checkin"
            ),
            textKeywords = listOf("签到", "立即签到", "领取", "打卡", "已签"),
            descKeywords = listOf("签到", "领取", "积分"),
            skipKeywords = GLOBAL_SKIP_KEYWORDS,
            preClickTabs = emptyList(),
            entryKeywords = listOf("我的积分", "积分"),
            needsScroll = false
        )
    )
}

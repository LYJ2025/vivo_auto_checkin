# vivo 会员中心自动签到

基于 Android **无障碍服务**（AccessibilityService）的 vivo 会员中心自动签到 App。

每日自动完成 4 个经验值签到任务（按指定顺序执行）：

| 顺序 | 任务 | 目标包名 |
|------|------|----------|
| 1 | 游戏中心签到 | `com.vivo.game` |
| 2 | vivo 官网签到 | `com.vivo.space`（fallback: `com.vivo.website`，浏览器兜底: `https://www.vivo.com.cn/`） |
| 3 | 应用商店签到 | `com.bbk.appstore`（fallback: `com.vivo.appstore`、`com.iqoo.appstore`） |
| 4 | 钱包签到 | `com.vivo.wallet` |

## 特性

- 单 APK 部署，仅需开启无障碍权限即可使用
- 不依赖 ADB / PC 连接
- 不使用 HTTP 接口模拟，全部通过 AccessibilityNodeInfo 模拟用户真实点击
- 前台保活服务（防 OriginOS 锁屏杀进程）
- 实时日志 + 进度展示（0/4 → 4/4）+ 一键复制日志
- 详细操作日志：每次点击打印坐标、bounds、点击方式（ACTION_CLICK / 祖先 / 父节点 / 手势）和返回值
- 页面节点诊断：找不到入口时 dump 当前页所有可见文本节点（含坐标、是否可点击）
- 智能选择右上角积分胶囊：基于位置 + 文本长度 + 广告词扣分，避免误点广告
- 去重判断（已签到 / 已领取 / 今日已签自动跳过）
- 弹窗自动关闭
- 超时降级，未安装 App 自动跳过
- 兼容 Android 6.0 ~ Android 14

## 安装

1. 下载 `app-debug-vX.X.apk` 到手机
2. 允许「未知来源安装」→ 点击安装
3. 打开 App → 点「去开启无障碍服务」→ 在列表里开启「vivo 自动签到服务」
4. 授予通知权限
5. 回到 App → 点「开始签到」

## 使用说明

- 任务执行顺序为：游戏中心 → vivo 官网 → 应用商店 → 钱包
- 每个任务流程：检查安装 → 拉起 App → 等待加载 → 关弹窗 → 切底部 tab（如"我的"）→ 找签到入口 → 找签到按钮 → 点击 → 验证 → 回桌面
- 已签到过的按钮（显示"已签1天""已签2天"等）也会点击进入，触发积分到账
- App 会在屏幕右侧显示实时日志，可以点「复制日志」按钮一键复制到剪贴板

## 技术栈

- Kotlin + Coroutines
- AndroidX / Material Components
- AGP / Gradle 8.x
- minSdk 23 / targetSdk 34

## 工程结构

```
app/src/main/java/com/vivo/autocheckin/
├── Logger.kt                  # 全局日志/进度 LiveData
├── TaskManager.kt             # 4 任务清单 + 匹配规则
├── MyAccessibilityService.kt  # 核心签到执行器
├── CheckinService.kt          # 前台保活服务
├── BootReceiver.kt            # 开机自启
└── MainActivity.kt            # UI + 无障碍检测
```

## 更新日志

### v1.13（最新）

- **应用商店/钱包**：去掉积分胶囊入口，**直接找签到按钮**。根据页面 dump 发现这两个 App 的"我的"tab 页面顶部右上角直接是「已签1天」签到按钮，无积分胶囊。
- **findByText / findByDesc**：优先返回真正 `isClickable=true` 的节点本身，不可点击才找祖先（之前 `isEnabled` 也算可点击，会返回不可点击的节点本身导致点击失败）。
- **viewId 查找**：同样优化，优先返回可点击节点或其祖先。

### v1.12

- **clickEntryCard 重试机制**：外层增加 3 次重试，每次间隔 800ms。切 tab 后页面可能未渲染完，需要重试。
- **空候选日志**：之前 candidates 为空时静默跳过，现在打印「第 N/3 次未找到含「积分」的节点」。

### v1.11

- **页面节点诊断**：当找不到签到入口卡片时，dump 当前页所有可见文本节点（含文本、坐标、是否可点击），按 y 坐标排序输出。用于诊断应用商店等找不到入口的场景。

### v1.10

- **官网积分胶囊阈值调整**：位置阈值 30% → 40%。官网积分胶囊 y=853（30.8%）被误判为页面中部内容，调整后正常通过。
- **应用商店 tab 位置校验**：navigateToTab 增加底部 tab 位置校验（必须位于屏幕底部 35% 内），排除顶部状态栏同名文字（如"我的"标题）。
- **navigateToTab 重试**：最多重试 2 次，应对弹窗关闭后页面未稳定的情况。
- **findCheckinNode 可见性校验**：节点中心必须 `0 ≤ y ≤ screenH`，过滤屏幕外不可见节点（应用商店 y=-150 被误点）。
- **官网排除词**：新增 `excludeTextKeywords`，排除"已签收"等订单状态文字（被「已签」误匹配）。

### v1.9

- **详细点击日志**：
  - `navigateToTab`：打印 tab 文本、bounds、坐标、ACTION_CLICK/手势结果
  - `clickEntryCard`：打印每个候选节点评分明细 + 通过筛选的候选排名
  - `performClick`：4 步回退（ACTION_CLICK → 祖先 → 父节点 → 手势）每步都打印坐标和返回值
  - `gestureClick`：打印 dispatchGesture 调用与返回值
  - `scrollDown`：打印滑动起止坐标
  - `dismissPopupsIfNeeded`：关闭弹窗时打印命中关键词与坐标
  - `backToHomeSafe`：打印 GLOBAL_ACTION_BACK/HOME 执行
  - 点击签到按钮：打印按钮 bounds 与坐标

### v1.8

- **应用商店修复**：与游戏中心同款修复，添加 `preClickTabs = listOf("我的")`，先切到"我的"tab 再点积分胶囊。
- **钱包误点修复**：三档位置严格判定，顶部 15% 内胶囊核心区 +1500，顶部 40% 以下强制出局（-3000）。
- **日志一键复制按钮**：在"实时日志"标题旁加按钮，点击复制全部日志到剪贴板。

### v1.7

- 游戏中心增加 `preClickTabs = ["我的"]` 和 `entryKeywords = ["我的积分", "积分"]`，流程改为先切"我的"tab 再点积分胶囊。
- 排除"积分商城"等节点：文本含"积分"但后面还有其他非空格字符（如"积分商城"）扣 4000 分。
- 调整任务顺序为：游戏中心 → vivo 官网 → 应用商店 → 钱包。

### v1.6 及更早

- 钱包误点广告"最高600积分 新人首借可享"：增加可见性判定、文本长度限制、广告词扣分。
- "已签n天"按钮也点击进入，触发积分到账。
- vivo 官网包名修正为 `com.vivo.space`，增加浏览器 fallback。
- 初始版本。

## 说明

- 仓库中 `app-debug-vX.X.apk` 为可直接安装的 debug 签名包（已用 debug keystore 签名）。
- 安装前需先卸载旧版（不同版本签名可能不同）。
- 任务配置位于 `app/src/main/java/com/vivo/autocheckin/TaskManager.kt`，可按需调整。

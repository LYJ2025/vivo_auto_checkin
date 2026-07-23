# vivo 会员中心自动签到

基于 Android **无障碍服务**（AccessibilityService）的 vivo 会员中心自动签到 App。

每日自动完成 4 个经验值签到任务：

| 任务 | 目标包名 |
|------|----------|
| 钱包签到 | `com.vivo.wallet` |
| 游戏中心签到 | `com.vivo.game` |
| 应用商店签到 | `com.vivo.appstore` |
| 官网登录签到 | `com.bbk.account` / `com.vivo.website` |

## 特性

- 单 APK 部署，仅需开启无障碍权限即可使用
- 不依赖 ADB / PC 连接
- 不使用 HTTP 接口模拟，全部通过 AccessibilityNodeInfo 模拟用户真实点击
- 前台保活服务（防 OriginOS 锁屏杀进程）
- 实时日志 + 进度展示（0/4 → 4/4）
- 去重判断（已签到 / 已领取 / 今日已签自动跳过）
- 弹窗自动关闭
- 超时降级，未安装 App 自动跳过
- 兼容 Android 6.0 ~ Android 14

## 安装

1. 下载 `app-debug.apk` 到手机
2. 允许「未知来源安装」→ 点击安装
3. 打开 App → 点「去开启无障碍服务」→ 在列表里开启「vivo 自动签到服务」
4. 授予通知权限
5. 回到 App → 点「开始签到」

## 技术栈

- Kotlin + Coroutines
- AndroidX / Material Components
- AGP 8.5.2 / Gradle 8.7
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

## 说明

`app-debug.apk` 为可直接安装的 debug 签名包。

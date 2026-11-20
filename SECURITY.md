# 安全说明

## 这个 App 能做什么、不能做什么

它用的是安卓无障碍服务，这个权限理论上能读到屏幕内容、模拟点击滑动。为了把范围压到最小：

- 无障碍服务在配置里限定为只接收抖音（`com.ss.android.ugc.aweme`）的事件，见 [accessibility_config.xml](app/res/xml/accessibility_config.xml)。
- 不申请网络权限，读到的内容不会离开手机，见 [AndroidManifest.xml](app/AndroidManifest.xml)。
- 唯一的操作是上滑一次，不点击、不输入。

## 报告问题

发现安全问题，请通过 GitHub 的 [Security Advisories](../../security/advisories/new) 私下报告，不要直接开公开 issue。

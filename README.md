<p align="center"><img src="docs/icon.png" width="96" alt="抖音划走"></p>

<h1 align="center">抖音划走</h1>

<p align="center">刷抖音时遇到广告、直播带货、购物链接、团购视频，自动帮你划到下一条。</p>

<p align="center">
  <a href="../../releases/latest">下载 APK</a> ·
  <a href="docs/PRD.md">需求文档</a>
</p>

---

## 它做什么

打开抖音刷推荐流，每切到一条新视频，它会读一下屏幕上的文字。看到“广告”“查看直播”“小黄车”“团购低至”这类字样，就替你往上划一下，和你自己划没有区别。

可以按类型开关：

| 类型 | 识别什么 |
|---|---|
| 广告 | 视频上标着“广告”“赞助” |
| 直播带货 | “直播中”“查看直播”这类直播间入口 |
| 购物链接 | 小黄车、商品橱窗、“已售”“券后” |
| 团购 | “团购”“限时秒杀”“免预约” |
| 软广 | 标题里的“种草”“好物推荐”，或者你自己加的品牌名 |

每类的关键词都能在 App 里改。

## 安装

1. 在 [Releases](../../releases/latest) 下载最新的 APK，安装。
2. 打开 App，点顶部黄色区域，按提示做两步：
   - **允许获取权限**：小米手机从网上装的 App 默认不能开无障碍，需要在“权限管理”右上角 ⋮ 里点“允许应用获取权限”。
   - **打开无障碍**：在无障碍设置 →“已下载的应用”里打开「抖音划走」。
3. 刚装好默认是**试运行**，只提示不真划。刷一会儿确认没有误划，再把试运行关掉。
4. 小米手机建议在“后台保活”里做三件事：省电策略设为无限制、打开自启动、在最近任务里锁住。不然可能会被系统清理掉。

想临时看一条带货视频：下拉通知栏点“暂停”，或者在控制中心加一个“抖音划走”快捷开关。

## 校验安装包

只从本仓库的 [Releases](../../releases/latest) 下载。其他渠道的同名 APK 可能被改过。

v0.8 的 SHA-256：

```
16ee25386443c87687ba94bc770a8db792c55104d9f880bea75be48c0cf97d33
```

所有正式版本都用同一个签名证书，指纹（SHA-256）：

```
cbcf696453eaca4f01efd536b8ffefb0740e37a33b34f917a463a98499848490
```

在电脑上核对：

```bash
shasum -a 256 skip-ads-v0.8.apk
apksigner verify --print-certs skip-ads-v0.8.apk
```

签名不一致的安装包不要装，后续版本也无法覆盖安装官方版。

## 隐私

- **不联网**。App 没有申请网络权限，可以在 [AndroidManifest.xml](app/AndroidManifest.xml) 里核对。
- **只看抖音**。无障碍服务限定为 `com.ss.android.ugc.aweme`，其他 App 的内容读不到。
- **数据只在本机**。计数、关键词、漏划记录都存在手机上，卸载即清除。

## 漏划了怎么办

刷到没被划走的广告，下拉通知栏点“漏了，记一下”，它会把当前画面上读到的文字存下来。在 App 的“漏划记录”里可以分享出来。

欢迎把记录贴到 [Issues](../../issues)，用来补默认关键词。

## 已知限制

- 只支持 Android 8.0 及以上。**iOS 做不了**，系统不允许 App 读取其他 App 的界面或模拟滑动。
- 识别完全靠画面上的文字。抖音改版后关键词可能失效，需要更新。
- 没有任何标记的软广（比如博主自然口播）认不出来。
- 主要在小米 HyperOS 上使用，其他品牌的授权和保活步骤可能不一样。

## 自己编译

项目没有用 Gradle，只依赖 Android SDK（build-tools 34、platforms/android-34）和 JDK 17。

```bash
export ANDROID_HOME=~/Library/Android/sdk
./scripts/build.sh
# 产物在 build/skip-ads.apk
```

不传签名参数时会自动生成一个调试签名。用自己的密钥：

```bash
KEYSTORE=your.jks KS_PASS=xxx KEY_ALIAS=xxx ./scripts/build.sh
```

代码结构：

```
app/src/com/lbr/skipad/
├── SkipService.java   无障碍服务：识别、划走、常驻通知、漏划记录
├── Prefs.java         规则和关键词定义、开关状态
├── MainActivity.java  设置页
└── ToggleTile.java    控制中心快捷开关
```

改默认规则看 `Prefs.java` 里的 `RULES`。

## 免责声明

这是个人学习项目，与抖音及字节跳动无关。它只做和手动上滑一样的操作，不修改抖音，也不收集数据。使用产生的后果由使用者自己承担。

## License

[MIT](LICENSE)

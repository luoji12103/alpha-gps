# Alpha GPS · Power

**轻量化、低功耗与稳定优先的 Android 分支。** 基于 [Saschl/alpha-gps](https://github.com/Saschl/alpha-gps)，为索尼相机发送手机位置，加入定位策略修复、NFC 手动设备交接与相机标签。

[项目主页](https://luoji12103.github.io/alpha-gps/) · [下载最新版 APK](https://github.com/luoji12103/alpha-gps/releases/latest/download/AlphaGPS-power-arm64.apk) · [发布说明](https://github.com/luoji12103/alpha-gps/releases) · [问题反馈](https://github.com/luoji12103/alpha-gps/issues)

## 本次正式版

**`v1.7.2+power.1` · Android versionCode `173` · ARM64 · FOSS**

基础源码：官方开发分支 `ec4dae727205136d1fb0a3574246ed026d012a43`，源码版本 `v1.7.2`。截至本次发布，官方最新 tagged release 仍是 `v1.7.1`；我们的正式发布不表示官方已发布 v1.7.2。

- 每 **60 秒**请求新位置，目标每 **5 秒**向 Sony 刷新仍有效的坐标；真实样本超过 **90 秒**停止重发。
- 位置权限、系统定位或 provider 恢复后的有界重试，减少定位注册失效后持续无位置的情况。
- NFC 手动交接：旧设备先暂停这台相机并关闭本地连接，接收设备再尝试接管。
- NFC 相机标签读写：触碰并确认，连接本机已配对相机。
- FOSS 构建不含 GMS / Sentry；NFC 不增加第三方依赖或常驻读卡扫描。

**未实现：** 设备间持续 BLE 协调、按电量 / 在线状态自动切换。NFC 是手动触发交接，相机位置仍经 Sony BLE 发送。

**验证范围：** 自动测试、构建与静态检查已通过；NFC 碰靠、标签物理读写、Sony 接管、照片 EXIF 与实际功耗尚未实机验证，没有具体续航提升或无缝交接保证。详见[本版发布记录](docs/releases/v1.7.2+power.1.md)。

## 安装与 NFC

Android 8.0+，本次 APK 仅支持 **arm64-v8a**。无 NFC 设备仍能使用定位与蓝牙功能。

安装包沿用本项目此前本地试用包的签名，可覆盖安装；官方商店 / F-Droid 签名不同，无法直接覆盖。卸载原版会清除应用数据，可能需要重新配对。

入口：**设置 → NFC 设备交接与相机标签**。

1. 两台 Android 设备先分别配对同一台相机，并开启 NFC、解锁、保持操作页面前台。
2. 旧设备开启转交，新设备开启接收；第一次贴近读取，确认后第二次贴近提交。
3. 接管后检查设备列表定位，再检查原图 EXIF。旧设备暂停后不会自动恢复争抢相机；失败时检查两端后手动恢复。

发送端需要 NFC HCE，接收端需要兼容的读卡能力。普通标签不能暂停旧手机，金属机身附近建议抗金属可写 NDEF 标签。详见 [NFC 使用与验收说明](docs/audits/2026-11-06-nfc-handoff.md)。

## 版本与构建

版本采用 **`v<官方源码版本>+power.<修订号>`**；例如 `v1.7.2+power.1`、`v1.7.2+power.2`，同步官方新基础后为 `v1.7.3+power.1`。`power` 标记这条增强分支，不表示官方版本；正式版不使用 `beta`。Android `versionCode` 始终独立递增，签名保持不变。

[release.properties](release.properties) 是 Android 版本、主页和发布校验的共同来源。流程见[版本与发布规则](docs/versioning.md)。

```bash
git clone --recurse-submodules https://github.com/luoji12103/alpha-gps.git
cd alpha-gps
python3 scripts/release-metadata.py
# JDK 21、Android SDK 37.1、NDK 29.0.14206865
./gradlew -PdisableSentryUpload=true -PtargetAbi=arm64-v8a --max-workers=2 \
  :sharednew:testAndroidHostTest :app:testFossDebugUnitTest \
  :app:assembleFossRelease :app:lintFossRelease :app:compileGplayDebugKotlin
# 未提供签名环境变量时，release APK 为未签名构建，不能冒充发布包。
```

主页为 Astro 静态站点，无客户端 JavaScript 或运行时 GitHub API 查询。Node 24：`cd website && npm ci && npm run build`。GitHub Pages 使用本项目路径 `/alpha-gps/`。

## 来源、兼容性与许可

原作者 **Saschl** 与上游贡献者。保留完整上游 Git 历史和 [GPL-3.0 许可](LICENSE)。本仓库的 Power 改动独立维护；fork 问题请在[本仓库](https://github.com/luoji12103/alpha-gps/issues)反馈。

- [上游项目与原始说明](https://github.com/Saschl/alpha-gps)
- [上游兼容机型报告](https://github.com/Saschl/alpha-gps#which-cameras-are-supported)：必须支持 Bluetooth location linking；Wi-Fi-only 机型不支持蓝牙定位。
- [Sony 刷新证据与过期策略](docs/audits/2026-11-04-sony-location-refresh.md)：Sony 未公布跨机型通用最大刷新间隔。
- [多设备自动协作设计（待实现）](docs/audits/2026-11-06-multi-device-handoff.md)
- [上游 Wi-Fi 架构](docs/wifi-architecture.md)

上游也提供 iOS、Google Play 和 F-Droid 版本；这些商店安装包不包含本分支新增的 Android 功能。源码中的 iOS 工程保留，本次不发布 iOS 构建。

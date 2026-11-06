# Android 再审计：定位恢复、日志开销和后续功能

后续更新：本报告对应 NFC 加入前的 `0f15734` 交付。用户随后明确要求的两种 NFC 功能已在 [NFC 手动交接与相机标签](2026-11-06-nfc-handoff.md) 中实现和记录；新增普通 NFC 权限，自动电量切换仍待实现。

审计日期：2026-11-06。仓库：Saschl/alpha-gps。本轮再次拉取上游，`main` 仍为 `8a79e1368fb94040465f8de731fec7c1d472c0d2`，最新公开 release 为 v1.7.1；当前源码版本为 v1.7.2 / 172。本轮基于上一轮交付 `f93b280` 继续修复，没有拿附件中的旧源码替换新版。

附件作为历史背景。当前要求是轻量、低功耗、稳定；Android 请求一分钟获取一次位置。多设备补充要求已确认：全部 Android、备用设备可以独立定位，根据电量和在线状态自动选择负责设备。历史文档中的运动识别等建议没有自动成为本轮要求。

## 本轮已经修复

### 定位注册失败后能够自行恢复

原路径中，相机完成握手后，如果定位服务注册失败或异步报告失败，会停止位置传输，却没有自己的恢复计时器。即使权限或定位提供者后来恢复，也可能要等相机重连或其他事件才重新尝试。这是“已连接但没有位置”的一个明确风险，不能据此断言用户所有缺 GPS 的照片都来自这一原因。

Android 现在按 **30 → 60 → 120 → 240 → 300 秒**重试，此后最多每 5 分钟一次：

- 仅在仍有就绪相机且允许传输时重试；断开最后一台、停用或服务结束时取消。
- 得到可用位置才重置退避，注册成功但一直取不到位置不算已经恢复。
- 平台提供者被禁用会通知共享层；Google Play 版同时观察 fused 和平台失败，忽略已经停用来源的迟到失败。
- 失败后清除旧样本，恢复后等待可用样本，不重发上一段会话的坐标。
- 只有注册失败才重试；正常注册后等待新位置、或旧样本超过 90 秒，不会循环重启 GNSS。

这不是精确唤醒机制。Android 深度休眠可能推迟重试；打开系统定位后也不保证立即取得首个样本。本轮没有新加 WakeLock、精确闹钟、传感器或主动扫描。

代码：`LocationTransmissionManager.kt`、`LocationTransmissionPolicy.kt`、`AndroidLocationPowerPolicy.kt`、`PlatformLocationSource.kt`、GPlay `LocationSourceFactory.kt`。

### 低日志级别真正减少共享层工作

原共享日志桥始终开启全部级别，即使用户选择 INFO / WARN，部分 DEBUG 消息仍会先构造字符串并进入平台日志路径，然后才在数据库日志树中过滤。

现在平台日志和共享桥都读取用户选择的阈值，在执行日志 lambda 前过滤；设置改变后刷新 KmLogging 缓存的级别标志。Android 数据库日志树也提前过滤。切换日志等级只替换 FileTree，保留用户已同意启用的崩溃报告树及其他树。

默认仍是上游的 INFO；没有强制关闭用户诊断。需要更少常规日志时可选择 WARN。本轮没有实测此改动带来的续航百分比。

## 继续保留的定位策略

| 项目 | 当前行为 |
| --- | --- |
| 手机采样请求 | 60 秒一次，平衡功耗；系统不保证严格按时回调 |
| Sony BLE 刷新 | 有有效位置时目标每 5 秒一次，不重新启动定位 |
| 相同坐标 | 允许在样本有效期内重复发送，静止时新样本也可相同 |
| 样本有效期 | 从真实采样时间起最多 90 秒；重复回调和发送不续期 |
| 无位置 / 过期 | 暂停刷新，等有效位置；不永久伪装成当前坐标 |
| 最后一台就绪相机断开 | 停止采样、刷新和恢复计时器，清空会话缓存 |

**Sony 官方指南没有公开统一的最大包间隔或失效秒数。** 5 秒是 AlphaGPS 上游既有发送节奏，属于兼容性选择；90 秒是本地样本年龄限制，不能作为相机失效时间。解释和协议参考见[上一轮 Sony 专项审计](2026-11-04-sony-location-refresh.md)。

## 审查到的其他改进机会

| 优先级 | 改进 | 收益与约束 | 本轮状态 |
| --- | --- | --- | --- |
| 高 | 多设备自动接管 | 备用机同时定位和连接相机，主手机可停止采样；必须解决交接、重连门禁和失联误判 | 已形成[实现方案](2026-11-06-multi-device-handoff.md)，尚未实现 |
| 高 | 每台相机的定位诊断 | 显示样本年龄、精度、最后一次 BLE 写成功时间、当前负责设备、不可用原因，便于查缺 GPS | 建议下一项开发；当前“正在传输”仅表示队列接受过位置包 |
| 中 | Wi-Fi 预览后台暂停 | 当前遥控界面退后台仍保留 Wi-Fi 和预览；可暂停预览而保留下载 / 控制会话 | 已确认路径；涉及现有行为，尚未更改 |
| 中 | BLE 握手失败的受控恢复 | 队列接受请求后若最终无法发起或超时，部分握手路径缺少统一恢复通知；应加一次有上限的恢复并防止重连风暴 | 已记录，尚未更改；不能把它等同于普通广播消失 |
| 较低 | GPS 专用构建 | 去掉 Wi-Fi 照片 / HEIF 转换可减小包体；需独立构建边界和验证 | 尚未制作；包体更小不自动等于后台更省电 |

HEIF native 库是在使用解码器时加载，没有证据表明仅安装这些库会让纯 GPS 待机一直耗电。当前 ARM64 包约 7.6 MiB，继续保留 R8 和资源压缩。没有为上述建议加入新 SDK、云服务或权限。

原有 Wi-Fi 取图、遥控和时区 / DST 同步已在上游实现，不作为本轮新增功能。继续保留相机广播消失不等于 GATT 断开的规则，以及连接高优先级的超时回落。

## 验证与边界

执行：

```bash
ANDROID_HOME=/home/luoji/Android/Sdk ./gradlew \
  -PdisableSentryUpload=true -PtargetAbi=arm64-v8a --max-workers=2 \
  :sharednew:testAndroidHostTest :app:testFossDebugUnitTest :app:compileGplayDebugKotlin

ANDROID_HOME=/home/luoji/Android/Sdk ./gradlew \
  -PdisableSentryUpload=true -PtargetAbi=arm64-v8a --max-workers=2 \
  :app:assembleFossRelease :app:lintFossRelease
```

共享层 **427** 项、Android **21** 项，合计 **448 项测试，0 失败 / 错误 / 跳过**。新增测试覆盖恢复退避及封顶、异步失败、关闭 / 断开取消、重复事件、有效样本重置、注册异常、正常断供不重复注册，以及降低和提高日志阈值时的惰性格式化。

FOSS release 构建、Google Play Kotlin 编译和完整 release lint 通过。lint **0 错误、18 警告**，比上一轮增加的两项是 Navigation3 依赖版本更新提醒，没有为消除此提醒而改动依赖。APK 使用同一本地持久签名，可覆盖本会话之前的本地版本；与官方发行签名不同。版本字段继续为 v1.7.2 / 172。

交付目录为 `/home/luoji/codex/artifacts/alpha-gps-20261106-recheck/`，APK、完整 / 增量补丁、校验值和原始验证日志见其中的 `BUILD-INFO.json`、`SHA256SUMS`。未连接手机和相机，没有安装实测、EXIF 回归、多设备交接或电流测量，不能宣称定位缺失率为零或节省特定比例电量。

建议先在同一手机、同一相机 / 固件完成锁屏 30 分钟、系统定位关闭再打开、相机关机再开、原图 EXIF 核对。系统定位恢复后留出当前退避间隔和首个位置的获取时间。多设备另按方案中的实机矩阵验证。

## 公开资料

- [Sony α6700：Reading location information from a smartphone](https://helpguide.sony.net/ilc/2320/v1/en/contents/202h_smartphone_location_info.html)：最多配对 15 个 Bluetooth 设备，位置同一时刻只链接一部手机；换手机前关闭旧手机的位置链接。
- [Android 后台 BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/background)：支持过滤的 PendingIntent 扫描；不建议安排周期扫描；CDM 对过滤和随机 MAC 有限制。
- [Android 蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)：Android 12 起扫描、广播和连接是不同权限。
- [AlphaGPS 上游](https://github.com/Saschl/alpha-gps)：本轮再次核对 main 与 release。

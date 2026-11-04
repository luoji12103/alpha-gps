# AlphaGPS 最新版复审与一分钟省电版本

## 基线和交付范围

- 上游：<https://github.com/Saschl/alpha-gps>。
- 拉取时最新发布版：v1.7.1；最新 main：`8a79e1368fb94040465f8de731fec7c1d472c0d2`（2026-11-01）。
- 本轮直接基于最新 main 工作，其 Android 版本字段为 **v1.7.2 / 172**。交付的是本地修改版，不是官方 v1.7.2 发布版。
- 分支：`audit/android-minute-power-20261104`。
- APK 对应的代码提交：`f6743f2f7b066ce1974e514c53100d2f1596b2eb`；之后的审计文档提交不改变程序。
- 附件只作为历史材料和需求背景。没有拿旧会话声称完成的补丁当作现有源码，也没有照搬旧版本问题结论。
- 延续历史会话最终选定的使用方式：Android 固定约一分钟更新，接受较粗定位，放弃快门反馈和运动传感器方案。

本轮检查 Android FOSS / GPlay 的定位、蓝牙、会话队列、伴侣设备、服务退出、开机恢复、日志和 Wi-Fi 启动边界。对共享逻辑运行 Android host tests；没有在 Linux 上构建 iOS，也没有连接 S24 Ultra 或 α6700。第三方原生解码库没有做完整安全审计。

## 最新版与旧会话的关键区别

上游已修复某些相机停止广播时被过早断开的问题。现在 CDM 的 BLE_DISAPPEARED **不能直接等同于应关闭 GATT**：相机可能还在正常传输。

因此保留普通断线时的 autoConnect 和“仍有连接就不因失去广播而退出”的策略；只有明确停用相机、关闭连接或销毁服务才关闭对应句柄。还补充了另一台相机仍在附近、或其 GATT 正等待重连时的服务保留判断。

新版 FOSS 已有相机 Wi-Fi 遥控、实时取景和照片传输，因此具有 INTERNET 等网络权限。不能沿用旧交接文档中“FOSS 无网络路径”的描述。本轮验证的是 **FOSS 运行时依赖没有 Sentry、GMS 和 Play SDK**，不是宣称整个应用没有网络能力。

## 复审发现及处理

| 优先级 | 最新 main 中的问题 | 本轮处理与验证 |
| --- | --- | --- |
| P0 数据完整性 | 周期重发绕过位置有效期，已过期坐标可能继续被赋予当前包时间 | 接收、立即发送、周期重发和队列实际执行都检查有效期；覆盖 30 秒边界、未来时间、非法坐标、时钟变化和恢复 |
| P1 | 上次会话的缓存及会话标记可能绕过新连接的新鲜度检查 | 最后一台相机离开时清空缓存和限频状态；覆盖重连和多相机 |
| P1 | Android API 33+ 将 GATT BUSY 201 当作已发起，等待不会到来的回调 | 区分 Started / Busy / Rejected；最多重试三次，退避 50 / 100 / 250 ms，总计 400 ms；覆盖 descriptor、断开、取消、过期和后续操作 |
| P1 | Fused 异步注册失败后共享层仍认为定位活跃，stop 后迟到的成功回调还能注册监听 | 失败流通知共享层；每次启动独立代次；迟到注册成功立即注销；GPlay 失败可回退平台提供者 |
| P1 | 停用单台设备只改内部状态，没有关闭 GATT；disabled + always-on 仍可能保留服务 | 明确停用时断开并关闭该句柄；过滤旧句柄回调；常驻计数只含启用设备；覆盖停用、另一台连接中和重连 |
| P1 | 已完成或失败的握手可能长时间维持 HIGH 连接优先级 | 成功、Error 和配对失败恢复 BALANCED；连接后另有一次性 60 秒恢复上限，销毁时取消 |
| P1 | connect 返回 false 未被处理，或无有效句柄却仍等待 always-on 重连 | 处理拒绝返回值；没有连接句柄时退出；always-on 启动也注册蓝牙关闭监听 |
| P1 | 开机 / 包替换恢复路径可绕过应用总开关 | receiver 和恢复命令均遵守总开关 |
| P1 | 路由把 all 转成 ALL，但关闭协调器只识别小写，导致关闭全部失效 | 大小写无关匹配；加入通过 ALL 关闭所有普通连接的回归测试 |
| P2 功耗 | 持续 5 秒高精度定位和独立 5 秒 BLE 定时重发 | Android 改为一分钟平衡精度请求；按新 fix 发送并限制每台相机发送频率 |
| P2 功耗 | 遥控未激活时每 3 秒持续探测 | 前三次快速探测后改为 30 秒；激活后停止；未启用遥控时不探测 |
| P2 I/O / 内存 | 日志逐条建协程、INSERT、COUNT；位置回调使用无限队列 | 日志采用 256 条容量和最多 32 条批量写入，约每 100 条检查保留量；位置使用 conflated 通道 |

另补了队列在发起操作前发布 pending 状态的顺序，防止适配器同步回调被漏掉。串行队列和多相机独立 lane 继续保留。

## Android 一分钟模式的准确含义

1. 首台相机完成握手后立即注册定位，优先接收 30 秒内的初始缓存；没有可用定位时等提供者返回。
2. GPlay 请求 BALANCED_POWER_ACCURACY，更新及最短更新间隔均为 60 秒；不再另发一次高精度 seed 请求。
3. FOSS 在 Android 12+ 优先系统 fused，使用平衡功耗请求；没有 fused 时先选网络定位，再在权限允许且网络提供者不可用时选 GPS。
4. 新 fix 到达就尝试发送，不额外等到下一个整分钟；每台相机两次入队至少间隔 60 秒。
5. 每次新 fix 重置一个 75 秒保底计时器。正常一分钟回调会在计时器触发前重置它；回调中断时最多补发一次仍有效的位置，不反复唤醒。
6. fix 年龄达到 90 秒就停止使用；未能取得新 fix 时，“正在传输”状态会清除。超期的排队包也不会再发起写入。
7. 最后一台就绪相机离开时，停止定位、采集、保底和过期任务，清空会话缓存。
8. Android 用 `SystemClock.elapsedRealtime()` 计时，包含深度休眠，避免用排除休眠时间的计时源导致锁屏后一分钟限频迟迟不放行。

60 秒是请求间隔和应用发送下限，Android 可延迟或合并回调；这不是“GNSS 每分钟只开机一次”的保证。GPS 单提供者情况下仍可能有较高耗电。iOS 沿用原有请求与重发节奏，只共享 correctness 修复。

不引入新运行时依赖、运动识别权限、位置上传或新的后台扫描器。遥控快门、相机时间同步和 Wi-Fi 命令继续通过原串行队列执行，不受一分钟 GPS 限频影响。

## 连接与待机功耗判断

| 状态 | 实际行为 | 主要开销 / 边界 |
| --- | --- | --- |
| 无就绪相机 | 定位停止，无 5 秒定位定时器 | CDM 系统观察本身仍可能有系统侧成本 |
| 相机离开且所有链路断开，always-on 关闭 | CDM 报告离开后停止服务 | 系统事件延迟决定退出时机，不能承诺相机关机后固定几秒退出 |
| 相机仍在附近但暂时断线 | 保留 autoConnect 机会 | 不增加应用定时扫描；CDM 一直不报告离开时仍可能保留等待服务，以维持上游重连行为 |
| 相机就绪 | 约一分钟定位 + BLE 常规连接 | 主要成本仍是定位硬件和持续 BLE 链路；减少写包不等于无线电只工作一分钟一次 |
| always-on 开启 | 按设置保留等待重连的句柄和前台服务 | 相机长时间关闭时通常不划算；无就绪相机仍不采集位置 |
| Wi-Fi 遥控 / 实时取景打开 | 主动维持相机网络和预览解码 | 可能高于 GPS 分享功耗；仅在用户进入该功能并连接后启动 |

后台自动连接依赖已配对、设备启用、CDM 观察、蓝牙和定位授权，以及系统允许后台启动。应用没有自行实现“每 N 秒扫描一次”的重连循环。系统发现延迟和 BLE 实际连接间隔需要手机和相机实测。

## 自动验证

最终完整运行：

```bash
ANDROID_HOME=/home/luoji/Android/Sdk ./gradlew \
  -PdisableSentryUpload=true -PtargetAbi=arm64-v8a --max-workers=2 \
  :sharednew:testAndroidHostTest :app:testFossDebugUnitTest \
  :app:assembleFossRelease :app:lintFossRelease \
  :app:compileGplayDebugKotlin \
  :app:dependencies --configuration fossReleaseRuntimeClasspath
```

- 共享层 412 项、Android 20 项，合计 432 项单元测试，0 失败 / 0 错误 / 0 跳过。
- FOSS release 构建、完整 release lint、GPlay Kotlin 编译通过。lint 仍有非阻断警告，详见交付的 lint 文本；没有禁用错误检查。
- 修正了服务 stopSelf 的错误蓝牙权限标注，并补齐上游空的波斯语系统通知资源，使完整 lint 可通过。
- 虚拟时钟验证：持续一小时、每分钟一个 fix，含首发共 61 次入队；正常回调不会再触发额外保底重发。
- 日志压力测试：1000 条瞬时输入在容量限制下保留 256 条，8 次批量 INSERT、2 次 COUNT；验证清空顺序、启动清理和存储错误恢复。
- APK 校验：ZIP 完整、仅 arm64-v8a、6 个原生库均为 AArch64 ELF；ZIP 及 ELF LOAD 均支持 16 KiB 对齐；签名验证成功。
- FOSS runtime dependency graph 没有 `io.sentry`、`com.google.android.gms` 或 `com.google.android.play`。

工具链保持上游要求：Gradle 9.8.2、JBR 21、Android 37.1 / 37.0、Build Tools 37.0.0、NDK 29.0.14206865、CMake 3.22.1。native 子模块固定为上游提交；构建安装未把 SDK 或签名私钥加入仓库。

日志缓冲满时会舍弃新诊断日志，避免故障期间无限占用内存；这不涉及照片数据。BLE 操作和 transport 事件通道没有机械改成 conflated，以保留握手、快门和释放命令的顺序。

## APK、签名及源码

交付文件为 `AlphaGPS-v1.7.2-minute-arm64.apk`：FOSS **release** 配置，已启用 R8 和资源压缩，非 debuggable，最低 Android 8.0（API 26），目标 API 37。精确大小和摘要见同目录 `BUILD-INFO.json`、`SHA256SUMS`。

使用独立的本地持久签名，证书 SHA-256：

```text
916e9710a71375f4beaccea3d4567246f0ad2e12a14f35ef0576298e9cf6892b
```

私钥及口令保存在 `/home/luoji/.local/share/alpha-gps-signing/`，不在补丁或 APK 交付资料中。后续本地更新需保留并复用此目录。它与官方商店、F-Droid 或旧调试包签名不同，不能直接覆盖那些安装；卸载旧应用会清除配置和关联，安装后需重新配对。

七个实现提交分别处理：

| 提交 | 内容 |
| --- | --- |
| `9f53643` | fix 有效期、缓存、排队过期和 source failure 契约 |
| `70e61b6` | GATT BUSY 有限退避和队列完成顺序 |
| `a57522f` | 显式释放句柄、setup 优先级上限、多相机生命周期 |
| `1baed21` | Android 一分钟模式、休眠计时、异步定位清理与回退、遥控探测退避 |
| `d386bbb` | 日志容量、批处理和清空顺序 |
| `fed314c` | 可选 ARM64 构建入口、补齐 lint 资源 |
| `f6743f2` | 开机 / 升级尊重总开关、ALL 关闭指令 |

完整 mailbox 补丁可在基线上应用：

```bash
git clone --recurse-submodules https://github.com/Saschl/alpha-gps.git
cd alpha-gps
git checkout -b audit/android-minute-power 8a79e1368fb94040465f8de731fec7c1d472c0d2
git am /path/to/AlphaGPS-latest-minute-power.patch
```

若提交 upstream，建议先投通用 correctness 和生命周期修复；一分钟默认模式是本用户的精度 / 功耗取舍，适合另一个 PR 讨论。没有向远端推送或创建 PR。

## 实机验收与剩余限制

没有测得真实电流、mAh 或续航提升百分比。请求次数和单元测试包数不能换算成实际节电比例。

| 试验 | 观察内容 |
| --- | --- |
| 相机关闭、锁屏 8 小时，always-on 关闭 | 应用服务是否退出，定位注册是否归零，CPU / 电池用量 |
| 相机连接、静止锁屏 2 小时 | 首次定位、约一分钟后的 EXIF、断线次数、定位 active 时间、手机与相机耗电 |
| 步行 1 小时 | 分钟坐标精度是否符合用途，照片 GPS 缺失及误差 |
| 室外→地库→室外 | 90 秒后应用不再发送旧 fix，恢复后继续；尤其检查相机自身缓存旧坐标的行为 |
| 快速开关相机 20 次 | 系统发现与重连延迟、HIGH 是否及时恢复、是否残留句柄和定位监听 |
| 两台相机、停用其中一台 | 另一台已连接 / 正在连接都能继续；停用者无残留 autoConnect |
| 禁用应用后重启 / 更新 | 不恢复 always-on 定位服务；重新启用后仍能正常配对和连接 |

可用只读命令记录：

```bash
adb shell dumpsys location
adb shell dumpsys activity services com.saschl.cameragps
adb shell dumpsys bluetooth_manager
adb shell dumpsys batterystats com.saschl.cameragps
```

仍需特别留意：

- 应用停止发送，不能撤回 α6700 已缓存的位置；相机可能继续将上一坐标写进照片。必须通过 EXIF 实拍确认。
- 界面“正在传输”仍表示位置包被队列接受，不表示相机已经确认收到，更不表示照片 EXIF 已写入。
- 75 秒保底补发后立即恢复的新 fix 仍受每台相机 60 秒限频约束。这是固定分钟模式的取舍，不保证移动场景下每张照片的位置实时。
- HIGH 优先级驻留已设上限，但这不等于所有失败握手都有新的自动恢复策略；队列操作 timeout 后的相机恢复行为仍需实测。
- Android 16+ 的 BT_CONNECTED / BT_DISCONNECTED 与 BLE presence 事件仍需用设备日志核对，本轮保留上游事件处理，避免改变刚修好的停止广播场景。
- BLE 操作及 transport 事件通道仍有无界部分；若以后增加高频遥控或后台任务，应单独设计背压及命令优先级，不能直接丢弃有序控制事件。
- FOSS 保留 Wi-Fi 权限和本地相机通信；本轮没有新增 GPS 服务器上传。GPlay 的 Sentry 仍按原有同意机制启用，发布的是不含该 SDK 的 FOSS 包。

日常可优先关闭不用的 always-on 和遥控功能，日志设为 WARN；相机上的关机联网和蓝牙遥控也按实际用途关闭。不要把省电模式当作已完成手机 / 相机硬件认证的版本。

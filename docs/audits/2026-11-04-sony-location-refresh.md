# Sony 定位刷新与一分钟取位置的兼容性复查

## 结论与证据边界

手机获取新坐标和向相机刷新坐标是两个独立节奏。可以在两次采样之间反复发送相同经纬度；静止时坐标本来就可能不变。不能把“一分钟获取位置”直接等同于“一分钟才发送一次”，也不能靠重发无限延长一个实际已经过期的样本。

本次查阅的索尼 α6700、α7R III 官方指南没有给出位置包最大发送间隔或统一失效秒数。**不能据此声称索尼保证 15 / 30 / 60 秒有效，也没有实机测出这些值。** 5 秒是恢复 AlphaGPS 上游原有的应用发送节奏，属于兼容性优先的选择，不是测得或厂商承诺的极限。不同机身、固件、锁屏调度和 BLE 阻塞仍需实际验证。

用户提到旧版本“时有时无”，未提供可复现日志。本轮找到了首轮本地版本中独立的风险：60 秒发送下限和 75 秒一次性补发未经相机验证；它们会留下长时间无刷新窗口。已修复此路径，但不能认定它是所有旧版本缺定位的唯一原因。

## 查阅资料（2026-11-04）

| 来源 | 可确认内容 | 不能由此推导的内容 |
| --- | --- | --- |
| [Sony α6700：Reading location information from a smartphone](https://helpguide.sony.net/ilc/2320/v1/en/contents/202h_smartphone_location_info.html) | 使用 Bluetooth 获取位置并写入拍摄记录；相机关闭较久后重开可能不能立即链接；只能与一部手机链接位置；无法取得位置时不记录 | 没有给出最大刷新间隔、位置缓存失效秒数或定时包保证 |
| [Sony α7R III：Loc. Info. Link Set.](https://helpguide.sony.net/ilc/1710/v1/en/contents/TP0001662032.html) | 区分 Bluetooth 已连接和可取得位置两个状态；该机型指南要求关闭 Bluetooth Rmt Ctrl 才能链接位置 | 不能把此遥控限制直接套到所有较新机型；也没有统一超时秒数 |
| [AlphaGPS 原始发送节奏](https://github.com/Saschl/alpha-gps/blob/8a79e1368fb94040465f8de731fec7c1d472c0d2/sharednew/src/commonMain/kotlin/com/sasch/cameragps/sharednew/bluetooth/SonyBluetoothConstants.kt) | `LOCATION_UPDATE_INTERVAL_MS = 5000L`，上游定时发送缓存位置 | 上游常量不是 Sony 公开的最大允许间隔 |
| [AlphaGPS #79](https://github.com/Saschl/alpha-gps/issues/79#issuecomment-3837726812) | 曾出现锁屏后位置回调不按请求间隔到达，作者在 v1.2.15 加入显式重发；后续也发现取不到位置的独立问题 | 不是本次旧版本问题的复现，也不能证明所有缺失来自发送频率 |
| [Python 参考实现](https://github.com/anoulis/sony_camera_bluetooth_external_gps/blob/931f87caa00853718c212bb2591b2f115e010936/sony_geotagging.py#L223) | 支持固定经纬度循环发送；每次重新构建时间字段 | 代码是逆向参考，不能提供所有 Sony 机型的保证；没有照搬其缺定位时无限发送固定坐标的行为 |
| [逆向包格式](https://github.com/whc2001/ILCE7M3ExternalGps/blob/219a05745bdcbbe8ede1442b9ba356d6fd8e1e29/PROTOCOL_EN.md)、[ESP32 发送实现](https://github.com/whc2001/ILCE7M3ExternalGps/blob/219a05745bdcbbe8ede1442b9ba356d6fd8e1e29/src/ILCE7M3ExternalGps.ino#L150) | 坐标与 UTC 时间为独立字段；参考实现约每秒发送；新固件先写 DD30，再写 DD31，之后写 DD11 | 项目已弃用且作者明确称仅供参考；1 秒间隔不是已测最大值；没有复制其源码 |

最新版 AlphaGPS 的握手已按 DD30 → DD31 的顺序启用位置，再进入位置发送；继续保留该流程，以及上游“停止广播不等于 GATT 已断开”的修复。这里不引入按刷新周期重复握手、主动重连或持续高优先级连接。

## 当前实现

| 项目 | 行为 |
| --- | --- |
| 手机采样 | Android 仍请求每 60 秒一次的平衡功耗位置；没有新增高频 GNSS 请求、运动传感器或精确闹钟 |
| 初始位置 | 握手完成后启动提供者；平台初始缓存最多 30 秒；没有位置就等待，不编造坐标 |
| 相机刷新 | 有有效位置时约每 5 秒重建并入队一个 Sony 位置包；两次采样间允许使用相同经纬度 |
| 采用新坐标 | 首次位置或断供恢复立即尝试发送；其他新样本替换缓存，由下一次 5 秒刷新发送，不因精度较粗一直保留旧坐标 |
| 协议时间 | 保持上游每包使用当前 UTC、时区 / DST 的行为；实际采样时间单独用于判断新鲜度，不会被协议时间覆盖 |
| 样本有效期 | 最多 90 秒，自真实采样时刻起算；Android 单调计时包含深度休眠；重发和重复缓存回调不会续期 |
| 排队包有效期 | 不超过下一次刷新应到的时刻，且不能超过原样本有效期；到实际发起 BLE 写入时再次检查，堵塞后不集中发送过时包 |
| 定时器 | 位置回调不会反复重置 / 推迟周期发送；无初始位置首次检查后暂停刷新，样本过期也暂停，等新位置恢复 |
| 会话结束 | 最后一台就绪相机断开 / 停用时停止采样和刷新，清空缓存；另一台仍就绪时继续 |

90 秒是本地防止错误地理标记的样本年龄上限，**不是 Sony 的相机超时**。如果 Android 一分半仍不给新位置，宁可让应用显示位置不可用，也不把陈旧坐标永久包装成当前坐标。应用不能撤回已经进入相机自身缓存的数据。

5 秒刷新只发送小的 BLE 位置包，不会重新调用定位采样。它比首轮每分钟发送有更多写入和定时唤醒；是否省电仍由位置提供者、系统调度和常驻 BLE 链路共同决定，不能由包大小推算续航。没有添加 WakeLock 或精确唤醒来强行保证锁屏后严格每 5 秒执行，因此这是目标节奏，不是 Android 实时保证。

## 自动验证

新增 / 改写回归测试验证：持续一小时每分钟提供一个样本、坐标始终相同，首发加每 5 秒刷新共 721 次；频繁位置回调不会推迟刷新或额外触发发送；新坐标在下一次刷新采用；无初始样本、断供、过期、恢复和多相机会话状态均能正确处理。

另外验证协议时间在重发时变化，原样本不被续期；时钟后调并重复交付同一缓存样本仍按原单调时限过期；排队包分别受 5 秒及原样本剩余寿命约束；GATT 堵塞约 15 秒后只执行尚未过期的刷新，丢弃旧刷新。

完整验证命令：

```bash
ANDROID_HOME=/home/luoji/Android/Sdk ./gradlew \
  -PdisableSentryUpload=true -PtargetAbi=arm64-v8a --max-workers=2 \
  :sharednew:testAndroidHostTest :app:testFossDebugUnitTest \
  :app:assembleFossRelease :app:lintFossRelease :app:compileGplayDebugKotlin
```

共享层 418 项、Android 20 项，合计 **438 项测试，0 失败 / 错误 / 跳过**。FOSS release 构建、完整 release lint、GPlay Kotlin 编译通过；lint 为 0 错误、16 项非阻断警告。APK 继续采用本地持久签名、ARM64 FOSS release 和 R8 / 资源压缩，没有新增依赖或权限。

本次文件名为 `AlphaGPS-v1.7.2-minute-sony-refresh-arm64.apk`，交付目录为 `/home/luoji/codex/artifacts/alpha-gps-20261104-sony-refresh/`。复用首轮本地签名，可覆盖本会话首轮 APK；Android 版本字段仍为 v1.7.2 / 172。它与官方商店 / F-Droid 等签名不同。实际大小、摘要、签名校验及测试记录见同目录的 `BUILD-INFO.json`、日志和 `SHA256SUMS`。

仍未连接手机和相机，没有实测电流、机身有效期或照片 GPS 缺失率。

## 实机确认方法

1. 使用新版 APK，先等待相机显示“取得位置信息”，并确认 Creators' App / Imaging Edge Mobile 没有同时占用位置链接。
2. 在能取到新位置的地方锁屏测试至少 10 分钟，间隔数秒拍照。用原始 JPEG / ARW 的 EXIF 核对 GPS，不只看应用的“正在传输”（该状态仍表示队列接受过包）。
3. 步行跨越一次分钟更新，确认新坐标得到采用；室内断供超过 90 秒，应用应停止刷新并在恢复后继续。
4. 若需要求该 α6700 / 固件的实际极限，应先以 5 秒建立稳定基线，再分别测试 10 / 15 / 20 / 30 / 60 秒，并在每个间隔中连续拍照、记录相机位置图标和 EXIF。一次不断图标不等于所有照片都有有效 GPS；测得的界限也只适用于该机身 / 固件和环境。

出现缺失时要区分：尚未取得首个样本、位置提供者断供、BLE 已断开、相机位置功能关闭、另一应用争用，以及相机自身接收 / EXIF 行为。若 5 秒刷新仍失败，需要有相应时间段的定位回调和 GATT 写完成日志，才能进一步定位；本次没有把这些未复现问题都归因于单一超时。

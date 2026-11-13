# 版本与发布规则

## 命名与单一来源

正式版格式：`v<官方源码版本>+power.<修订号>`。例如：

| 情况 | 显示版本 | Android versionCode 示例 |
| --- | --- | --- |
| 首次正式版 | `v1.7.2+power.1` | 173 |
| 同基础继续修复 | `v1.7.2+power.2` | 174 |
| 合并官方新版本 | `v1.7.3+power.1` | 175 或更大 |

- `power` 是中性功能分支名，涵盖省电、稳定性和 NFC；不在版本中堆叠所有功能名，不使用维护者用户名。
- `+power.N` 是 SemVer build metadata，不表示 beta。GitHub 的正式 / 预发布状态单独设置，正式版明确发布为非 prerelease，并设置为 latest。
- SemVer 工具通常忽略 `+` 后内容的排序。Android 覆盖安装依赖**签名 + 单调递增 versionCode**，不能靠字符串排序。应用内更新说明单独支持 `power.N` 数字修订比较；Obtainium 使用仓库最新 Release 和固定 APK 文件名。
- `upstreamVersion` 使用实际基线源码的版本字段；`upstreamCommit` 固定完整 SHA。若基于官方未打 tag 的开发源码，必须在发布说明中明确，不得暗示该官方版本已经发布。
- Android 构建、主页和校验脚本均读取根目录 `release.properties`，避免三个地方版本不一致。未来首次预发布时再扩展 channel 格式与解析，不能直接把 beta 塞进当前只接受正式版的字段。

## 维护更新

1. 拉取官方分支与 tags，检查差异，再合并基础代码；保留官方 Git 历史。
2. 设置 `upstreamVersion` / `upstreamCommit`，同基础递增 `revision`；换基础时可从 1 重新计数。
3. 将 `versionCode` 提高到所有已分发 APK 的最大值之上，即使只是补打包也不能回退。当前首次正式版 173，历史试用包为 172。
4. 更新应用内 ReleaseNotesCatalog、中文 / 英文更新说明、README 与 docs/releases，运行新增功能所需回归。
5. 使用持久签名密钥。密钥及密码文件仅保存在维护者设备，并通过私密方式备份；不得加入 Git、Release、构建日志或普通 Actions artifact。

## 验证与签名发布

1. 工作区应干净，版本信息正确：`python3 scripts/release-metadata.py`。初始化子模块，按 README 运行自动测试、ARM64 FOSS release 构建、lint 和 GPlay Kotlin 编译。主页使用 Node 24 构建。
2. 对未签名 APK 使用 Android SDK `zipalign -P 16` 对齐，再使用 `apksigner` 和原持久密钥签名。密码通过 `--ks-pass file:<私密密码文件>` 传入（PKCS12 的 key 与 keystore 密码相同时省略 `--key-pass`，不要让同一文件被重复消费），避免密码出现在命令或输出中。
3. 检查 APK 实际 versionName / versionCode、ABI、非 debuggable、签名证书、ZIP 完整性以及 ZIP / ELF 16 KiB 对齐；确认与历史 APK 的签名一致。
4. Release 固定 APK 文件名 **`AlphaGPS-power-arm64.apk`**，方便主页和 Obtainium。版本记录在 APK、tag 与 BUILD-INFO.json 中。
5. 将源码推到本仓库 main，为**实际构建对应的 commit** 创建 tag。禁止移动已发布 tag，也不覆盖替换已下载的同版本 APK；需要修正时发布新 revision 和 versionCode。
6. 创建草稿 Release，上传 APK、`SHA256SUMS`、`BUILD-INFO.json` 和对应源码补丁；从 GitHub 下载文件核对摘要，再公开发布并标为 latest。功能说明必须区分已实现、自动验证和未完成的实机验证。
7. 验证主页上线，下载按钮指向本仓库 latest 的固定 APK。Pages 的 canonical、图标、OpenGraph 和 sitemap 均使用 `/alpha-gps/` 项目路径。

GitHub `Verify Android` workflow 自动验证，不接触签名密钥、不发布 APK。它替换了上游会尝试使用上游 Sentry / 签名 secrets 的发布流程。实际签名与发布由维护者本地完成；这样无需把现有持久私钥上传到 GitHub。

## 签名连续性

首次 Power 正式版沿用历史本地预览包的签名。公开证书 SHA-256：

```text
916e9710a71375f4beaccea3d4567246f0ad2e12a14f35ef0576298e9cf6892b
```

这是公开证书指纹，不是私钥。与官方商店 / F-Droid 签名不同；两条发布渠道之间不能直接覆盖。此分支保留原 applicationId，以兼容此前本地安装，故无法与同包名官方版并行安装。

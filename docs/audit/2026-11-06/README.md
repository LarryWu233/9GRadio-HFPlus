# 9GRadio 最新版源码完整性审计

审计日期：2026-11-06（Asia/Shanghai）。当时结论：**源码完整性门槛未通过，正式适配暂停。**

此文保留初始审计结论。随后项目明确改用公开提交 `002c355` 开发独立衍生版，解除等待完整 1.83 源码的前置条件；当前状态见根目录 README。

本次只增加审计脚本与证据，没有修改应用、驱动、降噪或调谐代码，没有安装、卸载或清除手机应用。尚未产出 HF+ 测试 APK，也没有完成接收、帧率或音质验收。

## 1. 固定的官方版本

| 项目 | 实际值 |
|---|---|
| 最新正式发布 | [9GRadio_v1.83](https://github.com/rkarikari/9GRadio/releases/tag/9GRadio_v1.83)，2026-11-03 发布 |
| 下载 APK 的清单版本 | `com.radiosport.ninegradio`，`1.83 / build83`，minSdk 26，targetSdk 35 |
| APK 大小 | 13,117,184 字节，与发布附件元数据一致 |
| APK SHA-256 | `c3c78d6c1ef4d84f96630cf65d5403abdcca522ed8bde35a7470c32a554d99a7` |
| 发布标签提交 | `625424bbb0cae43cfbce657607942177cd501178` |
| 当前 master 提交 | `002c355ab02069e96da26755a4169095bb25614e` |
| 公开源码构建版本 | `1.39 / build39` |
| `app` 最后修改 | `508392f3c2ebaaaa103ddb258ebb2506f96297d3`，2026-07-06，提交标题 `v1.39` |

**决定性证据**：master、1.83 标签提交与 1.39 提交的 `app` Git 树对象完全相同，均为 `da22ac0333b35900335e6bbea3b335b0539d475d`。这证明三个版本的整个应用目录逐文件一致，不只是某一处版本号过时。1.83 标签提交只更新 README，随后的两个 master 提交只更新图片。

APK 已通过 Android SDK `apksigner verify`；证书 SHA-256 为 `ccadb142793ac755c881a936f09fd7f140e7aade1ff856fae0f58b87c70ea8d2`。这是下载件的签名校验记录，不代表已通过独立渠道认证签名人。

## 2. 对照 APK 的功能差异

下列证据来自 APK 的 DEX **已定义类**，不是仅搜到类名字符串。对应文件和声明均在当前源码中检查过。类或库存在仅证明 APK 包含该实现，不代表本次已验证实际解码效果。

| 功能/实现 | 1.83 APK 中的证据 | 公开源码缺口 |
|---|---|---|
| 文件 IQ 回放 | `source.FileIqSource` | 缺文件及声明 |
| 原生 ACARS | `dsp.AcarsNative`、`libacarsdec.so` | 缺 JNI 接口、原生集成与目标 |
| 多通道 ACARS | `dsp.AcarsMultiChannelDecoder` | 缺文件及声明；旧版单路 `AcarsDecoder` 不能代表新版多通道实现 |
| AIS | `dsp.AisDecoder`、`libAIS-catcher.so` | 缺文件及原生集成 |
| 新版 RDS | `dsp.RedseaProcess`、`libredsea.so` | 缺该后端；旧版已有 RDS 类不代表与新版一致 |
| 新版 ADS-B | `dsp.ReadsbProcess`、`libreadsb.so` | 缺该后端；不是声称旧版没有任何 ADS-B 功能 |
| 9G 远程控制 | `remote.RigControlManager` 等 | 缺新版 remote 包 |
| MLAT / RDF 相关链路 | `mlat.MlatManager` 等 | 缺新版 mlat 包 |
| 卫星跟踪 | `data.SatTracker`、`libsgp4.so` | 缺跟踪器及原生集成 |
| SSTV | `dsp.SstvNative`、`libsstv_decode.so` | 缺文件及原生集成 |
| LRPT 原生组件 | `libmeteor_decode.so` | 缺对应共享库构建目标 |
| 原生 FFT | `dsp.NativeFft`、`libninegradio_fft.so` | 缺文件及原生集成 |
| GPU 加速 | `dsp.GpuAccel`、`libninegradio_gpu.so` | 缺文件及原生集成 |
| Kal 校准 | `kal.KalCalibrator`、`libninegradio_kal.so` | 缺新版 kal 包及原生集成 |
| 新版降噪配置 | `AudioDsp$NoiseReducer$NoiseProfile` 等 | 现有 `AudioDsp.kt` 存在，但缺新版配置声明；当前 NR 为整块功率估计与单一增益处理 |

APK 包含 5,276 个 DEX 类，其中应用命名空间下有 1,922 个（含内部类及生成代码，不能与源码文件数直接比较）。审计脚本与 SDK `dexdump` 独立解析得到的完整应用类清单一致。公开应用目录仅有 53 个 Kotlin 文件。

APK 在三个 ABI 中各包含 13 种 `.so`（含 C++ 运行库）。当前主 CMake 仅声明 `dsd_neo`、`ninegradio_dsp` 两个共享目标；扣除运行库后，有 **10 个 APK 原生库没有对应共享构建目标**。完整列表见 [inventory.json](inventory.json)。

因此，直接在这份旧源码上改 HF+ 或降噪，会与“保留最新版现有功能”的要求冲突。重新包装旧版、仅复用新版 `.so`，都不能补齐 Kotlin、资源、JNI 和构建集成的差异。

## 3. 已检查的源码来源

- 官方公开分支只有 `master`，指向上述提交。
- 已核对 API 返回的全部 9 个正式发布记录（1.50、1.73、1.74、1.77、1.79、1.80、1.81、1.82、1.83）；每个只上传 APK，没有独立完整源码附件。
- GitHub 自动生成的 Source code ZIP/TAR 使用对应标签树，1.83 标签的 `app` 树仍与 1.39 相同。
- 检查了搜索返回的 11 个相关派生仓库的默认分支。9 个具有完全相同的旧 `app` 树；另一个声明 1.52，仍缺上述新版模块；ADS-B Logger 是独立裁剪应用 `com.radiosport.skylog1090`。
- README 和仓库网页中未发现独立的完整应用源码下载地址。

来源快照见 [upstream.json](upstream.json) 和 [fork-search.json](fork-search.json)。上述检查不能证明作者的私有仓库、未公开源码或所有派生分支中不存在完整源码；本次未找到可验证的完整 1.83 来源。

## 4. 未修改版本的构建检查

构建验证**未通过，尚未进入源码编译**：

- 仓库缺 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`。配置指定 Gradle 8.13，本机缓存没有该版本。
- 为探查原始工程的环境要求，使用本机已有 Gradle 8.11.1 离线执行 `assembleDebug`，未修改构建文件。执行停止在 JVM 发现阶段：`Cannot find a Java installation ... Compatible with Java 21, JetBrains`。
- 项目 `gradle-daemon-jvm.properties` 要求 JetBrains Java 21，本机可用 Java 为 17。离线缓存还缺工程要求的 AGP 8.13.2 与 Kotlin Gradle 插件 2.2.0。
- 这是**工具链/依赖缺口**，不能表述为 Kotlin/C++ 编译错误，也不是源码完整性结论的依据。由于源码门槛已独立失败，未继续大量下载工具链并构建旧版。

详见 [checks.json](checks.json)。取得完整新版源码后，应先按其真实工具链建立可复现原版构建，不应沿用此旧工程的配置猜测新版依赖。

## 5. 手机检查结果

- ADB 可连接，型号 `M2007J1SC`。
- 实际系统 **Android 13 / API 33**，页大小 **4096 字节**。
- 未检测到包 `com.radiosport.ninegradio`。用户描述的 1.73/build73 尚不能在这台备用机上核实。
- 只读取了系统属性与相关包列表；没有提取用户应用数据，没有安装测试 APK，没有改变系统设置。

该机可用于后续 Android 13、USB 与流畅度测试，但不能代替计划中的 Android 16 真机验收；4 KB 真机也不能独立验证 16 KB 运行兼容性。本阶段未做 HF+ 接收或 USB 授权测试。

## 6. 继续实施所需材料

需要作者或其他可信来源提供与 1.83（若之后有更新则重新固定版本）对应的完整可构建工程，包括：

1. Kotlin/Java、资源、清单，以及全部原生组件与 JNI 桥接代码。
2. 对应的第三方依赖源码/可复现获取方式、CMake 配置与补丁。
3. 完整 Gradle 启动文件、准确的 JDK/SDK/NDK/AGP/Kotlin 版本和构建说明。
4. 可对应发布 APK 的提交或源码归档标识。

已准备 [给维护者的源码请求草稿](SOURCE_REQUEST.md)，尚未发送。取得源码后重新运行本审计；静态差异消失仅代表可进入人工复核，还需未修改原版构建、功能对照后才开始 HF+ 改造。

## 7. 复现

在项目根目录执行，替换 SDK 和设备路径：

```powershell
python scripts/audit_source_baseline.py `
  --apk build/audit/raw/9GRadio_v1.83_release.apk `
  --aapt C:/path/to/Android/Sdk/build-tools/36.1.0/aapt.exe `
  --output build/audit/recheck.json `
  --fail-on-gap
```

可选增加 `--adb <adb.exe绝对路径> --serial <ADB设备序列号>` 读取设备属性。脚本不联网，不安装软件；只写 `--output` 指定的报告。需要原始 Git 对象存在；默认固定 1.83 提交和 1.39 提交，可通过 `--release-ref`、`--legacy-ref` 显式替换。

`--fail-on-gap` 在发现结构差异时返回退出码 2；审计错误返回 1。不传此开关时，完成审计返回 0。没有检测到差异的状态仍是 `MANUAL_REVIEW_REQUIRED`，不自动认定源码完整。APK 只保存在 Git 忽略的 `build/audit/raw`，不提交二进制文件。

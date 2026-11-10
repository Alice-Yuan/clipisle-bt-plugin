# Android BT 原生依赖与许可来源

以下为迁移自 ClipIsle 主工程的历史 BT 依赖审计（核验日期：2026-11-08），原始许可文本与来源清单随本插件 APK 打包。文中涉及 Flutter、主工程实际解析版本及其验收路径的内容仅记录当时主工程状态，独立插件的依赖版本和制品须另行验证。`SOURCES.json` 列出原审计的固定版本来源。

## 版本依据

2026-11-09 补充 Tracker 订阅网络依赖：OkHttp 4.12.0 与 Okio 3.9.1，均为 Apache-2.0；完整原文分别为 `okhttp-LICENSE.txt` 和 `okio-LICENSE.txt`，来自固定官方版本。Okio 以 `releaseRuntimeClasspath` 的实际解析版本为准：已有 AndroidX DataStore 将请求版本 3.6.0 提升到 3.9.1。MockWebServer、okhttp-tls 仅用于 JVM 测试，不随应用打包。

依赖入口为 Maven `com.frostwire:jlibtorrent-android:2.0.15.3`。根据上游固定 [release/2.0.15.3 Dockerfile](https://github.com/frostwire/frostwire-jlibtorrent/blob/release/2.0.15.3/swig/Dockerfile)、[构建变量](https://github.com/frostwire/frostwire-jlibtorrent/blob/release/2.0.15.3/swig/build-utils.shinc)、[Android arm64 链接配置](https://github.com/frostwire/frostwire-jlibtorrent/blob/release/2.0.15.3/swig/config/android-arm64-config.jam) 核实如下，未使用会随主分支变化的版本推测。

| 组件 | 固定版本 / 修订 | 打包许可 |
| --- | --- | --- |
| jlibtorrent JNI / Java 包装 | 2.0.15.3 | jlibtorrent-LICENSE.txt，MIT |
| libtorrent | 2.0.15，`eb397173da6189dad52f008916c27419dde32faa` | libtorrent-LICENSE.txt 与 libtorrent-COPYING.txt，主许可 BSD-3-Clause 及上游完整子组件声明 |
| Boost | 1.88.0 | boost-LICENSE.txt，BSL-1.0 |
| OpenSSL | 4.1.0 | openssl-LICENSE.txt，Apache-2.0 |
| try_signal | `105cce59972f925a33aa6b1c3109e4cd3caf583d` | try-signal-LICENSE.txt，BSD-3-Clause |
| SWIG 生成的运行时辅助代码 | 4.5.1 | swig-runtime-LICENSE.txt、swig-COPYRIGHT.txt、swig-LICENSE-UNIVERSITIES.txt |
| LLVM / Android NDK C++ 静态运行库 | NDK r28c (`28.2.13676358`)，Clang 19.0.1 / `r530567e` | libcxx-LICENSE.txt、libcxxabi-LICENSE.txt、libunwind-LICENSE.txt、compiler-rt-LICENSE.txt，Apache-2.0 WITH LLVM-exception 及历史许可 |

`try_signal` 版本取自该 libtorrent 修订的 [deps 子模块](https://github.com/arvidn/libtorrent/tree/eb397173da6189dad52f008916c27419dde32faa/deps)，静态链接依据 [Jamfile](https://github.com/arvidn/libtorrent/blob/eb397173da6189dad52f008916c27419dde32faa/Jamfile)。OpenSSL 版本另外从本地 Maven AAR 四种 ABI 的二进制字符串核对，均为 `OpenSSL 4.1.0 23 Oct 2026`；上游构建使用自身 Dockerfile 的 OpenSSL armcap 修补，ClipIsle 未改动或重编该二进制。

Android 链接配置含 `-static-libstdc++`，因此纳入 C++ 运行库许可。LLVM 文件来自 NDK `clang_source_info.md` 声明的基础修订 `3b5e7c83a6e226d5bd7ed2e9b67449b64812074c`，源码链接见 SOURCES.json；libc++、libc++abi、compiler-rt 原文还与本地官方 r28c `toolchains/llvm/prebuilt/windows-x86_64/NOTICE` 精确比对吻合。libunwind 同基础修订许可一并保留。NDK 安装的 `source.properties` 明确标识 r28c；个别上游 shell 脚本中的旧 r26d 环境变量不作为实际下载版本依据。

## 声明范围

- libtorrent 的 LICENSE 含 puff/zlib、ed25519、SHA-1、SHA-256 等内嵌代码声明，同时包含非 Android 平台的声明。保留上游完整原文避免删漏，不表示所有平台组件都进入 Android 二进制。
- Android 配置选用 OpenSSL；不将未选用的 asio-gnutls 当作已打包依赖。OpenSSL 构建禁用 zlib；libtorrent 自带的 puff 声明仍保留在其 LICENSE 内。
- SWIG 编译工具自身不随 App 分发。上游 LICENSE 明确给予 `Lib`/`Examples` 中运行时支持代码另行使用、复制、修改、分发的许可；同时保留大学及作者声明。不能把工具本身的 GPL 标记误作整个 App 的许可。
- LLVM 编译器、NDK 工具和完整 SDK 不随 App 分发；这里只展示可能静态链接进 JNI 的运行库许可。
- 更新 jlibtorrent/AAR 时须重新核对固定构建源版本、完整许可及 ELF/ZIP 对齐；不能只替换版本号。原始文件保持许可原文，来源说明独立存放。

## 16 KB 验证

`tool/audit_android_native_alignment.py` 可读取已存在的 APK/AAR，核对所有原生库 PT_LOAD 对齐及 64 位地址/偏移 16 KB 同余，同时检查 APK 未压缩原生库的 ZIP 数据偏移。机器可读证据位于 `docs/acceptance/bt/native-alignment-final.json`，含制品 SHA-256、大小、文件时间及各库数据。

该检查证明给定制品的打包对齐，不替代真实 16 KB Android 设备上的装载/下载测试。AAR 的 32 位 ABI 仍为 4 KB 对齐；当前 APK 仅携带 arm64-v8a 和 x86_64，64 位检查单独判定。

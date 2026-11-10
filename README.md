# ClipIsle BT 插件

ClipIsle 的独立 Android BT 扩展。插件 APK 单独安装，包含 jlibtorrent、下载队列、Tracker 设置和后台传输；宿主通过插件声明发现功能，并提供导入种子入口。需要 Android 8.0（API 26）或更高版本，以及 arm64-v8a 或 x86_64 设备。

## 安装和使用

在 ClipIsle 的插件管理页面安装本插件的发行包，并核对页面显示的插件签名。启用插件后，按提示授权插件访问宿主；插件会显示宿主包名和签名指纹供核对。授权后可从宿主的插件入口导入种子文件或处理磁力链接，也可在插件设置中调整下载与 Tracker 选项。系统可能要求分别为插件授予通知或后台运行权限。

下载文件和任务数据存储在插件自己的应用目录。**卸载插件会删除这些私有数据**；需要保留文件时，请先从宿主导出。插件可以暂停或卸载，安装插件不会替代宿主本身。

1.0.2 需要支持分组页面与 `autoSaveAction` 的新版 ClipIsle 宿主。设置在宿主界面内直接修改并保存；开始和继续下载不再弹出插件自身的重复确认。Android 14 通过透明 Activity 衔接用户发起的后台传输，仍保留系统通知和首次授权。DHT 已连接也不保证每个磁力都能取得元数据；请求超时后会给出状态反馈，可稍后重试或导入种子。

## 本地构建和测试

准备 JDK 17、Android SDK（包括 API 36 平台与构建工具）及 Python 3。首次构建需联网下载 Gradle 和依赖。Windows 在项目根目录运行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

macOS 或 Linux 运行：

```sh
chmod +x gradlew
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Debug APK 位于 `app/build/outputs/apk/debug/`，仅供开发测试。CI 只执行 Debug 构建和单元测试，不持有签名密钥，也不自动发布。

## 发行构建

发行签名由维护者自行配置。在项目根目录创建被 Git 忽略的 `key.properties`，指定 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`；`storeFile` 可为绝对路径或相对于项目根目录的路径。不要提交此文件或密钥库。未配置签名时 Release APK 为未签名产物，不会自动使用 Debug 密钥。

构建完成后，针对实际 APK 验证编译后的插件元数据，再生成发行清单：

```sh
python check_apk_manifest.py PATH_TO_APK --aapt2 PATH_TO_AAPT2
python make_metadata.py PATH_TO_SIGNED_APK --url HTTPS_APK_URL --apksigner PATH_TO_APKSIGNER
```

第二条命令检查 APK 签名并从 APK 计算 SHA-256 和证书指纹，默认拒绝 Android Debug 证书。将签名 APK 和生成的 `clipisle-plugin.json` 作为发行附件。Android 14 及以上的用户发起传输、通知权限和后台限制仍需使用真实设备验证。

## 接口与安全

宿主通过 `com.clipisle.plugin.BIND` 和服务的 `com.clipisle.plugin.manifest` 元数据发现插件。插件使用 Messenger JSON 命令与宿主通信，并校验宿主 UID 和用户确认的签名；宿主签名变化后需要重新授权。种子通过用户授权的 content URI 读取。已完成且选中的文件通过只读 content URI 导出，宿主向其他应用分享时需另行授予 URI 读取权限。

## 许可

此项目本身尚未由权利人授予开源许可证，保留所有权利。APK 在 `assets/licenses/` 包含第三方许可原文、版权声明和来源资料，包括 jlibtorrent、libtorrent、Boost、OpenSSL、try_signal、SWIG 运行时代码、LLVM/NDK C++ 运行库、OkHttp 和 Okio。该目录中的审计记录来自原宿主工程，独立插件的依赖解析版本应在发行时另行核对。

# Vector 管理器

[英文文档（English）](README.md)

manager 应用采用 Jetpack Compose 编写，只有一个 Activity，通过 Binder 配置 root daemon。它本身不持有任何额外权限，
对设备的所有操作都通过请求 daemon 来完成。它替代了已被删除的 `:app` 模块。

本文档补充了代码无法独立表达的信息：框架依赖的约束边界，以及“错误静默失败”而不是“报错”的关键场景。其余细节请直接阅读代码。

## 寄生式运行模型

manager 通常以注入方式运行在 `com.android.shell` 中，而不是作为独立应用安装。其
`AndroidManifest.xml` 在运行时不会被注册，因此在运行时不存在 manifest 中声明的组件：无
`ContentProvider`，也就没有 `androidx.startup`、`InitializationProvider` 的自注册；无
`FileProvider`；也没有按应用级生效的语言 API，因为 `setApplicationLocales` 依赖已安装的包名。
语言覆盖通过 compose composition 阶段完成，这也是不需要重启即可生效的原因。

因此整个系统初始化必须是显式的，从 Activity 启动开始。该 APK 也会作为普通 app 安装用于开发调试，两个模式都必须能工作，
任何只假设单一运行模式的实现都会在另一模式下暴露 bug。

其内存归属为 `com.android.shell` 的进程内存。这是很多决策看起来“偏激”的根源：
日志读取器以字节偏移分页读取而非持有整个文件对象，模块扫描也采用缓存而非重复扫描。

## Binder 的注入时序

框架通过反射从注入后的 dex 中加载 `<managerPackage>.Constants`，并调用静态方法
`setBinder(IBinder)`。APK 本身不会调用此方法，所以 R8 里通过 `proguard-rules.pro` 保留了该方法。
一旦重命名该类或方法，运行时握手会直接断开，应用启动后仅会显示“未发现框架”，且无编译期报错。

时序并非固定：Binder 可能先于 Activity 到达，或 Activity 先于 Binder 创建。`ServiceLocator.attach()`
是幂等的，`bind()` 只是向 `StateFlow` 做一次普通赋值，因此两种顺序都安全。仓库层并非持有 Binder 本身，
而是收集这个 flow，Binder 后续重连或延迟到达时会重新读取最新状态，而不是留在旧数据上。

## 与 daemon 的通信

`ipc/DaemonClient` 会把每个 AIDL 调用包进 `runIpc`，迁移到 `Dispatchers.IO` 执行并返回 `Result`。
接口定义位于
`services/manager-service/src/main/aidl/org/matrix/vector/ipc/IManagerService.aidl`，它是每个调用语义的真相来源；
调用前请先阅读该接口文档。

Binder 上这里有两类高频易错点，且 AIDL 文档已明确说明：

- 代理在 daemon 不支持某次事务时返回默认值而不是抛异常，因此 `0`、`null`、空字符串可能是“真实返回值”
  也可能是默认值（见 `getProtocolVersion` 与 `ROOT_UNKNOWN`）。
- 调用成功并不代表执行成功：有些接口返回布尔值表示拒绝结果，若直接忽略该返回值，会把拒绝静默当作成功。

daemon 始终是最终真相源。出现“写入与读取不一致”时，通常是 daemon 异步缓存的读取值与数据库写入路径不同步。

## 日志

日志请统一使用 `Constants.TAG`。`daemon/src/main/jni/logcat.cpp` 会把以 `Vector` 开头的标签转入 daemon 的 verbose 流，
这些内容会进入日志界面，并在用户导出报告压缩包中保留。普通文件内置 tag 不会被采集。
日志约定、级别定义、哪些内容不应落日志里，均由 `Constants.TAG` 规范承载。

崩溃日志写入 `cacheDir/crash`，因为 `FileSystem.getLogs` 会在 manager 的两个运行目录下从该位置收集。

## 字符串

`res/values/strings.xml`、`strings_logs.xml` 与 `strings_store.xml` 已翻译为 18 种语言。
`crowdin.yml` 同时指向 manager 与 daemon；`manager/build.gradle.kts` 又会合并 `../daemon/src/main/res`，
因此两侧字符串名冲突会导致构建失败。

任何用户可见文案不应直接在 composable 中硬编码；必须翻译的标识以 `translatable=\"false\"` 标注。
构建时会扫描包含 `strings.xml` 的 `values-*` 目录并生成 `BuildConfig.TRANSLATIONS`，因此你新增语言时无需改代码。

若要更改文案含义，请新增 key，而不是修改旧 key 的文本；否则这 18 种语言会长期维持旧含义而不自知。

## 构建与运行

```sh
./gradlew :manager:assembleDebug   # 生成 APK
./gradlew :zygisk:zipDebug         # 打包包含 manager 的 module zip
./gradlew ktfmtFormat              # 格式化，CI 不会检查 ktfmt
```

版本号使用 `git rev-list --count refs/remotes/origin/master` 生成，因此分支构建与 master 构建可能共用同一数值。
`module.prop` 与状态页会携带 build stamp，用于设备上区分构建来源和内容，尤其在同一提交被多次构建时很重要：
CI 构建表现为 `93d66473-JingMatrix-Vector`，本地构建为 `93d66473`，未清理状态时会附带机器名后缀如 `93d66473+thinkpad`。

Debug 构建新增第二个 launcher activity，`src/debug` 中提供脚本化设备状态的演示模式，发布版不存在该入口，因此不能用于正式健康报告。
这意味着 `monkey -c LAUNCHER` 会随机命中两个入口之一：

```sh
adb shell am start -n org.matrix.vector.manager/.ui.MainActivity
```

仓库中没有测试源码集，CI 只跑 `zipAll`，绿标仅代表通过编译与打包。其余验证需在真机 daemon 环境下执行。

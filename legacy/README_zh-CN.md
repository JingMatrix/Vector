# Legacy Xposed API 实现

[英文文档（English）](README.md)

本文档说明 Vector 框架中 `legacy` 模块的架构与实现。`legacy` 子系统提供向后兼容层，实现经典的
`de.robv.android.xposed` API 命名空间，并将执行路径转发到现代 native ART Hook 引擎。

## 模块边界与拓扑

legacy 兼容层跨越多个编译边界，严格隔离 API 表层、Dalvik/ART 运行时与 native 执行环境。

- [legacy](.): 包含 Java API 表层（`de.robv.android.xposed.*`）、状态转换处理器（`LegacyDelegateImpl`）、反射缓存机制，以及资源/共享偏好覆盖逻辑。
- [xposed](../xposed): 管理独立 classloader（`VectorModuleClassLoader`）、AOT 去内联器（`VectorDeopter`）与依赖注入（DI）框架。
- [native](../native): 提供 JNI 桥接（`hook_bridge.cpp`、`resources_hook.cpp`）、并发 hook 注册表、栈上调用分发、内存内 DEX 生成与二进制 XML 改写流程。
- [daemon](../daemon): 具备特权权限的进程外组件，用于提供可访问的 SELinux 目录与跨进程文件共享上下文。

### 依赖注入与启动

在进程启动阶段，`Startup.initXposed` 在 `zygisk` 模块中被调用。该流程会实例化 `LegacyDelegateImpl`，并通过
`VectorBootstrap.INSTANCE.init()` 注入到 `xposed` 模块，建立 DI（依赖注入）契约。

`LegacyDelegateImpl` 实现 `LegacyFrameworkDelegate` 接口，作为唯一的执行转换边界，处理以下事件：

- 应用包加载事件（`onPackageLoaded`）
- System Server 初始化（`onSystemServerLoaded`）
- 原生 Hook 执行路由（`processLegacyHook`）
- 资源目录追踪（`setPackageNameForResDir`）

## 模块初始化

Legacy 模块在初始化阶段由 `XposedInit.loadLegacyModules()` 加载。框架会通过 `VectorServiceClient.INSTANCE.getLegacyModules()`
从 daemon 查询已启用 APK 的路径列表。

模块不会走标准 Android 机制加载。为防止通过 `ClassLoader.getParent()` 链路探测被检测到、并避免残留文件描述符，
`XposedInit.loadModule` 采用 `VectorModuleClassLoader`。该类加载器将模块 APK 直接映射到内存，在宿主应用 classpath 之外执行。

模块映射到内存后，框架会解析 APK 内两个约定 manifest 文件以初始化 Java 与 native hook：

1. `assets/xposed_init`：定义 Java 入口类。该类经 `VectorModuleClassLoader` 加载，并扫描实现 `IXposedMod` 的类，注册到内部回调数组：
    - `IXposedHookZygoteInit`：使用包含模块路径和 system server 状态的 `StartupParam` 立即触发回调。
    - `IXposedHookLoadPackage`：通过 `IXposedHookLoadPackage.Wrapper` 封装后加入 `XposedBridge.sLoadedPackageCallbacks`。
    - `IXposedHookInitPackageResources`：加入 `XposedBridge.sInitPackageResourcesCallbacks`，随后在 `XposedInit.hookResources()` 中触发 native 资源 hook。

2. `assets/native_init`：定义 native 库文件名，作为 [native hook 模块](https://github.com/LSPosed/LSPosed/wiki/Native-Hook) 入口。
   这些名称通过 `NativeAPI::recordNativeEntrypoint` 注册，在动态链接时被拦截。`native` 模块在
   [native_api.cpp](../native/src/jni/native_api_bridge.cpp) 中提供了无直接依赖核心符号的 inline hook 基础设施。

## 生命周期事件转换

`xposed` 模块管理 Android 生命周期事件（例如 `LoadedApk.createOrUpdateClassLoaderLocked`），并将 `LegacyPackageInfo` payload
发往 `LegacyDelegateImpl`。

`LegacyDelegateImpl.onPackageLoaded` 会将现代 payload 转成经典 Xposed 格式。它构造
`XC_LoadPackage.LoadPackageParam`，字段包括：`packageName`、`processName`、`classLoader`、`appInfo`、`isFirstApplication`。
该参数对象交由 `XC_LoadPackage.callAll` 分发到 `sLoadedPackageCallbacks` 并执行模块回调。

系统服务进程属于特殊场景。`LegacyDelegateImpl.onSystemServerLoaded` 会手动把 `android` 添加到 `loadedPackagesInProcess` 集合，
并构造一个 `processName` 为 `system_server` 的硬编码 `LoadPackageParam`。

## 执行路由与方法 Hook

方法 hook 流水线包含：通过反射定位目标可执行体、通过去内联保证 ART 兼容、注册 JNI trampoline，以及调用期状态管理。

### 结构化反射缓存

Legacy 模块大量依赖反射定位目标方法与字段（如 `XposedHelpers.findAndHookMethod`）。在 `XposedHelpers` 内部实现了结构化缓存机制，
查询包装成 `MemberCacheKey` 派生类（`Method`、`Constructor`、`Field`）。这些 key 的哈希由对象身份与结构属性（类引用、参数数组内容、精度标志）计算，
并存于 `ConcurrentHashMap`（`fieldCache`、`methodCache`、`constructorCache`），实现高频反射命中的零分配缓存命中。

### AOT 去内联

现代 Android Runtime 会大量对短方法进行 AOT 内联，导致被内联的方法 hook 时 JNI trampoline 被绕过（调用方直接执行内联机器码）。

为解决该问题，`xposed` 模块实现 `VectorDeopter`。在初始化或应用加载时，
`VectorDeopter.deoptMethods()` 会读取已知内联方法清单
[VectorInlinedCallers](../xposed/src/main/kotlin/org/matrix/vector/impl/core/VectorInlinedCallers.kt)，
对每个目标调用 native 命令 `HookBridge.deoptimizeMethod`，使 ART 丢弃该目标的方法编译码流并恢复解释执行（`lsplant` 的
`ClassLinker::SetEntryPointsToInterpreter`），从而确保 JNI trampoline 的执行边界不被破坏。

### Native Hook 注册表与执行状态转换

Hook 注册由 `XposedBridge` 路由到 native 层，在 [hook_bridge.cpp](../native/src/jni/hook_bridge.cpp) 进行落地。
native 层管理并发全局注册表追踪已 hook 的可执行体与回调列表。
当 hook 方法触发时，native 引擎会暂停标准执行并将控制权返回 `xposed`，后者按 `LegacyFrameworkDelegate.processLegacyHook` 调用。

`LegacyDelegateImpl` 再将现代执行状态（`OriginalInvoker`）转为 legacy 规格，包入 `LegacyApiSupport`，并执行如下流程：

1. 正向遍历所有已注册 `XC_MethodHook`，执行 `beforeHookedMethod`。
2. 检查任一模块是否调用了 `setResult()` 或 `setThrowable()`；若未跳过原方法，则指示 `OriginalInvoker` 执行 native 原生逻辑。
3. 反向遍历回调，执行 `afterHookedMethod`。若下游模块在执行中抛异常，`LegacyApiSupport` 捕获异常并恢复原始缓存结果/异常，避免主机进程被非受控异常终止。

## 资源 Hook 子系统

资源 Hook 允许 legacy 模块在运行时替换应用资源、布局与字符串。由于 Android 在资源查询和 XML 解析上有大量 native 层优化，
该子系统需同时结合框架级注入、动态类生成与二进制 XML 的直接内存改写。

为拦截资源查询，系统会将默认 `Resources` 替换为自定义 `XResources` 子类。初始化框架时，
`XposedInit.hookResources()` 会 hook Android `android.app.ResourcesManager`，覆盖 `createResources`、`createResourcesForActivity`（Android 12+）和旧版本 `getOrCreateResources`。
当应用请求新的资源对象时，回调会执行 `cloneToXResources()`，通过 `HiddenApiBridge.Resources_setImpl` 提取并复制底层 `mResourcesImpl`，
再将新构造的 `XResources` 注入 OS 内部追踪数组（如 `mResourceReferences` 或 `ActivityResource` 结构），并用 `WeakReference` 包裹避免泄漏。

### 动态类层次生成

要有效拦截资源查询，框架必须以 `XResources` 和 `XTypedArray` 替代系统资源对象。
但若直接将 `XResources` 继承 AOSP 的 `Resources`，在 OEM 深度修改的运行时环境中常出现致命 `ClassCastException`。

为解决这一运行时多态问题，框架动态生成中间类层。

初始化时，`legacy` 模块中的 `XposedBridge.initXResources` 会读取系统资源与 TypedArray 的真实运行时类，
然后调用 native 桥 `ResourcesHook.makeInheritable` 去除这些 OEM 类的 `final` 修饰，允许被继承。
随后调用 `ResourcesHook.buildDummyClassLoader`。native 实现借助 `dex_builder` 库在内存 buffer 中直接构建 DEX，
生成 `xposed.dummy.XResourcesSuperClass` 与 `xposed.dummy.XTypedArraySuperClass`，并把它们的父类动态设为检测到的 OEM 类。
该 buffer 通过 `dalvik.system.InMemoryDexClassLoader` 加载到运行时。最后，`legacy` 模块通过覆盖 classloader 的 parent 字段，
让其指向该内存中的 dummy classloader。
编译期 `XResources` 只声明继承 `XResourcesSuperClass` 的桩类；运行期当 Dalvik/ART 解析 `XResources` 时，
处理后的 classloader 链会提供正确的动态 dummy 类，使其安全继承厂商专有方法与字段，顺利通过内部类型检查。

在联想 ZUI 设备上，OEM 修改了 `obtainTypedArray` 实现，改为从 `android.app.ActivityThread.sCurrentActivityThread`
读取设备配置。由于 Zygote 启动时该字段为 null，会触发致命 `NullPointerException` 导致开机崩溃。
legacy 模块通过反射构造一个空的、未初始化的 `ActivityThread` 对象，注入该静态字段，调用 `obtainTypedArray` 后在 finally 块中立即清空。

### 替换缓存与 native 二进制 XML 改写

高频渲染路径（如 `getDrawable`、`getColor`）若每次都走标准哈希表查询替换，容易产生大量锁竞争影响 UI 线程。
为此 `XResources` 使用无锁位图缓存实现 O(1) 快速判断，再访问主 `sReplacements` 映射。

- `sSystemReplacementsCache`：静态 256 字节数组，跟踪框架资源 ID（小于 `0x7f000000`）。
- `mReplacementsCache`：128 字节数组，跟踪应用级资源 ID（大于等于 `0x7f000000`）。

位图缓存是高性能拒绝路径，在未命中的资源场景下先返回 `null`，避免获取 `sReplacements` 全局锁。
在注册阶段，框架将资源 ID 映射到字节数组索引（利用 Type/Entry Index 熵），并用该 ID 的低三位设置特定位。
`getReplacement` 读取时先做位运算，如果位为 0 则不加锁直接返回 `null`。该 O(1) 检查使大部分请求绕过锁竞争，保障 UI 线程性能。

应用通过 `LayoutInflater` inflate 布局时，Android 会解析 AAPT 二进制 XML，并在 native 的
`android::ResXMLParser` 中执行。标准 Java Hook 无法拦截该 parser 内部 ID 解析逻辑。
为注入模块布局，框架在内存中改写二进制 XML 树。

1. 在 [resources_hook.cpp](../native/src/jni/resources_hook.cpp) 的 `PrepareSymbols` 中，使用 `ElfImage` 在内存解析 `libandroidfw.so`，
   解析未导出/重整后的 C++ 符号：`android::ResXMLParser::next`、`restart`、`getAttributeNameID`，并缓存到全局函数指针。
2. 若待请求布局未命中缓存，`XResources` 取出 native 指针（`mParseState`）并传给 JNI 桥 `rewriteXmlReferencesNative`。
   native 侧将 `jlong` 转回 `android::ResXMLParser*`，循环执行 `ResXMLParser_next`。
3. 当 parser 遇到 `android::ResXMLParser::START_TAG` 时，读取属性数并遍历属性；每个属性先取 `attrNameID`（通过缓存的 `getAttributeNameID`），
   若 ID 位于应用包命名空间（`0x7f000000`），会通过 JNI 调 `XResources.translateAttrId` 查询 Java 层是否有替代值。若返回替代 ID，
   native 层会直接修改 parser 内存中的二进制树（`mResIds[attrNameID] = attrResID`）执行内存内替换；同样逻辑也用于属性 value 的 `translateResId`。
4. 解析到 `END_DOCUMENT` 时，native 循环结束并调用 `ResXMLParser_restart`。随后 native bridge 返回后，Android 框架继续布局膨胀过程，
   但已解析的是经改写的内存树，故模块替换的布局 ID 被正确使用。

## SharedPreferences 与 SELinux 边界

经典 Xposed API 曾依赖 `Context.MODE_WORLD_READABLE`，允许目标应用直接读取模块在
`/data/data/<package>/shared_prefs/` 下的配置文件。自 Android 7.0 起使用该 flag 会抛 `SecurityException`。
而现代 SELinux 又严格隔离应用目录，无法凭 Unix 权限遍历跨进程目录。

为在不破坏稳定性的前提下恢复 `XSharedPreferences`，框架通过 out-of-process daemon 与运行时路径重定向实现绕过。

`daemon` 以特权身份运行，并为模块配置共享创建专用安全目录。解析模块目录时，daemon 会执行
`setSelinuxContextRecursive` 设置
[u:object_r:xposed_data:s0](../zygisk/module/sepolicy.rule) 上下文。
此上下文在标准应用域中可读性更广。随后 `Os.chmod` 设置 `755` 权限，并调整目录所有者，最终形成模块与目标应用都可合法访问的文件系统桥接区。

### 拦截与重定向

为了透明使用该安全区，`legacy` 模块会在模块自身的 UI 进程中拦截配置保存逻辑。
应用加载时，`LegacyDelegateImpl` 通过 `VectorMetaDataReader` 解析模块 APK 元数据。
若 `xposedminversion` 大于 92 或声明了 `xposedsharedprefs`，框架触发 `hookNewXSP` 并向
`android.app.ContextImpl` 注入两个关键 Hook：

1. Flag 擦除：Hook `checkMode`，若出现 `MODE_WORLD_READABLE` bit，则将 hook 抛出的异常置空，抑制 `SecurityException`。
2. 路径重定向：通过 `XC_MethodReplacement` Hook `getPreferencesDir`，返回 daemon 提供的安全区路径
   `VectorServiceClient.INSTANCE.getPrefsPath`，而非标准隔离目录。

当模块保存 `SharedPreferences` 时，Android 框架会自动把 XML 写入带 SELinux 兼容上下文的安全区目录。

### 文件 I/O 与 IPC 绕过

当目标应用挂钩并实例化 `XSharedPreferences` 时，框架会按目标 API 级别决定路径；对现代模块，它会直接跳过旧的 `/data/data`
路径并映射到安全区。

在原始 [Xposed 框架](https://github.com/rovo89/XposedBridge) 中，读时绕过 SELinux 依赖同步 BinderService 或 ZygoteService 提供的 native root；
Vector 中已移除了这些 IPC。由于 daemon 已预先为安全区设置可访问上下文，目标应用进程可直接读取文件。SELinuxHelper 无条件返回 `DirectAccessService`（BaseService 实现）。
该服务仅作为结构性兼容层存在，以保持 XSharedPreferences 内部缓存逻辑，并直接使用 `FileInputStream`/`BufferedInputStream` 做本地读，无额外 IPC 开销。

考虑到广播或 ContentProvider 对跨进程偏好追踪过于显眼，`XSharedPreferences` 实现了进程内文件监听来处理实时更新。
当注册 `OnSharedPreferenceChangeListener` 时，框架会启动内部 daemon 线程（`sWatcherDaemon`），借助
`java.nio.file.WatchService`（Linux inotify 抽象）监听安全区目录。线程阻塞在 `sWatcher.take()`，当目标 XML 收到
`ENTRY_MODIFY` 或 `ENTRY_DELETE` 事件后，先校验文件 hash，再将 legacy 偏好变更回调通过 native 路径分发给注册监听器。

## 开发者参考

面向 legacy Xposed API 的模块开发与调试，可参考以下官方文档：

- [Xposed Development Tutorial (rovo89)](https://github.com/rovo89/XposedBridge/wiki/Development-tutorial)
- [LSPosed New XSharedPreferences Mechanism](https://github.com/LSPosed/LSPosed/wiki/New-XSharedPreferences)

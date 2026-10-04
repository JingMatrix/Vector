package org.matrix.vector.daemon.data

import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SELinux
import android.os.SharedMemory
import android.os.SystemProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import hidden.HiddenApiBridge
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import org.matrix.vector.ipc.ModuleCode
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.utils.ObfuscationManager

private const val TAG = "VectorFileSystem"

/**
 * What the module installer labels the module tree, and what [FileSystem] holds it to.
 *
 * Chosen over `xposed_file` because the manager APK is read by whoever receives it: the shipped
 * `sepolicy.rule` grants `xposed_file` to `shell` but not to `untrusted_app`, so an installed
 * manager could no longer update itself. The platform allows every `appdomain` and `coredomain`
 * to read a `system_file`, which covers both.
 */
private const val SYSTEM_FILE_CONTEXT = "u:object_r:system_file:s0"

/**
 * The properties the HyperOS Rust Runtime advertises itself with.
 *
 * Read rather than looking for `/system_ext/bin/hyos_spawner`: the runtime can be switched off on a
 * device that still ships that binary, and an index nothing will ever read is work done for
 * nothing. These are the same two properties LSPosed 2.2.0 reads for the same decision, in its
 * daemon's `ILSPManagerService` transaction 67; they were recovered from its released daemon.
 */
private const val HYOS_ACTIVE_PROPERTY = "rust.runtime_active"
private const val HYOS_VERSION_PROPERTY = "rust.runtime_version"

/**
 * The index of what each HyperOS Runtime process should load.
 *
 * It sits inside Vector's own tree rather than in the shared /data/misc, because nothing but the
 * companion reads it: the per-process libraries are looked up by the companion and handed to the
 * spawner before it forks, so the file never has to be reachable by the application it describes.
 * That is the whole reason there is no pointer file any more and no path published anywhere — the
 * channel is the companion connection, not the filesystem.
 *
 * Magisk holds /data/adb at mode 0700 owned by root, so this subtree is root-only by construction.
 */
private const val HYOS_INDEX_DIR = "hyos"
private const val HYOS_GENERATION_NAME = ".generation"

/**
 * The first line of every index, and the version of the format it describes.
 *
 * A file that does not start with it is not one of ours, which is the only thing between a
 * half-written file and the spawner acting on it. The two sides of this format are this file and
 * `zygisk/src/main/cpp/hyos_runtime.cpp`, and nothing else reads it.
 */
private const val HYOS_INDEX_MAGIC = "vector-hyos 1"

/**
 * The subdirectory of the misc root holding copies of module libraries that a process cannot map
 * out of the module's APK.
 *
 * Two of them, because the two readers have different lifetimes and each prunes what it no longer
 * needs. system_server's copies are pruned down to the modules still bound for system_server; the
 * HyperOS Runtime's, to the modules still in some scope. Sharing one directory would have each
 * pass delete the other's copies, leaving whichever reader ran first with nothing to load.
 */
private const val STAGED_LIBRARY_DIR = "lib"
private const val HYOS_LIBRARY_DIR = "libhyos"

/**
 * What came of trying to load a module APK.
 *
 * The loader used to answer every refusal with the same null, so a module built against libxposed
 * API 100 — which this framework drops outright — reached the user as the same "the framework could
 * not load it" as a zip that will not parse. That refusal is the one with somewhere to go: the
 * module is not broken, it is old, and only a rebuild by its author moves it. The rest really are
 * indistinguishable from here.
 */
sealed interface ModuleLoad {
  /** Parsed, and ready to hand to a forking process. */
  data class Loaded(val apk: ModuleCode) : ModuleLoad

  /** Declares libxposed API 100, and carries nothing else this framework can load. */
  data object UnsupportedApi : ModuleLoad

  /** Will not parse, has no init files, or names no module classes. */
  data object Unusable : ModuleLoad
}

/** The APK when it loaded and null when it did not, for callers with nothing to say about why. */
val ModuleLoad.apkOrNull: ModuleCode?
  get() = (this as? ModuleLoad.Loaded)?.apk

/**
 * One native library the index names for one process: which module asked for it, the name that
 * module declared, and the absolute path the process should load it from.
 */
data class HyosModuleLibrary(
    val modulePackage: String,
    val libraryName: String,
    val libraryPath: String,
)

object FileSystem {
  val basePath: Path = Paths.get("/data/adb/lspd")
  val logDirPath: Path = basePath.resolve("log")
  val oldLogDirPath: Path = basePath.resolve("log.old")
  val modulePath: Path = basePath.resolve("modules")
  val socketPath: Path = basePath.resolve(".cli_sock")
  val daemonApkPath: Path = Paths.get(System.getProperty("java.class.path", ""))
  val managerApkPath: Path = daemonApkPath.parent.resolve("manager.apk")
  val configDirPath: Path = basePath.resolve("config")
  val dbPath: File = configDirPath.resolve("modules_config.db").toFile()

  @Volatile private var preloadDex: SharedMemory? = null

  private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault())
  private val lockPath: Path = basePath.resolve("lock")
  private var fileLock: FileLock? = null
  private var lockChannel: FileChannel? = null

  init {
    runCatching {
          Files.createDirectories(basePath)
          Os.chmod(basePath.toString(), "700".toInt(8))
          SELinux.setFileContext(basePath.toString(), SYSTEM_FILE_CONTEXT)
          Files.createDirectories(configDirPath)
        }
        .onFailure { Log.e(TAG, "Failed to initialize directories", it) }
    ensureManagerApkLabel()
  }

  /**
   * Reasserts the label the flash gave [managerApkPath].
   *
   * The manager APK is the one file in the module directory that leaves it as a descriptor rather
   * than as bytes: `FrameworkService.openManagerApk` hands it to the host process to inject, and
   * `ManagerService.getManagerApk` hands it to an installed manager to update itself. The kernel
   * judges that hand-off by the *receiver* — `selinux_binder_transfer_file` checks the file against
   * `cred_sid(to)` — so the read is asked of `u:r:shell:s0` for the parasitic host, and of the
   * app's own domain for an installed one, never of the daemon's root context. Opening the file
   * here therefore proves nothing about whether it can be passed on.
   *
   * The installer leaves the module tree `system_file`, which every `appdomain` and `coredomain`
   * may read, and that is what makes the hand-off work. Nothing keeps it that way: `/data/adb` is
   * `adb_data_file` in the platform's `file_contexts`, and init's `restorecon --recursive
   * --skip-ce /data` walks the tree again whenever the `file_contexts` digest changes — a system
   * image flashed over a kept /data — which puts the default back. `adb_data_file` is readable by
   * `adbd` alone, so from then on every launch fails the transfer, the host is handed nothing, and
   * the manager dies in the host's own activity with a null intent to show for it. Magisk does not
   * repair this at boot either: its own restorecon only relabels files that are `unlabeled`.
   */
  private fun ensureManagerApkLabel() {
    if (!managerApkPath.exists()) return
    val path = managerApkPath.toString()
    runCatching {
          if (SELinux.getFileContext(path) == SYSTEM_FILE_CONTEXT) return@runCatching
          if (!SELinux.setFileContext(path, SYSTEM_FILE_CONTEXT)) {
            Log.w(TAG, "Failed to relabel $path as $SYSTEM_FILE_CONTEXT")
          }
        }
        .onFailure { Log.e(TAG, "Failed to relabel $path", it) }
  }

  fun setupCli(): String {
    val cliSource = daemonApkPath.parent.resolve("cli").toFile()
    val cliDest = basePath.resolve("cli").toFile()
    if (cliSource.exists()) {
      runCatching {
            cliSource.copyTo(cliDest, overwrite = true)
            Os.chmod(cliDest.absolutePath, "700".toInt(8))
          }
          .onFailure { Log.e(TAG, "Failed to deploy CLI script", it) }
    }

    val cliSocket: String = socketPath.toString()
    val socketFile = File(cliSocket)
    if (socketFile.exists()) {
      Log.d(TAG, "Existing $cliSocket deleted")
      socketFile.delete()
    }

    return cliSocket
  }

  /** Tries to lock the daemon lockfile. Returns false if another daemon is running. */
  fun tryLock(): Boolean {
    return runCatching {
          val permissions =
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
          lockChannel =
              FileChannel.open(
                  lockPath, setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE), permissions)
          fileLock = lockChannel?.tryLock()
          fileLock?.isValid == true
        }
        .getOrDefault(false)
  }

  /** Clears all special file attributes (like immutable) on a directory. */
  fun chattr0(path: Path): Boolean {
    return runCatching {
          val fd = Os.open(path.toString(), OsConstants.O_RDONLY, 0)
          // 0x40086602 for 64-bit, 0x40046602 for 32-bit (FS_IOC_SETFLAGS)
          val req = if (Process.is64Bit()) 0x40086602 else 0x40046602
          HiddenApiBridge.Os_ioctlInt(fd, req, 0)
          Os.close(fd)
          true
        }
        .recover { e -> if (e is ErrnoException && e.errno == OsConstants.ENOTSUP) true else false }
        .getOrDefault(false)
  }

  /** Recursively sets SELinux context. Crucial for modules to read their data. */
  fun setSelinuxContextRecursive(path: Path, context: String) {
    runCatching {
          SELinux.setFileContext(path.toString(), context)
          if (path.isDirectory()) {
            Files.list(path).use { stream ->
              stream.forEach { setSelinuxContextRecursive(it, context) }
            }
          }
        }
        .onFailure { Log.e(TAG, "Failed to set SELinux context for $path", it) }
  }

  /**
   * Lazily loads resources from the daemon's APK path via reflection. This allows FakeContext to
   * access strings/drawables without a real application context.
   */
  val resources: Resources by lazy {
    val am = AssetManager::class.java.getDeclaredConstructor().newInstance()
    val addAssetPath =
        AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
          isAccessible = true
        }
    addAssetPath.invoke(am, daemonApkPath.toString())
    @Suppress("DEPRECATION") Resources(am, null, null)
  }

  /** Loads a single DEX file into SharedMemory, optionally applying obfuscation. */
  private fun readDex(inputStream: InputStream, obfuscate: Boolean): SharedMemory {
    var memory = SharedMemory.create(null, inputStream.available())
    val byteBuffer = memory.mapReadWrite()
    Channels.newChannel(inputStream).read(byteBuffer)
    SharedMemory.unmap(byteBuffer)

    if (obfuscate) {
      val newMemory = ObfuscationManager.obfuscateDex(memory)
      if (memory !== newMemory) {
        memory.close()
        memory = newMemory
      }
    }
    memory.setProtect(OsConstants.PROT_READ)
    return memory
  }

  /**
   * The packages a module claims, when its module.prop fixes the scope. Null when it does not, so
   * a caller can tell "claims nothing" from "claims no restriction".
   *
   * staticScope is documented as "the module scope is fixed and users should not apply the module
   * on apps outside the scope list". Enforcing that in the manager alone leaves the socket CLI, a
   * backup restore and the module's own requestScope walking straight past it, so the daemon has
   * to know about it too.
   */
  fun readStaticScope(apkPath: String): Set<String>? =
      runCatching {
            ZipFile(File(apkPath)).use { zip ->
              val props =
                  Properties().apply {
                    zip.getEntry("META-INF/xposed/module.prop")?.let { entry ->
                      runCatching { zip.getInputStream(entry).use { load(it) } }
                    }
                  }
              if (!props.getProperty("staticScope").toBoolean()) return@use null
              val claimed =
                  zip.getEntry("META-INF/xposed/scope.list")?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().useLines { lines ->
                      lines.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    }
                  } ?: emptySet()
              // A module that fixes its scope and then names nothing has fixed it at "no apps at
              // all": every write through here is refused, and pruneScopeToClaimed deletes the rows
              // the user had already chosen on the next cache rebuild. That is a packaging mistake
              // rather than an intention — a module ships staticScope=true with a scope.list it
              // forgot to generate — and the cost of reading it literally is a module that can
              // never hook anything, silently. So the declaration is ignored and the scope stays
              // the user's; the manager's ModuleDetection ignores it too, so the picker it draws
              // and the writes accepted here agree about what the module may hook.
              if (claimed.isEmpty()) {
                Log.w(TAG, "$apkPath fixes its scope but names nothing; ignoring staticScope")
                return@use null
              }
              claimed
            }
          }
          .onFailure { Log.w(TAG, "Cannot read the scope list of $apkPath", it) }
          .getOrNull()

  /** Parses the module APK, extracts init lists, and loads DEXes into SharedMemory. */
  fun loadModule(apkPath: String, obfuscate: Boolean): ModuleLoad {
    val file = File(apkPath)
    if (!file.exists()) return ModuleLoad.Unusable

    val preLoadedApk = ModuleCode()
    val preLoadedDexes = mutableListOf<SharedMemory>()
    val moduleClassNames = mutableListOf<String>()
    val moduleLibraryNames = mutableListOf<String>()
    var isLegacy = false
    var exceptionPassthrough = false
    var targetApiVersion = 0
    var autoHotReload = false

    runCatching {
          ZipFile(file).use { zip ->
            // module.prop is specified as Java Properties format. Parsing it by hand mishandles
            // ':' as a separator, '!' comments, escapes and line continuations, and the manager
            // app already reads the same file with Properties.load.
            val props =
                Properties().apply {
                  zip.getEntry("META-INF/xposed/module.prop")?.let { entry ->
                    // Properties.load rejects a malformed \uXXXX escape, which the old hand-rolled
                    // parser tolerated. Keep that tolerance: a bad module.prop must not make the
                    // APK unloadable, not least because a legacy module is selected by
                    // assets/xposed_init and needs no module.prop at all.
                    runCatching { zip.getInputStream(entry).use { load(it) } }
                        .onFailure { Log.w(TAG, "Malformed module.prop in $apkPath", it) }
                  }
                }

            val targetApi = leadingInt(props.getProperty("targetApiVersion"))
            targetApiVersion = targetApi
            autoHotReload = props.getProperty("autoHotReload")?.trim().toBoolean()
            // The module-wide mode ExceptionMode.DEFAULT resolves to. Anything that is not
            // "passthrough" - absent, misspelled, or an explicit "protective" - keeps the
            // protective default the API specifies.
            exceptionPassthrough =
                props.getProperty("exceptionMode")?.trim().equals("passthrough", true)

            val hasLegacyFile = zip.getEntry("assets/xposed_init") != null

            // Determine Loading Strategy based on Priority: API 101+ > Legacy > API 100
            val strategy =
                when {
                  targetApi >= 101 -> "MODERN"
                  hasLegacyFile -> "LEGACY"
                  targetApi == 100 -> "UNSUPPORTED" // API 100 is dropped
                  else -> "NONE"
                }

            // Helper to read the list files
            fun readList(name: String, dest: MutableList<String>) {
              zip.getEntry(name)?.let { entry ->
                zip.getInputStream(entry).bufferedReader().useLines { lines ->
                  lines
                      .map { it.trim() }
                      .filter { it.isNotEmpty() && !it.startsWith("#") }
                      .forEach { dest.add(it) }
                }
              }
            }

            when (strategy) {
              "MODERN" -> {
                isLegacy = false
                readList("META-INF/xposed/java_init.list", moduleClassNames)
                readList("META-INF/xposed/native_init.list", moduleLibraryNames)
              }
              "LEGACY" -> {
                isLegacy = true
                readList("assets/xposed_init", moduleClassNames)
                readList("assets/native_init", moduleLibraryNames)
              }
              "UNSUPPORTED" -> {
                Log.w(TAG, "Module $apkPath uses API 100 which is no longer supported.")
                return ModuleLoad.UnsupportedApi
              }
              else -> return ModuleLoad.Unusable // No valid init files found
            }

            if (moduleClassNames.isEmpty()) return ModuleLoad.Unusable

            // Read DEX files
            var secondary = 1
            while (true) {
              val entryName = if (secondary == 1) "classes.dex" else "classes$secondary.dex"
              val dexEntry = zip.getEntry(entryName) ?: break
              zip.getInputStream(dexEntry).use { preLoadedDexes.add(readDex(it, obfuscate)) }
              secondary++
            }
          }
        }
        .onFailure {
          Log.e(TAG, "Failed to load module $apkPath", it)
          return ModuleLoad.Unusable
        }

    if (preLoadedDexes.isEmpty()) return ModuleLoad.Unusable

    // Apply obfuscation
    if (obfuscate) {
      val signatures = ObfuscationManager.getSignatures()
      for (i in moduleClassNames.indices) {
        val s = moduleClassNames[i]
        signatures.entries
            .firstOrNull { s.startsWith(it.key) }
            ?.let { moduleClassNames[i] = s.replace(it.key, it.value) }
      }
    }

    preLoadedApk.apply {
      this.preLoadedDexes = preLoadedDexes
      this.moduleClassNames = moduleClassNames
      this.moduleLibraryNames = moduleLibraryNames
      this.legacy = isLegacy
      this.exceptionPassthrough = exceptionPassthrough
      this.targetApiVersion = targetApiVersion
      this.autoHotReload = autoHotReload
    }

    return ModuleLoad.Loaded(preLoadedApk)
  }

  /** Safely creates the log directory. If a file exists with the same name, it deletes it first. */
  private fun createLogDirPath() {
    if (!Files.isDirectory(logDirPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      logDirPath.toFile().deleteRecursively()
    }
    Files.createDirectories(logDirPath)
  }

  /**
   * Rotates the log directory by clearing file attributes (chattr 0), deleting the old backup, and
   * renaming the current log directory to the backup.
   */
  fun moveLogDir() {
    runCatching {
          if (Files.exists(logDirPath)) {
            if (chattr0(logDirPath)) {
              // Kotlin's deleteRecursively replaces the verbose Java SimpleFileVisitor
              oldLogDirPath.toFile().deleteRecursively()
              Files.move(logDirPath, oldLogDirPath)
            }
          }
          Files.createDirectories(logDirPath)
        }
        .onFailure { Log.e(TAG, "Failed to move log directory", it) }
  }

  fun getPropsPath(): File {
    createLogDirPath()
    return logDirPath.resolve("props.txt").toFile()
  }

  fun getKmsgPath(): File {
    createLogDirPath()
    return logDirPath.resolve("kmsg.log").toFile()
  }

  @Synchronized
  fun getPreloadDex(obfuscate: Boolean): SharedMemory? {
    if (preloadDex == null) {
      runCatching {
            FileInputStream("framework/vector.dex").use { preloadDex = readDex(it, obfuscate) }
          }
          .onFailure { Log.e(TAG, "Failed to load framework dex", it) }
    }
    return preloadDex
  }

  fun ensureModuleFilePath(path: String?) {
    if (path == null || path.contains(File.separatorChar) || path == "." || path == "..") {
      throw RemoteException("Invalid path: $path")
    }
  }

  fun resolveModuleDir(packageName: String, dir: String, userId: Int, uid: Int): Path {
    val path = modulePath.resolve(userId.toString()).resolve(packageName).resolve(dir).normalize()
    path.toFile().mkdirs()

    if (SELinux.getFileContext(path.toString()) != "u:object_r:xposed_data:s0") {
      runCatching {
            setSelinuxContextRecursive(path, "u:object_r:xposed_data:s0")
            if (uid != -1) Os.chown(path.toString(), uid, uid)
            Os.chmod(path.toString(), "755".toInt(8))
          }
          .onFailure { throw RemoteException("Failed to set SELinux context: ${it.message}") }
    }
    return path
  }

  /**
   * Copies a module's native libraries out of its APK into a directory this framework owns, and
   * answers with that directory.
   *
   * A module loaded into system_server cannot dlopen a library straight out of its own APK.
   * Everything under /data/app is apk_data_file, and while system_server may read and map such a
   * file it may not execute it; AOSP says why in so many words - "Executable files in /data are a
   * persistence vector" - and forbids granting it. Every app domain does hold that permission,
   * which is the whole reason the same module loads the same library without trouble in an ordinary
   * process and fails only in system_server.
   *
   * The way past it is not a new rule but the one this module already ships. xposed_data is a type
   * we declare ourselves, outside the data_file_type attribute that neverallow is written against,
   * and `allow * xposed_data {file dir} *` already reaches every domain - system_server included.
   * A copy placed under it is one system_server may map executable. Extraction has a second
   * benefit: the library ends up at offset zero of an ordinary file, so it no longer has to be
   * stored uncompressed and page-aligned inside the APK to be mappable at all.
   *
   * Note that this deliberately does not consult moduleLibraryNames. That list only names the
   * libraries whose native_init we are asked to call, and a module is free to load its own
   * libraries without declaring any - the module that prompted all this does exactly that.
   *
   * Returns null when the module ships nothing for this ABI or the copy failed, in which case the
   * module still loads and only its native part fails, exactly as it does today.
   *
   * [dirName] selects which of the readers the copy is for; see [STAGED_LIBRARY_DIR]. The two sets
   * are kept apart because each is pruned against a different notion of "still needed".
   */
  fun stageNativeLibraries(
      root: Path,
      packageName: String,
      apkPath: String,
      dirName: String = STAGED_LIBRARY_DIR,
  ): String? =
      runCatching {
            val apk = File(apkPath)
            val dir = root.resolve(dirName).resolve(packageName)

            // Re-extract only when the APK behind the copy changed. Getting this wrong in the
            // lenient direction would leave system_server running a module's superseded native
            // code, so the framework's own version is part of the stamp as well.
            val stamp = "${apk.length()}:${apk.lastModified()}:${BuildConfig.VERSION_CODE}"
            val stampFile = dir.resolve(".stamp").toFile()
            if (stampFile.isFile && stampFile.readText() == stamp) return@runCatching dir.toString()

            val abis =
                if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS

            ZipFile(apk).use { zip ->
              val libraries =
                  zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".so") }
                      .toList()
              // A module built for several ABIs keeps them in sibling directories, and only the one
              // this process could load is worth copying.
              val abi =
                  abis.firstOrNull { abi -> libraries.any { it.name.startsWith("lib/$abi/") } }
                      ?: return@runCatching null

              dir.toFile().deleteRecursively()
              Files.createDirectories(dir)

              libraries
                  .filter { it.name.startsWith("lib/$abi/") }
                  .forEach { entry ->
                    val target = dir.resolve(entry.name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { input ->
                      Files.newOutputStream(target).use { input.copyTo(it) }
                    }
                    Os.chmod(target.toString(), "644".toInt(8))
                  }

              stampFile.writeText(stamp)
              // The daemon runs with a zero umask, so every mode here is set rather than inherited.
              Os.chmod(stampFile.absolutePath, "644".toInt(8))
              // The misc root is searchable but not listable; the staged tree keeps that shape, and
              // the label is what actually decides whether system_server may map these files.
              Os.chmod(dir.parent.toString(), "711".toInt(8))
              Os.chmod(dir.toString(), "711".toInt(8))
              setSelinuxContextRecursive(dir, "u:object_r:xposed_data:s0")
              SELinux.setFileContext(dir.parent.toString(), "u:object_r:xposed_data:s0")
              dir.toString()
            }
          }
          .onFailure { Log.e(TAG, "Failed to stage the native libraries of $packageName", it) }
          .getOrNull()

  /**
   * Drops staged libraries belonging to modules that are no longer bound for system_server, so an
   * uninstalled or rescoped module does not leave a copy of its native code behind for good.
   */
  fun pruneStagedNativeLibraries(
      root: Path?,
      keep: Set<String>,
      dirName: String = STAGED_LIBRARY_DIR,
  ) {
    if (root == null) return
    runCatching {
          val libRoot = root.resolve(dirName)
          if (!libRoot.isDirectory()) return
          Files.list(libRoot).use { stream ->
            stream
                .filter { it.fileName.toString() !in keep }
                .forEach { it.toFile().deleteRecursively() }
          }
        }
        .onFailure { Log.e(TAG, "Failed to prune staged native libraries", it) }
  }

  /** Whether this device runs applications on the HyperOS Rust Runtime at all. */
  fun hasHyosRuntime(): Boolean =
      SystemProperties.getBoolean(HYOS_ACTIVE_PROPERTY, false) &&
          SystemProperties.get(HYOS_VERSION_PROPERTY).orEmpty().isNotEmpty()

  /**
   * Stages a module's native libraries for the HyperOS Runtime's reader rather than system_server's.
   *
   * A process there cannot be told a search path the way an injected ART process can, so it is
   * handed absolute paths in the index below and needs the libraries to exist at those paths. The
   * APK itself would do — /data/app is readable and mappable by any app domain — but the entry has
   * to be STORED inside the zip for the loader to open it, and a module that compressed its
   * libraries would silently lose its native part. A copy has neither problem.
   */
  fun stageHyosNativeLibraries(root: Path, packageName: String, apkPath: String): String? =
      stageNativeLibraries(root, packageName, apkPath, HYOS_LIBRARY_DIR)

  /** Drops HyperOS-staged libraries of modules that are in no scope any more. */
  fun pruneHyosNativeLibraries(root: Path?, keep: Set<String>) {
    pruneStagedNativeLibraries(root, keep, HYOS_LIBRARY_DIR)
  }

  /**
   * Publishes what each HyperOS Runtime process should load.
   *
   * Written 0600 inside Vector's own root-owned tree: the only reader is the companion, which runs
   * as root, and the only consumer is the spawner, which inherits the answer through the fork. No
   * application is meant to see this, and none can reach the directory to try.
   *
   * A process with no file is in nobody's scope. Files for processes that stopped being in scope
   * are removed, and the generation stamp is rewritten last, so a reader that refreshes on a
   * changed generation never sees a half-written set.
   */
  fun publishHyosIndex(index: Map<String, List<HyosModuleLibrary>>) {
    runCatching {
          val dir = basePath.resolve(HYOS_INDEX_DIR)
          Files.createDirectories(dir)
          Os.chmod(dir.toString(), "700".toInt(8))

          val written = mutableSetOf<String>()
          index.forEach { (processName, libraries) ->
            // A process name is a manifest string that becomes a file name here. Android's own
            // validation is not something to lean on for a write performed as root, so anything
            // that is not a plain name is refused rather than resolved.
            if (processName.isEmpty() || processName.contains('/') || processName == "." ||
                processName == "..") {
              Log.w(TAG, "Refusing to publish an index under the name '$processName'")
              return@forEach
            }
            val target = dir.resolve(processName)
            val text =
                buildString {
                  appendLine(HYOS_INDEX_MAGIC)
                  libraries.forEach {
                    append(it.modulePackage)
                    append('\t')
                    append(it.libraryName)
                    append('\t')
                    append(it.libraryPath)
                    append('\n')
                  }
                }
            Files.writeString(
                target,
                text,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            Os.chmod(target.toString(), "600".toInt(8))
            written.add(processName)
          }

          Files.list(dir).use { stream ->
            stream
                .filter {
                  val name = it.fileName.toString()
                  name != HYOS_GENERATION_NAME && name !in written
                }
                .forEach { it.toFile().delete() }
          }

          // Written last, and only after the entries are in place: it is what a reader keys on, so
          // a generation that has moved promises a set that is already complete.
          val generation = dir.resolve(HYOS_GENERATION_NAME)
          Files.writeString(
              generation,
              System.currentTimeMillis().toString(),
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
          )
          Os.chmod(generation.toString(), "600".toInt(8))
        }
        .onFailure { Log.e(TAG, "Failed to publish the HyperOS Runtime index", it) }
  }

  fun toGlobalNamespace(path: String): File {
    return if (path.startsWith("/")) File("/proc/1/root", path) else File("/proc/1/root/$path")
  }

  fun getLogs(zipFd: ParcelFileDescriptor) {
    runCatching {
          ZipOutputStream(java.io.FileOutputStream(zipFd.fileDescriptor)).use { os ->
            // The commit, not just the version code: the code is the commit count on master, so
            // every branch build at the same depth wears the number of an official build it was
            // never made from. Without it an attached archive cannot be tied to a binary.
            val comment =
                "Vector ${BuildConfig.BUILD_TYPE} ${BuildConfig.VERSION_NAME} " +
                    "(${BuildConfig.VERSION_CODE}) ${BuildConfig.VERSION_HASH}"
            os.setComment(comment)
            os.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)

            fun addFile(name: String, file: File) {
              if (!file.exists() || !file.isFile) return
              runCatching {
                    os.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(os) }
                    os.closeEntry()
                  }
                  .onFailure { Log.e(TAG, "Failed to export $file as $name", it) }
            }

            fun addDir(basePath: String, dir: File) {
              if (!dir.exists() || !dir.isDirectory) return
              dir.walkTopDown()
                  .filter { it.isFile }
                  .forEach { file ->
                    val relativePath = dir.toPath().relativize(file.toPath()).toString()
                    val entryName =
                        if (basePath.isEmpty()) relativePath else "$basePath/$relativePath"
                    addFile(entryName, file)
                  }
            }

            fun addProcOutput(name: String, vararg cmd: String) {
              runCatching {
                val proc = ProcessBuilder(*cmd).start()
                os.putNextEntry(ZipEntry(name))
                proc.inputStream.use { it.copyTo(os) }
                os.closeEntry()
              }
            }

            // Gather system crash traces
            addDir("tombstones", File("/data/tombstones"))
            addDir("anr", File("/data/anr"))
            addDir(
                "crash_shell",
                File("/data/data/${BuildConfig.MANAGER_INJECTED_PKG_NAME}/cache/crash"))
            addDir(
                "crash_manager",
                File("/data/data/${BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME}/cache/crash"))

            // Gather system logs directly via shell
            addProcOutput("full.log", "logcat", "-b", "all", "-d")
            addProcOutput("dmesg.log", "dmesg")

            // Gather system module states safely
            val magiskDataDir = File("/data/adb/modules")
            if (magiskDataDir.exists() && magiskDataDir.isDirectory) {
              magiskDataDir.listFiles()?.forEach { moduleDir ->
                val modName = moduleDir.name
                listOf("module.prop", "remove", "disable", "update", "sepolicy.rule").forEach {
                  addFile("modules/$modName/$it", File(moduleDir, it))
                }
              }
            }

            // Gather memory/mount info for daemon and caller
            val proc = File("/proc")
            arrayOf("self", Binder.getCallingPid().toString()).forEach { pid ->
              val pidPath = File(proc, pid)
              listOf("maps", "mountinfo", "status").forEach {
                addFile("proc/$pid/$it", File(pidPath, it))
              }
            }

            // Gather Database and Scopes
            addFile("modules_config.db", dbPath)
            runCatching {
                  val scopes = ConfigCache.state.scopes
                  Log.d(TAG, "Exporting scopes for ${scopes.size} targets")
                  os.putNextEntry(ZipEntry("scopes.txt"))
                  scopes.forEach { (scope, modules) ->
                    os.write("${scope.processName}/${scope.uid}\n".toByteArray())
                    modules.forEach { mod ->
                      os.write("\t${mod.packageName}\n".toByteArray())
                      mod.code?.moduleClassNames?.forEach { cn ->
                        os.write("\t\t$cn\n".toByteArray())
                      }
                      mod.code?.moduleLibraryNames?.forEach { ln ->
                        os.write("\t\t$ln\n".toByteArray())
                      }
                    }
                  }
                  os.closeEntry()
                }
                .onFailure { Log.e(TAG, "Failed to export module scopes", it) }

            // Gather daemon logs
            addDir("log", logDirPath.toFile())
            addDir("log.old", oldLogDirPath.toFile())
          }
        }
        .onFailure { Log.e(TAG, "Failed to export logs", it) }
        .also { runCatching { zipFd.close() } }
  }

  private fun getNewLogFileName(prefix: String): String {
    return "${prefix}_${formatter.format(Instant.now())}.log"
  }

  /**
   * The parts still on disk for one of the two logs, oldest first.
   *
   * Read from the directory rather than from LogcatMonitor's LRU so that a manager opened after a
   * daemon restart still sees the history: the LRU is rebuilt empty, the files are not.
   */
  fun listLogParts(verbose: Boolean): List<String> {
    val prefix = if (verbose) "verbose_" else "modules_"
    return runCatching {
          logDirPath
              .toFile()
              .listFiles { file -> file.isFile && file.name.startsWith(prefix) && file.name.endsWith(".log") }
              ?.map { it.name }
              // The names carry an ISO-8601 timestamp, so lexicographic order is chronological.
              ?.sorted()
              .orEmpty()
        }
        .getOrDefault(emptyList())
  }

  /**
   * Opens one part by name.
   *
   * The name arrives from an unprivileged process and is used to build a path inside a directory
   * only root can read, so it is never trusted: it has to be one of the names [listLogParts] just
   * returned, which rules out traversal and anything outside the log directory by construction
   * rather than by pattern-matching for "..".
   */
  fun openLogPart(verbose: Boolean, name: String): File? {
    if (name !in listLogParts(verbose)) return null
    return logDirPath.resolve(name).toFile().takeIf { it.isFile }
  }

  fun getNewVerboseLogPath(): File {
    createLogDirPath()
    return logDirPath.resolve(getNewLogFileName("verbose")).toFile()
  }

  fun getNewModulesLogPath(): File {
    createLogDirPath()
    return logDirPath.resolve(getNewLogFileName("modules")).toFile()
  }

  // Matches the manager's leading-integer parsing, including values such as "101.0".
  private fun leadingInt(value: String?): Int =
      value?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
}

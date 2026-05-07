package org.matrix.vector.daemon.ipc

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.lsposed.lspd.models.Application
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.CliRequest
import org.matrix.vector.daemon.CliResponse
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.ModuleDatabase
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.system.*

object CliHandler {

  /**
   * Executes the requested CLI command within the daemon's memory space. Returns a structured
   * CliResponse.
   */
  fun execute(request: CliRequest): CliResponse {
    return try {
      val responseData =
          when (request.command) {
            "status" -> handleStatus()
            "modules" -> handleModules(request)
            "scope" -> handleScope(request)
            "config" -> handleConfig(request)
            "db" -> handleDatabase(request)
            "log" -> handleLog(request)
            else -> throw IllegalArgumentException("Unknown command: ${request.command}")
          }
      CliResponse(success = true, data = responseData)
    } catch (e: Exception) {
      CliResponse(success = false, error = e.message ?: "Unknown error occurred")
    }
  }

  private fun handleStatus(): Map<String, Any> {
    return mapOf(
        "Framework Version" to BuildConfig.VERSION_NAME,
        "Version Code" to BuildConfig.VERSION_CODE,
        "Enabled Modules" to ConfigCache.state.modules.size,
        "Status Notification" to PreferenceStore.isStatusNotificationEnabled())
  }

  private fun isPackageInstalled(pkg: String, userId: Int = 0): Boolean {
    return runCatching { packageManager?.getPackageInfo(pkg, 0, userId) != null }
        .getOrDefault(false)
  }

  private fun handleModules(request: CliRequest): Any {
    return when (request.action) {
      "ls" -> {
        val enabledOnly = request.options["enabled"] as? Boolean ?: false
        val disabledOnly = request.options["disabled"] as? Boolean ?: false

        //  Get the current immutable snapshot of enabled modules
        val enabledModuleKeys = ConfigCache.state.modules.keys
        //  Get all installed modules from the system
        val installed = ConfigCache.getInstalledModules()

        // Map to the CLI view model
        installed
            .mapNotNull { info ->
              val pkg = info.packageName
              val isEnabled = enabledModuleKeys.contains(pkg)

              // Filter based on CLI flags
              if (enabledOnly && !isEnabled) return@mapNotNull null
              if (disabledOnly && isEnabled) return@mapNotNull null

              mapOf(
                  "PACKAGE" to pkg,
                  "UID" to info.uid,
                  "STATUS" to (if (isEnabled) "enabled" else "disabled"))
            }
            .sortedBy { it["PACKAGE"] as String }
      }
      "enable" -> {
        if (request.targets.isEmpty())
            throw IllegalArgumentException("No packages provided to enable.")
        val success = mutableListOf<String>()
        val failed = mutableListOf<String>()
        request.targets.forEach { pkg ->
          if (ModuleDatabase.enableModule(pkg)) success.add(pkg) else failed.add(pkg)
        }
        mapOf("Enabled" to success, "Failed" to failed)
      }
      "disable" -> {
        if (request.targets.isEmpty())
            throw IllegalArgumentException("No packages provided to disable.")
        val success = mutableListOf<String>()
        val failed = mutableListOf<String>()
        request.targets.forEach { pkg ->
          if (ModuleDatabase.disableModule(pkg)) success.add(pkg) else failed.add(pkg)
        }
        mapOf("Disabled" to success, "Failed" to failed)
      }
      else -> throw IllegalArgumentException("Unknown module action: ${request.action}")
    }
  }

  private fun handleScope(request: CliRequest): Any {
    if (request.targets.isEmpty()) throw IllegalArgumentException("Module package name required.")
    val modulePkg = request.targets[0]
    val apps = request.targets.drop(1)

    return when (request.action) {
      "ls" -> {
        val scope =
            ConfigCache.getModuleScope(modulePkg)
                ?: throw IllegalArgumentException("Module not found: $modulePkg")
        scope.map { mapOf("APP_PACKAGE" to it.packageName, "USER_ID" to it.userId) }
      }
      "add" -> {
        if (apps.isEmpty()) throw IllegalArgumentException("No target apps provided.")
        val scope = ConfigCache.getModuleScope(modulePkg) ?: mutableListOf()

        apps.forEach { appStr ->
          val parts = appStr.split("/")
          val pkg = parts[0]
          val user = parts.getOrNull(1)?.toIntOrNull() ?: 0
          if (scope.none { it.packageName == pkg && it.userId == user }) {
            scope.add(
                Application().apply {
                  packageName = pkg
                  userId = user
                })
          }
        }
        ModuleDatabase.setModuleScope(modulePkg, scope)
        "Successfully appended ${apps.size} apps to $modulePkg scope."
      }
      "set" -> {
        if (apps.isEmpty())
            throw IllegalArgumentException("No target apps provided for scope overwrite.")
        val scope = mutableListOf<Application>()
        apps.forEach { appStr ->
          val parts = appStr.split("/")
          val pkg = parts[0]
          val user = parts.getOrNull(1)?.toIntOrNull() ?: 0
          scope.add(
              Application().apply {
                packageName = pkg
                userId = user
              })
        }
        ModuleDatabase.setModuleScope(modulePkg, scope)
        "Successfully overwrote scope for $modulePkg (${apps.size} apps)."
      }
      "rm" -> {
        if (apps.isEmpty()) throw IllegalArgumentException("No target apps provided to remove.")
        var removedCount = 0
        apps.forEach { appStr ->
          val parts = appStr.split("/")
          val pkg = parts[0]
          val user = parts.getOrNull(1)?.toIntOrNull() ?: 0
          if (ModuleDatabase.removeModuleScope(modulePkg, pkg, user)) removedCount++
        }
        "Successfully removed $removedCount apps from $modulePkg scope."
      }
      else -> throw IllegalArgumentException("Unknown scope action: ${request.action}")
    }
  }

  private fun handleConfig(request: CliRequest): Any {
    val keys = request.targets
    return when (request.action) {
      "get" -> {
        if (keys.isEmpty()) throw IllegalArgumentException("Config key required.")
        val key = keys[0]
        val value =
            when (key) {
              "status-notification" -> ManagerService.enableStatusNotification()
              "verbose-log" -> ManagerService.isVerboseLog
              else -> throw IllegalArgumentException("Unknown config key: $key")
            }
        mapOf("KEY" to key, "VALUE" to value)
      }
      "set" -> {
        if (keys.size < 2) throw IllegalArgumentException("Key and value required.")
        val key = keys[0]
        val value =
            keys[1].toBooleanStrictOrNull()
                ?: throw IllegalArgumentException("Value must be 'true' or 'false'.")

        when (key) {
          "status-notification" -> ManagerService.setEnableStatusNotification(value)
          "verbose-log" -> ManagerService.setVerboseLog(value)
          else -> throw IllegalArgumentException("Unknown config key: $key")
        }
        "Successfully set $key to $value."
      }
      else -> throw IllegalArgumentException("Unknown config action: ${request.action}")
    }
  }

  private fun handleDatabase(request: CliRequest): Any {
    val action = request.action

    return when (action) {
      "backup" -> {
        val path =
            request.targets.firstOrNull()
                ?: throw IllegalArgumentException(
                    "Target path is required for database operations.")
        val dbFile = File(path)
        if (dbFile.exists()) dbFile.delete()

        // VACUUM INTO creates a consistent, defragmented copy without long-term locking.
        ConfigCache.dbHelper.writableDatabase.execSQL("VACUUM INTO '$path'")
        "Database backed up successfully to: $path"
      }
      "restore" -> {
        val path =
            request.targets.firstOrNull()
                ?: throw IllegalArgumentException(
                    "Target path is required for database operations.")
        val sourceFile = File(path)
        if (!sourceFile.exists()) throw FileNotFoundException("Source file does not exist: $path")

        val currentDbPath = ConfigCache.dbHelper.readableDatabase.path
        ConfigCache.dbHelper.close()
        sourceFile.copyTo(File(currentDbPath), overwrite = true)

        ConfigCache.requestCacheUpdate()
        val miscPath = ConfigCache.state.miscPath
        if (miscPath != null)
            PreferenceStore.updateModulePref("lspd", 0, "config", "misc_path", miscPath.toString())

        "Database restored from $path. Daemon state is being refreshed."
      }
      "reset" -> {
        val currentDbPath = ConfigCache.dbHelper.readableDatabase.path
        ConfigCache.dbHelper.close()

        val dbFile = File(currentDbPath)
        val walFile = File("$currentDbPath-wal")
        val shmFile = File("$currentDbPath-shm")

        val deleted = dbFile.delete()
        if (walFile.exists()) walFile.delete()
        if (shmFile.exists()) shmFile.delete()
        if (!deleted) throw IOException("Failed to delete database file.")

        ConfigCache.requestCacheUpdate()
        val miscPath = ConfigCache.state.miscPath
        if (miscPath != null)
            PreferenceStore.updateModulePref("lspd", 0, "config", "misc_path", miscPath.toString())

        "Database reset successfully. Schema recreated."
      }
      else -> throw IllegalArgumentException("Unknown database action: ${request.action}")
    }
  }

  private fun handleLog(request: CliRequest): Any {
    return when (request.action) {
      "clear" -> {
        val verbose = request.options["verbose"] as? Boolean ?: false
        ManagerService.clearLogs(verbose)
        "Logs cleared successfully."
      }
      // "stream" is handled in SystemServerService.kt to attach the FileDescriptor
      else -> throw IllegalArgumentException("Unknown log action: ${request.action}")
    }
  }
}

package org.matrix.vector.daemon

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.util.concurrent.Callable
import kotlin.system.exitProcess
import org.matrix.vector.daemon.data.FileSystem
import picocli.CommandLine
import picocli.CommandLine.*

// --- IPC Data Models ---
data class CliRequest(
    val command: String,
    val action: String = "",
    val targets: List<String> = emptyList(),
    val options: Map<String, Any> = emptyMap()
)

data class CliResponse(
    val success: Boolean,
    val data: Any? = null,
    val error: String? = null,
    val isFdAttached: Boolean = false
)

// --- IPC Client Logic ---
object VectorIPC {
  val gson: Gson =
      GsonBuilder()
          .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE) // Handles Any/Object fields
          .setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE) // Handles Map values
          .setPrettyPrinting()
          .create()

  fun transmit(request: CliRequest): CliResponse {
    val socket = LocalSocket()
    return try {
      val cliSocket = FileSystem.socketPath.toString()
      val socketFile = java.io.File(cliSocket)

      if (!socketFile.exists()) {
        System.err.println("Error: Socket file not found at $cliSocket")
        System.err.println("Current UID: ${android.os.Process.myUid()}")
      }
      socket.connect(LocalSocketAddress(cliSocket, LocalSocketAddress.Namespace.FILESYSTEM))

      val output = DataOutputStream(socket.outputStream)
      val input = DataInputStream(socket.inputStream)

      // Send Security Token
      output.writeLong(BuildConfig.CLI_TOKEN_MSB)
      output.writeLong(BuildConfig.CLI_TOKEN_LSB)

      // Send Request
      output.writeUTF(gson.toJson(request))

      // Read Response
      val responseJson = input.readUTF()
      val response = gson.fromJson(responseJson, CliResponse::class.java)

      // Handle Log Streaming
      if (response.isFdAttached) {
        val hasFd = input.readByte()
        if (hasFd.toInt() == 1) {
          val fds = socket.getAncillaryFileDescriptors()
          if (!fds.isNullOrEmpty()) {
            streamLog(fds[0], request.options["follow"] as? Boolean ?: false)
          }
        }
      }
      response
    } catch (e: Exception) {
      CliResponse(success = false, error = "Socket Failure: ${e.message}")
    } finally {
      socket.close()
    }
  }

  private fun streamLog(fd: java.io.FileDescriptor, follow: Boolean) {
    // Wrap the raw FileDescriptor in a FileInputStream.
    // 'use' ensures that fis.close() (and thus the FD) is called
    // when the block finishes or if an exception is thrown.
    FileInputStream(fd).use { fis ->
      val reader = fis.bufferedReader()

      try {
        while (true) {
          val line = reader.readLine()
          if (line != null) {
            println(line)
          } else {
            if (!follow) break // EOF reached, exit

            // In follow mode, wait for new data to be written to the log
            Thread.sleep(100)
          }

          // Check if thread was interrupted (e.g. by a shutdown hook)
          if (Thread.interrupted()) break
        }
      } catch (e: Exception) {
        if (e !is InterruptedException) {
          System.err.println("Log streaming error: ${e.message}")
        }
      }
    } // FD is closed here automatically
  }
}

// --- UI Formatter ---
object OutputFormatter {
  /**
   * Auto-formats the Daemon's output. Prints ASCII tables for lists, Key-Value for maps, or raw
   * JSON.
   */
  @Suppress("UNCHECKED_CAST")
  fun print(response: CliResponse, isJson: Boolean): Int {
    if (isJson) {
      println(VectorIPC.gson.toJson(response))
      return if (response.success) 0 else 1
    }

    if (!response.success) {
      System.err.println("Error: ${response.error}")
      return 1
    }

    val data = response.data ?: return 0

    when (data) {
      is List<*> -> {
        if (data.isEmpty()) {
          println("No records found.")
          return 0
        }
        // Check if it's a list of objects/maps to draw a table
        val first = data[0]
        if (first is Map<*, *>) {
          printTable(data as List<Map<String, Any>>)
        } else {
          data.forEach { println(" - $it") }
        }
      }
      is Map<*, *> -> {
        data.forEach { (k, v) -> println("$k: $v") }
      }
      else -> println(data.toString())
    }
    return 0
  }

  private fun printTable(rows: List<Map<String, Any>>) {
    val headers = rows.first().keys.toList()
    val columnWidths = headers.associateWith { it.length }.toMutableMap()

    // Calculate maximum width for each column
    for (row in rows) {
      for (header in headers) {
        val length = row[header]?.toString()?.length ?: 0
        if (length > columnWidths[header]!!) {
          columnWidths[header] = length
        }
      }
    }

    // Print Headers
    val headerRow = headers.joinToString("  ") { it.padEnd(columnWidths[it]!!) }
    println(headerRow.uppercase())
    println("-".repeat(headerRow.length))

    // Print Data
    for (row in rows) {
      println(
          headers.joinToString("  ") { header ->
            (row[header]?.toString() ?: "").padEnd(columnWidths[header]!!)
          })
    }
  }
}

// --- CLI Commands (picocli) ---
@Command(
    name = "vector-cli",
    mixinStandardHelpOptions = true,
    version = ["Vector CLI ${BuildConfig.VERSION_NAME}"],
    description = ["A fast, scriptable CLI for configuring the Vector Framework daemon."],
    subcommands =
        [
            StatusCommand::class,
            ModulesCommand::class,
            ScopeCommand::class,
            ConfigCommand::class,
            DatabaseCommand::class,
            LogCommand::class])
class Cli : Callable<Int> {

  @Option(
      names = ["--json"],
      description = ["Output structured JSON for scripting"],
      scope = ScopeType.INHERIT)
  var json: Boolean = false

  override fun call(): Int {
    CommandLine(this).usage(System.out)
    return 0
  }

  companion object {
    @JvmStatic
    fun main(args: Array<String>) {
      val uid = Process.myUid()
      if (uid != 0) {
        System.err.println("Permission denied: Vector CLI must run as root.")
        exitProcess(1)
      }
      val mainThread = Thread.currentThread()
      Runtime.getRuntime()
          .addShutdownHook(
              Thread {
                mainThread.interrupt() // Signal the loop to stop and close the stream
              })
      val exitCode = CommandLine(Cli()).execute(*args)
      exitProcess(exitCode)
    }
  }
}

@Command(name = "status", description = ["Show framework and system health status"])
class StatusCommand : Callable<Int> {
  @ParentCommand lateinit var parent: Cli

  override fun call(): Int {
    val req = CliRequest(command = "status")
    val res = VectorIPC.transmit(req)
    return OutputFormatter.print(res, parent.json)
  }
}

@Command(name = "modules", description = ["Manage Xposed modules"])
class ModulesCommand {
  @ParentCommand lateinit var parent: Cli

  @Command(name = "ls", description = ["List installed modules"])
  fun ls(
      @Option(names = ["-e", "--enabled"], description = ["Show only enabled modules"])
      enabled: Boolean,
      @Option(names = ["-d", "--disabled"], description = ["Show only disabled modules"])
      disabled: Boolean
  ): Int {
    val req =
        CliRequest(
            command = "modules",
            action = "ls",
            options = mapOf("enabled" to enabled, "disabled" to disabled))
    return OutputFormatter.print(VectorIPC.transmit(req), parent.json)
  }

  @Command(name = "enable", description = ["Enable one or more modules (batch processing)"])
  fun enable(@Parameters(paramLabel = "PKG", arity = "1..*") pkgs: List<String>): Int {
    val req = CliRequest(command = "modules", action = "enable", targets = pkgs)
    return OutputFormatter.print(VectorIPC.transmit(req), parent.json)
  }

  @Command(name = "disable", description = ["Disable one or more modules (batch processing)"])
  fun disable(@Parameters(paramLabel = "PKG", arity = "1..*") pkgs: List<String>): Int {
    val req = CliRequest(command = "modules", action = "disable", targets = pkgs)
    return OutputFormatter.print(VectorIPC.transmit(req), parent.json)
  }
}

@Command(name = "scope", description = ["Manage granular application injection scopes"])
class ScopeCommand {
  @ParentCommand lateinit var parent: Cli

  @Command(name = "ls", description = ["List apps in a module's scope"])
  fun ls(@Parameters(index = "0", paramLabel = "MODULE_PKG") modulePkg: String): Int {
    val req = CliRequest(command = "scope", action = "ls", targets = listOf(modulePkg))
    return OutputFormatter.print(VectorIPC.transmit(req), parent.json)
  }

  @Command(name = "add", description = ["Append apps to scope (format: pkg/user_id)"])
  fun add(
      @Parameters(index = "0", paramLabel = "MODULE_PKG") modulePkg: String,
      @Parameters(index = "1..*") apps: List<String>
  ): Int {
    val req = CliRequest(command = "scope", action = "add", targets = listOf(modulePkg) + apps)
    return OutputFormatter.print(VectorIPC.transmit(req), parent.json)
  }

  @Command(name = "set", description = ["Overwrite entire scope (format: pkg/user_id)"])
  fun set(
      @Parameters(index = "0", paramLabel = "MODULE_PKG") modulePkg: String,
      @Parameters(index = "1..*") apps: List<String>
  ): Int {
    val req = CliRequest(command = "scope", action = "set", targets = listOf(modulePkg) + apps)
    return OutputFormatter.print(VectorIPC.transmit(req), parent.json)
  }

  @Command(name = "rm", description = ["Remove apps from scope (format: pkg/user_id)"])
  fun rm(
      @Parameters(index = "0", paramLabel = "MODULE_PKG") modulePkg: String,
      @Parameters(index = "1..*") apps: List<String>
  ): Int {
    val req = CliRequest(command = "scope", action = "rm", targets = listOf(modulePkg) + apps)
    return OutputFormatter.print(VectorIPC.transmit(req), parent.json)
  }
}

@Command(name = "config", description = ["Manage daemon preferences natively"])
class ConfigCommand {
  @ParentCommand lateinit var parent: Cli

  @Command(name = "get", description = ["Get a config value"])
  fun get(@Parameters(paramLabel = "KEY") key: String): Int {
    val req = CliRequest(command = "config", action = "get", targets = listOf(key))
    return OutputFormatter.print(VectorIPC.transmit(req), parent.json)
  }

  @Command(name = "set", description = ["Set a config value"])
  fun set(
      @Parameters(index = "0", paramLabel = "KEY") key: String,
      @Parameters(index = "1", paramLabel = "VALUE") value: String
  ): Int {
    val req = CliRequest(command = "config", action = "set", targets = listOf(key, value))
    return OutputFormatter.print(VectorIPC.transmit(req), parent.json)
  }
}

@Command(name = "db", description = ["Database maintenance"])
class DatabaseCommand {
  @ParentCommand lateinit var parent: Cli

  @Command(name = "backup", description = ["Backup the database to a file"])
  fun backup(@Parameters(paramLabel = "PATH") path: String): Int {
    val req = CliRequest(command = "db", action = "backup", targets = listOf(path))
    return OutputFormatter.print(VectorIPC.transmit(req), parent.json)
  }

  @Command(name = "restore", description = ["Restore the database from a file"])
  fun restore(@Parameters(paramLabel = "PATH") path: String): Int {
    val req = CliRequest(command = "db", action = "restore", targets = listOf(path))
    return OutputFormatter.print(VectorIPC.transmit(req), parent.json)
  }

  @Command(name = "reset", description = ["Wipe all data and recreate the database schema"])
  fun reset(
      @Option(names = ["--force", "-f"], description = ["Skip confirmation"]) force: Boolean = false
  ): Int {
    if (!force) {
      print(
          "Are you sure you want to RESET the database? All modules and scopes will be lost. (y/N): ")
      val input = readLine()
      if (input?.lowercase() != "y") {
        println("Operation cancelled.")
        return 0
      }
    }

    val req = CliRequest(command = "db", action = "reset")
    return OutputFormatter.print(VectorIPC.transmit(req), parent.json)
  }
}

@Command(name = "log", description = ["Stream or clear framework logs"])
class LogCommand {
  @ParentCommand lateinit var parent: Cli

  @Command(name = "cat", description = ["Dump logs and exit"])
  fun cat(
      @Option(names = ["-v", "--verbose"], description = ["Read verbose daemon log"])
      verbose: Boolean
  ): Int {
    val req =
        CliRequest(
            command = "log",
            action = "stream",
            options = mapOf("verbose" to verbose, "follow" to false))
    VectorIPC.transmit(req)
    return 0
  }

  @Command(name = "tail", description = ["Follow logs in real-time"])
  fun tail(
      @Option(names = ["-v", "--verbose"], description = ["Follow verbose daemon log"])
      verbose: Boolean
  ): Int {
    val req =
        CliRequest(
            command = "log",
            action = "stream",
            options = mapOf("verbose" to verbose, "follow" to true))
    VectorIPC.transmit(req)
    return 0
  }

  @Command(name = "clear", description = ["Clear log buffers"])
  fun clear(@Option(names = ["-v", "--verbose"]) verbose: Boolean): Int {
    val req = CliRequest(command = "log", action = "clear", options = mapOf("verbose" to verbose))
    return OutputFormatter.print(VectorIPC.transmit(req), parent.json)
  }
}

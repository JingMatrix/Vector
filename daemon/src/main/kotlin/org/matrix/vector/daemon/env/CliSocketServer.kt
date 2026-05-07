package org.matrix.vector.daemon.env

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.launch
import org.matrix.vector.daemon.*
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.ipc.CliHandler

private const val TAG = "VectorCliSever"

object CliSocketServer {

  private var isRunning = false

  fun start() {
    if (isRunning) return
    isRunning = true

    val serverThread = Thread {
      // Keep these references outside the loop to prevent GC from closing them
      var rootSocket: LocalSocket? = null
      var server: LocalServerSocket? = null
      var socketFile: File? = null

      try {
        val cliSocketPath: String = FileSystem.setupCli()
        socketFile = File(cliSocketPath)

        // Create a standard LocalSocket
        rootSocket = LocalSocket()
        // Bind it to the filesystem path
        val address = LocalSocketAddress(cliSocketPath, LocalSocketAddress.Namespace.FILESYSTEM)
        rootSocket.bind(address)

        // LocalServerSocket(FileDescriptor) requires the FD to already be listening.
        Os.listen(rootSocket.fileDescriptor, 50)
        // Wrap the underlying FileDescriptor into a ServerSocket
        server = LocalServerSocket(rootSocket.fileDescriptor)

        Log.d(TAG, "CLI server started at $cliSocketPath")

        while (!Thread.currentThread().isInterrupted) {
          try {
            val clientSocket = server.accept()
            VectorDaemon.scope.launch { handleClient(clientSocket) }
          } catch (e: IOException) {
            if (Thread.currentThread().isInterrupted) break
            Log.w(TAG, "Error accepting client", e)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Fatal CLI Server error", e)
      } finally {
        try {
          server?.close()
          rootSocket?.close()
        } catch (ignored: Exception) {}

        if (socketFile?.exists() == true) {
          socketFile.delete()
        }
        isRunning = false
        Log.d(TAG, "CLI server stopped")
      }
    }

    serverThread.name = "VectorCliListener"
    serverThread.priority = Thread.MIN_PRIORITY
    serverThread.start()
  }

  private fun handleClient(socket: LocalSocket) {
    try {
      val input = DataInputStream(socket.inputStream)
      val output = DataOutputStream(socket.outputStream)

      // Read & Verify Security Token (UUID MSB/LSB)
      val msb = input.readLong()
      val lsb = input.readLong()
      if (msb != BuildConfig.CLI_TOKEN_MSB || lsb != BuildConfig.CLI_TOKEN_LSB) {
        socket.close()
        return
      }

      val requestJson = input.readUTF()
      val request = VectorIPC.gson.fromJson(requestJson, CliRequest::class.java)

      // Intercept Log Streaming specifically before CliHandler
      if (request.command == "log" && request.action == "stream") {
        val verbose = request.options["verbose"] as? Boolean ?: false
        val logFile = if (verbose) LogcatMonitor.getVerboseLog() else LogcatMonitor.getModulesLog()

        if (logFile != null && logFile.exists()) {
          val response = CliResponse(success = true, isFdAttached = true)
          output.writeUTF(VectorIPC.gson.toJson(response))

          // Open file and get raw FileDescriptor
          val fis = FileInputStream(logFile)
          val fd = fis.fd

          // Attach FD to the next write operation
          socket.setFileDescriptorsForSend(arrayOf(fd))
          output.write(1) // Trigger byte to "carry" the ancillary FD data

          // fis is closed when the socket/method finishes
          return
        } else {
          output.writeUTF(
              VectorIPC.gson.toJson(CliResponse(success = false, error = "Log file not found.")))
          return
        }
      }

      // Standard commands go to CliHandler as usual
      val response = CliHandler.execute(request)
      output.writeUTF(VectorIPC.gson.toJson(response))
    } finally {
      socket.close()
    }
  }
}

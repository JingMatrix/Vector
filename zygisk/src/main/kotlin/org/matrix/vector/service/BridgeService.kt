package org.matrix.vector.service

import android.app.ActivityThread
import android.os.Binder
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.Parcel
import android.os.Process
import hidden.HiddenApiBridge.Binder_allowBlocking
import hidden.HiddenApiBridge.Context_getActivityToken
import org.matrix.vector.ipc.IVectorDaemon
import org.matrix.vector.util.Log
import org.matrix.vector.impl.core.VectorServiceClient

/**
 * Manages manual Binder transactions for the Vector framework.
 *
 * This service is not registered in ServiceManager. Instead, the Zygisk native module intercepts
 * [Binder.execTransact] and redirects calls with the [TRANSACTION_CODE] to this class.
 */
object BridgeService {
    private const val TRANSACTION_CODE =
        ('_'.code shl 24) or ('V'.code shl 16) or ('E'.code shl 8) or 'C'.code
    private const val TAG = "VectorZygiskBridge"

    /** Actions supported by the manual IPC bridge. */
    private enum class Action {
        UNKNOWN,
        SEND_BINDER, // Daemon sending the system service binder
        GET_BINDER, // Process requesting its specific application service
    }

    private class Connection(val binder: IBinder) : DeathRecipient {
        val service: IVectorDaemon = IVectorDaemon.Stub.asInterface(binder)

        override fun binderDied() {
            synchronized(BridgeService) {
                if (connection === this) connection = null
            }
            unlink()
        }

        fun unlink() {
            runCatching { binder.unlinkToDeath(this, 0) }
        }
    }

    @Volatile private var connection: Connection? = null
    private val connectedService: IVectorDaemon?
        get() = connection?.service

    private val systemServerLifeToken = Binder()

    /** Returns the active Vector daemin service interface. */
    @JvmStatic fun getService(): IVectorDaemon? = connectedService

    /**
     * Initializes the client-side connection to the Vector daemin service.
     *
     * @param binder The raw binder for [IVectorDaemon].
     */
    @Synchronized
    private fun receiveFromBridge(binder: IBinder?): Boolean {
        if (binder == null) {
            Log.e(TAG, "Received null binder from bridge.")
            return false
        }
        // This transaction came from root. All nested calls must identify system_server itself.
        val token = Binder.clearCallingIdentity()
        val next = Connection(Binder_allowBlocking(binder))
        try {
            if (connection?.binder == binder && binder.isBinderAlive) return true
            binder.linkToDeath(next, 0)
            val framework =
                next.service.attachProcess(
                    Process.myUid(),
                    Process.myPid(),
                    "system",
                    systemServerLifeToken,
                ) ?: return false
            if (!VectorServiceClient.reconnectSystemServer(framework, next.service)) return false

            val activityThread = ActivityThread.currentActivityThread()
            val at = activityThread.applicationThread as android.app.IApplicationThread
            val systemCtx = activityThread.systemContext
            next.service.dispatchSystemServerContext(
                at.asBinder(),
                Context_getActivityToken(systemCtx),
            )
            check(binder.isBinderAlive) { "Daemon died during system server reattachment" }
            val previous = connection
            connection = next
            previous?.unlink()
            Log.i(TAG, "Vector daemon service binder linked; system server registered.")
            return true
        } catch (failure: Throwable) {
            Log.e(TAG, "Cannot reattach system server to Vector daemon", failure)
            return false
        } finally {
            if (connection !== next) next.unlink()
            Binder.restoreCallingIdentity(token)
        }
    }

    /** Handles manual parcel transactions. Called via reflection/JNI from the native hook. */
    @JvmStatic
    fun onTransact(data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return try {
            val actionIdx = data.readInt()
            val action = Action.values().getOrElse(actionIdx) { Action.UNKNOWN }

            Log.d(TAG, "onTransact: action=$action, callerUid=${Binder.getCallingUid()}")

            when (action) {
                Action.SEND_BINDER -> {
                    // Only allow root (UID 0) to push the initial service binder
                    if (Binder.getCallingUid() == 0) {
                        val attached = receiveFromBridge(data.readStrongBinder())
                        reply?.writeNoException()
                        attached
                    } else false
                }

                Action.GET_BINDER -> {
                    val processName = data.readString()
                    val heartBeat = data.readStrongBinder()
                    val appService =
                        connectedService?.attachProcess(
                            Binder.getCallingUid(),
                            Binder.getCallingPid(),
                            processName,
                            heartBeat,
                        )

                    if (appService != null && reply != null) {
                        reply.writeNoException()
                        reply.writeStrongBinder(appService.asBinder())
                        true
                    } else false
                }
                else -> false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling bridge transaction", e)
            false
        }
    }

    /**
     * Entry point for the JNI hook in [IPCBridge.cpp].
     *
     * @param obj The Binder object being called.
     * @param code The transaction code.
     * @param dataObj Native pointer to the data Parcel.
     * @param replyObj Native pointer to the reply Parcel.
     * @param flags Transaction flags.
     * @return True if the transaction was handled.
     */
    @JvmStatic
    fun execTransact(obj: IBinder, code: Int, dataObj: Long, replyObj: Long, flags: Int): Boolean {
        if (code != TRANSACTION_CODE) return false

        val data = dataObj.asParcel()
        val reply = replyObj.asParcel()

        if (data == null || reply == null) {
            Log.w(TAG, "Transaction dropped: null parcel pointers.")
            return false
        }

        return try {
            onTransact(data, reply, flags)
        } catch (e: Exception) {
            if (flags and IBinder.FLAG_ONEWAY == 0) {
                reply.setDataPosition(0)
                reply.writeException(e)
            }
            Log.e(TAG, "Exception during execTransact", e)
            true // We handled it, even if by returning an exception
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

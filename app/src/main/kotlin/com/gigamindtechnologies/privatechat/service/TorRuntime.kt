package com.gigamindtechnologies.privatechat.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import com.gigamindtechnologies.privatechat.core.tor.TorControlClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.torproject.jni.TorService
import java.io.File
import java.io.IOException

/**
 * Runs the Tor client that is built into the app (tor-android) and opens our own control
 * connection to it over its private Unix socket.
 */
class TorRuntime(private val context: Context) {
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    /** Starts Tor and returns a control connection once Tor has finished bootstrapping. */
    suspend fun start(onProgress: (Int) -> Unit): TorControlClient {
        withContext(Dispatchers.Main) {
            bound = context.bindService(Intent(context, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        if (!bound) throw IOException("Could not start Tor")
        return withContext(Dispatchers.IO) {
            val control = connect(File(File(context.getDir("TorService", Context.MODE_PRIVATE), "data"), "ControlSocket"))
            try {
                control.authenticate()
                while (true) {
                    val progress = control.bootstrapProgress()
                    onProgress(progress)
                    if (progress >= 100) break
                    delay(500)
                }
                control
            } catch (e: Exception) {
                control.close()
                throw e
            }
        }
    }

    fun stop() {
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
    }

    private suspend fun connect(socketFile: File): TorControlClient {
        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            if (socketFile.exists()) {
                val socket = LocalSocket()
                try {
                    socket.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                    return TorControlClient(socket.inputStream, socket.outputStream, socket)
                } catch (e: IOException) {
                    runCatching { socket.close() }
                }
            }
            if (System.currentTimeMillis() > deadline) throw IOException("Tor did not start")
            delay(250)
        }
    }
}

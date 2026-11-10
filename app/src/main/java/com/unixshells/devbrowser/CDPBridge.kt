package com.unixshells.devbrowser

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Bridges Chrome DevTools Protocol between the WebView's Unix domain socket
 * and local TCP ports that the DevTools frontend can connect to.
 *
 * Handles the origin check issue: WebView's CDP server rejects WebSocket
 * connections from origins it doesn't trust. We intercept the HTTP upgrade
 * request and remove the Origin header so the connection is accepted.
 */
class CDPBridge(
    private val httpPort: Int = 9222,
    private val wsPort: Int = 9223
) {
    companion object {
        private const val TAG = "CDPBridge"
        private const val BUFFER_SIZE = 65536
    }

    private var httpServer: ServerSocket? = null
    private var wsServer: ServerSocket? = null
    private var socketName: String? = null
    @Volatile private var running = false

    fun start(pid: Int) {
        socketName = "webview_devtools_remote_$pid"
        running = true
        Log.d(TAG, "Starting CDP bridge - HTTP:$httpPort WS:$wsPort socket:$socketName")

        thread(name = "cdp-http", isDaemon = true) { runProxy(httpPort) }
        thread(name = "cdp-ws", isDaemon = true) { runProxy(wsPort) }

        Log.d(TAG, "CDP bridge started")
    }

    fun stop() {
        running = false
        try { httpServer?.close() } catch (_: Exception) {}
        try { wsServer?.close() } catch (_: Exception) {}
        Log.d(TAG, "CDP bridge stopped")
    }

    private fun runProxy(port: Int) {
        try {
            // 보안 수정: 이 포트는 인증 없이 브라우저를 원격 조종(쿠키 탈취, JS 실행)할 수 있다.
            // 예전에는 ServerSocket(port)라서 0.0.0.0에 열려 같은 와이파이·VPN의 누구나 접속할 수 있었다.
            // 이 기기 안(127.0.0.1)에서만 접속되도록 루프백 주소에 묶는다.
            val server = ServerSocket(port, 50, InetAddress.getLoopbackAddress())
            if (port == httpPort) httpServer = server else wsServer = server
            server.reuseAddress = true
            Log.d(TAG, "Proxy listening on port $port")

            while (running) {
                val client: Socket
                try {
                    client = server.accept()
                } catch (e: IOException) {
                    if (running) Log.e(TAG, "Accept failed on port $port: ${e.message}")
                    break
                }
                thread(name = "cdp-conn-$port", isDaemon = true) {
                    handleConnection(client)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proxy on port $port failed: ${e.message}")
        }
    }

    private fun handleConnection(client: Socket) {
        val name = socketName ?: return
        var localSocket: LocalSocket? = null

        try {
            localSocket = LocalSocket()
            localSocket.connect(
                LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)
            )

            Log.d(TAG, "Connected to WebView socket, proxying...")

            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()
            val localIn = localSocket.inputStream
            val localOut = localSocket.outputStream

            // Read the first chunk from the client to check if it's a WebSocket
            // upgrade request. If so, strip the Origin header to bypass the
            // WebView's origin check.
            val firstChunk = ByteArray(BUFFER_SIZE)
            val firstRead = clientIn.read(firstChunk)
            if (firstRead <= 0) {
                client.close()
                localSocket.close()
                return
            }

            val firstData = String(firstChunk, 0, firstRead)

            val modifiedData = if (firstData.contains("Upgrade: websocket", ignoreCase = true)) {
                // Strip the Origin header
                val lines = firstData.split("\r\n").toMutableList()
                val filtered = lines.filter { line ->
                    !line.startsWith("Origin:", ignoreCase = true)
                }
                val result = filtered.joinToString("\r\n")
                Log.d(TAG, "Stripped Origin header from WebSocket upgrade")
                result
            } else {
                firstData
            }

            // Send the (possibly modified) first chunk to WebView
            localOut.write(modifiedData.toByteArray())
            localOut.flush()

            // Now pipe the rest bidirectionally
            val toLocal = thread(name = "cdp-to-local", isDaemon = true) {
                pipe(clientIn, localOut, "client->webview")
            }

            val toClient = thread(name = "cdp-to-client", isDaemon = true) {
                pipe(localIn, clientOut, "webview->client")
            }

            toLocal.join()
            toClient.join()

        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { localSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, label: String) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // Connection closed
        }
        Log.d(TAG, "Pipe $label closed")
    }
}

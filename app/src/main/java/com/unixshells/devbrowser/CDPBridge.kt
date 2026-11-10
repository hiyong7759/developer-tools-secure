package com.unixshells.devbrowser

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
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

    // 열려 있는 연결. stop() 때 함께 닫아, 닫은 뒤에도 남은 연결로 조종당하지 않게 한다.
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    /**
     * 개발자 도구를 여는 동안에만 호출한다(MainActivity.showDevTools).
     * 포트는 이 함수 안에서 바로 열린 상태가 되어, 곧바로 /json을 조회해도 연결된다.
     * 닫았다가 다시 열 수 있도록 stop() 뒤에 새 CDPBridge를 만들어 start()한다.
     */
    fun start(pid: Int) {
        socketName = "webview_devtools_remote_$pid"
        running = true
        Log.d(TAG, "Starting CDP bridge - HTTP:$httpPort WS:$wsPort socket:$socketName")

        httpServer = openLoopbackServer(httpPort)
        wsServer = openLoopbackServer(wsPort)

        httpServer?.let { server -> thread(name = "cdp-http", isDaemon = true) { acceptLoop(server) } }
        wsServer?.let { server -> thread(name = "cdp-ws", isDaemon = true) { acceptLoop(server) } }

        Log.d(TAG, "CDP bridge started")
    }

    fun stop() {
        running = false
        try { httpServer?.close() } catch (_: Exception) {}
        try { wsServer?.close() } catch (_: Exception) {}
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
        Log.d(TAG, "CDP bridge stopped")
    }

    private fun openLoopbackServer(port: Int): ServerSocket? {
        return try {
            // 보안 수정: 이 포트는 인증 없이 브라우저를 원격 조종(쿠키 탈취, JS 실행)할 수 있다.
            // 예전에는 ServerSocket(port)라서 0.0.0.0에 열려 같은 와이파이·VPN의 누구나 접속할 수 있었다.
            // 이 기기 안에서만 접속되도록 루프백 주소에 묶는다.
            // getLoopbackAddress()는 기기에 따라 ::1(IPv6)을 돌려주는데, 앱은 127.0.0.1(IPv4)로
            // 접속하므로 연결이 거부되어 개발자 도구가 먹통이 된다. 그래서 127.0.0.1을 직접 지정한다.
            // reuseAddress는 bind 전에 켜야 한다. 그래야 닫자마자 다시 열어도 포트 충돌이 없다.
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
            }.also { Log.d(TAG, "Proxy listening on port $port") }
        } catch (e: Exception) {
            Log.e(TAG, "Proxy on port $port failed: ${e.message}")
            null
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        val port = server.localPort
        while (running) {
            val client: Socket
            try {
                client = server.accept()
            } catch (e: IOException) {
                if (running) Log.e(TAG, "Accept failed on port $port: ${e.message}")
                break
            }
            clients.add(client)
            thread(name = "cdp-conn-$port", isDaemon = true) {
                handleConnection(client)
            }
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
            clients.remove(client)
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

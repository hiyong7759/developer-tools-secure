package com.unixshells.devbrowser

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Local HTTP server that serves the Chrome DevTools frontend files
 * from the app's assets directory.
 *
 * If bundled assets aren't available, it redirects to the hosted
 * Chrome DevTools frontend on appspot.
 */
class DevToolsServer(
    private val context: Context,
    port: Int = 9224
) : NanoHTTPD(
    // 보안 수정: NanoHTTPD(port)는 0.0.0.0에 열려 같은 와이파이·VPN의 누구나 이 서버에 접속할 수 있었다.
    // 앱 안의 WebView만 쓰므로 이 기기 안(127.0.0.1)에서만 접속되도록 묶는다.
    "127.0.0.1",
    port
) {

    // Constants are in the companion object at the bottom of the class

    private val hasBundledAssets: Boolean by lazy {
        try {
            val files = context.assets.list(DEVTOOLS_ASSETS_PATH)
            files != null && files.isNotEmpty()
        } catch (e: IOException) {
            false
        }
    }

    override fun serve(session: IHTTPSession): Response {
        var uri = session.uri
        Log.d(TAG, "Request: $uri")

        // Default entry point
        if (uri == "/" || uri.isEmpty()) {
            uri = "/devtools_app.html"
        }

        // If we have bundled assets, serve them
        if (hasBundledAssets) {
            return serveBundled(uri, session)
        }

        // Otherwise redirect to hosted DevTools
        return serveHostedRedirect(uri, session)
    }

    private fun serveBundled(uri: String, @Suppress("UNUSED_PARAMETER") session: IHTTPSession): Response {
        val assetPath = "$DEVTOOLS_ASSETS_PATH${uri}"

        return try {
            val inputStream = context.assets.open(assetPath)
            val mimeType = getMimeType(uri)

            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: IOException) {
            // Try as directory index
            try {
                val inputStream = context.assets.open("$assetPath/index.html")
                newChunkedResponse(Response.Status.OK, "text/html", inputStream)
            } catch (e2: IOException) {
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found: $uri"
                )
            }
        }
    }

    companion object {
        private const val TAG = "DevToolsServer"
        private const val DEVTOOLS_ASSETS_PATH = "devtools_frontend"
        private const val HOSTED_DEVTOOLS_BASE =
            "https://chrome-devtools-frontend.appspot.com/serve/file/@main"

        // Panel hiding script is served from assets/devtools_frontend/devbrowser_hide_panels.js
        // and injected via <script src> tag into devtools_app.html at serve time.
    }

    private fun serveHostedRedirect(uri: String, session: IHTTPSession): Response {
        // For the main HTML page, serve a shim that loads from CDN
        // but connects to our local WebSocket
        if (uri.endsWith(".html")) {
            val wsParam = session.parameters["ws"]?.firstOrNull() ?: ""
            val hostedUrl = "$HOSTED_DEVTOOLS_BASE${uri}" +
                if (wsParam.isNotEmpty()) "?ws=$wsParam" else ""

            val shimHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>
                        body {
                            background: #1e1e2e;
                            color: #cdd6f4;
                            font-family: -apple-system, sans-serif;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            height: 100vh;
                            margin: 0;
                            text-align: center;
                        }
                        .container { padding: 20px; }
                        h2 { color: #4fc3f7; font-size: 18px; }
                        p { color: #888; font-size: 14px; line-height: 1.6; }
                        a { color: #4fc3f7; }
                        .loading { font-size: 24px; margin-bottom: 16px; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="loading">&#x1f527;</div>
                        <h2>DevTools Frontend Not Bundled</h2>
                        <p>
                            Run the fetch script to bundle DevTools locally:<br>
                            <code>./scripts/fetch-devtools-frontend.sh</code>
                        </p>
                        <p>
                            Or use the hosted version:<br>
                            <a href="$hostedUrl">Open Hosted DevTools</a>
                        </p>
                        <p style="margin-top: 20px; font-size: 12px; color: #555;">
                            The hosted version requires internet access and may<br>
                            have CORS limitations with the local WebSocket proxy.
                        </p>
                    </div>
                </body>
                </html>
            """.trimIndent()

            return newFixedLengthResponse(Response.Status.OK, "text/html", shimHtml)
        }

        // For other resources, proxy/redirect to hosted
        val redirect = newFixedLengthResponse(
            Response.Status.REDIRECT,
            MIME_PLAINTEXT,
            "Redirecting to hosted DevTools"
        )
        redirect.addHeader("Location", "$HOSTED_DEVTOOLS_BASE$uri")
        return redirect
    }

    private fun getMimeType(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".mjs") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".ttf") -> "font/ttf"
            path.endsWith(".wasm") -> "application/wasm"
            path.endsWith(".map") -> "application/json"
            else -> "application/octet-stream"
        }
    }
}

package com.unixshells.devbrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import android.util.Log
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DevBrowser"
        private const val CDP_HTTP_PORT = 9222
        private const val CDP_WS_PORT = 9223
        private const val DEVTOOLS_PORT = 9224
        // 배율 메뉴에서 고를 수 있는 값(%)
        private val ZOOM_STEPS = intArrayOf(75, 90, 100, 110, 125, 150)
    }

    // Views
    private lateinit var browserContainer: FrameLayout
    private lateinit var devtoolsWebView: WebView
    private lateinit var urlBar: EditText
    private lateinit var contentLayout: LinearLayout
    private lateinit var divider: View
    private lateinit var btnDevTools: ImageButton
    private lateinit var btnDock: ImageButton
    private lateinit var btnDesktopToggle: ImageButton
    private lateinit var btnTabs: ImageButton
    private lateinit var tabStrip: HorizontalScrollView
    private lateinit var tabContainer: LinearLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    // State
    private lateinit var tabManager: TabManager
    private var cdpBridge: CDPBridge? = null
    private var devToolsServer: DevToolsServer? = null
    private var isDevToolsVisible = false
    private var isTabStripVisible = false
    private var dockMode = DockMode.BOTTOM
    private var devtoolsZoom = 100
    // 웹페이지가 차지하는 비율(0.2~0.8). px가 아니라 비율로 기억해야 창 크기가 바뀌어도 모양이 유지된다
    private var dockRatio = 0.5f
    private lateinit var prefs: SharedPreferences

    enum class DockMode { BOTTOM, RIGHT }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("devbrowser_settings", Context.MODE_PRIVATE)
        devtoolsZoom = prefs.getInt("devtools_zoom", 100)
        dockMode = if (prefs.getString("dock_mode", "bottom") == "right") DockMode.RIGHT else DockMode.BOTTOM
        dockRatio = prefs.getFloat("dock_ratio", 0.5f).coerceIn(0.2f, 0.8f)

        findViews()
        setupWindowInsets()
        setupTabManager()
        setupDevToolsWebView()
        setupToolbar()
        setupFindInPage()
        setupDividerDrag()

        // Enable WebView debugging for CDP
        WebView.setWebContentsDebuggingEnabled(true)

        startServers()

        // Create first tab
        val intentUrl = intent?.dataString
        tabManager.createTab(intentUrl ?: "https://www.google.com")
    }

    private fun setupWindowInsets() {
        val rootLayout = findViewById<LinearLayout>(R.id.rootLayout)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun findViews() {
        browserContainer = findViewById(R.id.browserContainer)
        devtoolsWebView = findViewById(R.id.devtoolsWebView)
        urlBar = findViewById(R.id.urlBar)
        contentLayout = findViewById(R.id.contentLayout)
        divider = findViewById(R.id.divider)
        btnDevTools = findViewById(R.id.btnDevTools)
        btnDock = findViewById(R.id.btnDock)
        btnDesktopToggle = findViewById(R.id.btnDesktopToggle)
        btnTabs = findViewById(R.id.btnTabs)
        tabStrip = findViewById(R.id.tabStrip)
        tabContainer = findViewById(R.id.tabContainer)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
    }

    private fun setupTabManager() {
        tabManager = TabManager(
            context = this,
            onTabChanged = { tab ->
                // Swap the WebView shown in the browser container
                browserContainer.removeAllViews()
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                browserContainer.addView(
                    tab.webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                urlBar.setText(tab.url)
                setupDownloadListener(tab.webView)
                updateTabStrip()
            },
            onTabListChanged = { updateTabStrip() },
            onPageStarted = { url -> urlBar.setText(url) },
            onPageFinished = { url -> urlBar.setText(url) },
            onTitleChanged = { _ -> updateTabStrip() }
        )

        tabManager.pageZoom = prefs.getInt("page_zoom", 100)
        tabManager.updateDesktopMode(prefs.getBoolean("desktop_mode_default", true))
    }

    private fun setupDownloadListener(webView: WebView) {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)

                val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                request.setTitle(filename)
                request.setDescription("Downloading $filename")
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, filename
                )

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Downloading: $filename", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupDevToolsWebView() {
        devtoolsWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            // 개발자 도구 화면(devtools_app.html)에는 viewport 메타가 없다. 와이드 뷰포트를 켜 두면
            // 약 980px 가상 폭으로 그린 뒤 패널 폭에 맞춰 확대·축소되어, 창이나 패널 크기를 바꿀 때마다
            // 글자 크기가 변했다. 끄면 패널 폭이 그대로 화면 폭이 되어 글자 크기가 일정하다.
            useWideViewPort = false
            loadWithOverviewMode = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        // 크기 조절은 사용자가 메뉴에서 직접 고른 값(기본 100%)으로만 한다
        devtoolsWebView.setInitialScale(TabManager.initialScalePercent(this, devtoolsZoom))

        devtoolsWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = false

            override fun onPageFinished(view: WebView, url: String) {
            }
        }

        devtoolsWebView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(TAG, "DevTools: ${consoleMessage.message()}")
                return true
            }
        }
    }

    private fun setupToolbar() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            tabManager.activeTab?.webView?.let { wv ->
                if (wv.canGoBack()) wv.goBack()
            }
        }

        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            tabManager.activeTab?.webView?.let { wv ->
                if (wv.canGoForward()) wv.goForward()
            }
        }

        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            tabManager.activeTab?.webView?.reload()
        }

        btnDesktopToggle.setOnClickListener { toggleDesktopMode() }
        btnDevTools.setOnClickListener { toggleDevTools() }
        btnDock.setOnClickListener {
            setDockMode(if (dockMode == DockMode.BOTTOM) DockMode.RIGHT else DockMode.BOTTOM)
        }
        updateDockIcon()
        btnTabs.setOnClickListener { toggleTabStrip() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                 event.action == KeyEvent.ACTION_DOWN)
            ) {
                navigateToUrl(urlBar.text.toString())
                urlBar.clearFocus()
                true
            } else false
        }

        // Update desktop mode button state
        updateDesktopToggleIcon()
    }

    private fun setupFindInPage() {
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.isNotEmpty()) {
                    tabManager.activeTab?.webView?.findAllAsync(query)
                } else {
                    tabManager.activeTab?.webView?.clearMatches()
                    findCount.text = ""
                }
            }
        })

        tabManager.activeTab?.webView?.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            findCount.text = if (numberOfMatches > 0) {
                "${activeMatchOrdinal + 1}/$numberOfMatches"
            } else {
                "0/0"
            }
        }

        findViewById<ImageButton>(R.id.findNext).setOnClickListener {
            tabManager.activeTab?.webView?.findNext(true)
        }

        findViewById<ImageButton>(R.id.findPrev).setOnClickListener {
            tabManager.activeTab?.webView?.findNext(false)
        }

        findViewById<ImageButton>(R.id.findClose).setOnClickListener {
            hideFindBar()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDividerDrag() {
        divider.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    val origin = IntArray(2)
                    contentLayout.getLocationOnScreen(origin)
                    val ratio = if (dockMode == DockMode.BOTTOM) {
                        (event.rawY - origin[1]) / (contentLayout.height - divider.height)
                    } else {
                        (event.rawX - origin[0]) / (contentLayout.width - divider.width)
                    }
                    dockRatio = ratio.coerceIn(0.2f, 0.8f)
                    applyDockLayout()
                }
                MotionEvent.ACTION_UP -> prefs.edit().putFloat("dock_ratio", dockRatio).apply()
            }
            true
        }
    }

    private fun startServers() {
        devToolsServer = DevToolsServer(this@MainActivity, DEVTOOLS_PORT).apply { start() }

        Log.d(TAG, "DevTools server started - DevTools:$DEVTOOLS_PORT")
    }

    /**
     * 원격 조종 포트(9222/9223)는 개발자 도구를 보는 동안에만 연다.
     * 앱을 켜 두는 내내 열어 두면 같은 기기의 다른 앱이 그동안 브라우저를 조종할 수 있다.
     * 9224(DevToolsServer)는 화면 파일만 제공하므로 앱 시작 때부터 둔다.
     */
    private fun startCdpBridge() {
        cdpBridge?.stop()
        val httpPort = prefs.getInt("cdp_http_port", CDP_HTTP_PORT)
        val wsPort = prefs.getInt("cdp_ws_port", CDP_WS_PORT)
        cdpBridge = CDPBridge(httpPort, wsPort).apply { start(Process.myPid()) }
    }

    private fun stopCdpBridge() {
        cdpBridge?.stop()
        cdpBridge = null
    }

    // ─── DevTools ────────────────────────────────────────

    private fun toggleDevTools() {
        if (isDevToolsVisible) hideDevTools() else showDevTools()
    }

    private fun showDevTools() {
        divider.visibility = View.VISIBLE
        devtoolsWebView.visibility = View.VISIBLE
        applyDockLayout()

        // 아래 /json 조회보다 먼저 포트가 열려 있어야 한다(start()가 끝나면 이미 LISTEN 상태)
        startCdpBridge()
        loadDevToolsFrontend()

        btnDevTools.setColorFilter(Color.parseColor("#4fc3f7"))
        isDevToolsVisible = true
        updateDockIcon()
    }

    /** 열려 있는 페이지의 ID를 찾아 개발자 도구 화면을 (다시) 불러온다. 배율을 바꿀 때도 이걸로 다시 그린다. */
    private fun loadDevToolsFrontend() {
        val cdpPort = prefs.getInt("cdp_http_port", CDP_HTTP_PORT)

        // Discover the browser WebView's page ID from CDP /json endpoint
        // Both HTTP and WS go through the same port (the Unix socket handles both)
        Thread {
            try {
                // Small delay to ensure the page is registered with CDP
                Thread.sleep(500)

                val conn = java.net.URL("http://127.0.0.1:$cdpPort/json").openConnection()
                    as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                Log.d(TAG, "CDP /json response: $json")

                // Parse page entries
                val idMatch = Regex(""""id"\s*:\s*"([^"]+)"""").findAll(json)
                val urlMatch = Regex(""""url"\s*:\s*"([^"]+)"""").findAll(json)

                val ids = idMatch.map { it.groupValues[1] }.toList()
                val urls = urlMatch.map { it.groupValues[1] }.toList()

                // Pick the page that isn't our devtools page
                var pageId = ids.firstOrNull() ?: ""
                for (i in ids.indices) {
                    if (i < urls.size && !urls[i].contains("devtools_app") && !urls[i].contains("9224")) {
                        pageId = ids[i]
                        break
                    }
                }

                Log.d(TAG, "Discovered page ID: $pageId")

                val devtoolsUrl = "http://localhost:$DEVTOOLS_PORT/devtools_app.html" +
                    "?ws=127.0.0.1:$cdpPort/devtools/page/$pageId"

                runOnUiThread {
                    Log.d(TAG, "Loading DevTools: $devtoolsUrl")
                    devtoolsWebView.loadUrl(devtoolsUrl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to discover page ID: ${e.message}", e)
                runOnUiThread {
                    val devtoolsUrl = "http://localhost:$DEVTOOLS_PORT/devtools_app.html" +
                        "?ws=127.0.0.1:$cdpPort"
                    Log.d(TAG, "Loading DevTools (fallback): $devtoolsUrl")
                    devtoolsWebView.loadUrl(devtoolsUrl)
                }
            }
        }.start()
    }

    /**
     * 사용자가 고른 도킹 위치(아래/오른쪽)와 분할 비율대로 다시 배치한다.
     * 창 크기가 바뀌어도 위치를 바꾸지 않는다. 비율은 weight로 걸어 두어 크기가 바뀌면 같은 비율로 따라간다.
     * (예전에는 가로면 오른쪽, 세로면 아래로 강제 전환해서 덱스 창을 조절할 때마다 선택이 덮어써졌다.)
     */
    private fun applyDockLayout() {
        val containerParams = browserContainer.layoutParams as LinearLayout.LayoutParams
        val devtoolsParams = devtoolsWebView.layoutParams as LinearLayout.LayoutParams
        val dividerSize = (6 * resources.displayMetrics.density).toInt()

        if (dockMode == DockMode.BOTTOM) {
            contentLayout.orientation = LinearLayout.VERTICAL
            containerParams.apply { weight = dockRatio; height = 0; width = ViewGroup.LayoutParams.MATCH_PARENT }
            devtoolsParams.apply { weight = 1f - dockRatio; height = 0; width = ViewGroup.LayoutParams.MATCH_PARENT }
            divider.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dividerSize)
        } else {
            contentLayout.orientation = LinearLayout.HORIZONTAL
            containerParams.apply { weight = dockRatio; width = 0; height = ViewGroup.LayoutParams.MATCH_PARENT }
            devtoolsParams.apply { weight = 1f - dockRatio; width = 0; height = ViewGroup.LayoutParams.MATCH_PARENT }
            divider.layoutParams = LinearLayout.LayoutParams(dividerSize, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        browserContainer.layoutParams = containerParams
        devtoolsWebView.layoutParams = devtoolsParams
        contentLayout.requestLayout()
    }

    private fun setDockMode(mode: DockMode) {
        dockMode = mode
        prefs.edit().putString("dock_mode", if (mode == DockMode.RIGHT) "right" else "bottom").apply()
        updateDockIcon()
        // 개발자 도구를 다시 불러오지 않고 자리만 옮긴다
        if (isDevToolsVisible) applyDockLayout()
    }

    private fun updateDockIcon() {
        btnDock.setImageResource(
            if (dockMode == DockMode.BOTTOM) R.drawable.ic_dock_bottom else R.drawable.ic_dock_right
        )
        btnDock.setColorFilter(Color.parseColor(if (isDevToolsVisible) "#4fc3f7" else "#aaaaaa"))
    }

    private fun hideDevTools() {
        val containerParams = browserContainer.layoutParams as LinearLayout.LayoutParams
        containerParams.apply { weight = 1f; height = 0; width = ViewGroup.LayoutParams.MATCH_PARENT }
        browserContainer.layoutParams = containerParams

        contentLayout.orientation = LinearLayout.VERTICAL
        divider.visibility = View.GONE
        devtoolsWebView.visibility = View.GONE
        devtoolsWebView.loadUrl("about:blank")
        stopCdpBridge()

        btnDevTools.setColorFilter(Color.parseColor("#aaaaaa"))
        isDevToolsVisible = false
        updateDockIcon()
    }

    /**
     * Hides DevTools panels that don't work with WebView's CDP implementation.
     * Runs after the DevTools frontend has loaded. Uses MutationObserver to
     * catch panels registered after initial load.
     */
    private fun sendInspectCommand() {
        if (!isDevToolsVisible) showDevTools()
        // Activate element picker via CDP — send Overlay.setInspectMode
        devtoolsWebView.evaluateJavascript("""
            (function() {
                // Trigger the inspect element mode in DevTools
                if (window.InspectorFrontendHost) {
                    window.InspectorFrontendHost.enterInspectElementMode();
                }
            })();
        """.trimIndent(), null)
    }

    // ─── Desktop Mode ────────────────────────────────────

    private fun toggleDesktopMode() {
        tabManager.updateDesktopMode(!tabManager.isDesktopMode)
        updateDesktopToggleIcon()
    }

    private fun updateDesktopToggleIcon() {
        if (tabManager.isDesktopMode) {
            btnDesktopToggle.setImageResource(R.drawable.ic_desktop)
            btnDesktopToggle.setColorFilter(Color.parseColor("#4fc3f7"))
        } else {
            btnDesktopToggle.setImageResource(R.drawable.ic_phone)
            btnDesktopToggle.setColorFilter(Color.parseColor("#aaaaaa"))
        }
    }

    // ─── Tabs ────────────────────────────────────────────

    private fun toggleTabStrip() {
        isTabStripVisible = !isTabStripVisible
        tabStrip.visibility = if (isTabStripVisible) View.VISIBLE else View.GONE
        btnTabs.setColorFilter(
            Color.parseColor(if (isTabStripVisible) "#4fc3f7" else "#aaaaaa")
        )
        if (isTabStripVisible) updateTabStrip()
    }

    private fun updateTabStrip() {
        tabContainer.removeAllViews()
        val tabs = tabManager.allTabs
        val activeTab = tabManager.activeTab

        for ((index, tab) in tabs.withIndex()) {
            val tabView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 4, 4, 4)
                val isActive = tab == activeTab
                setBackgroundColor(
                    if (isActive) Color.parseColor("#252545") else Color.TRANSPARENT
                )

                setOnClickListener { tabManager.switchToTab(index) }
            }

            val titleView = TextView(this).apply {
                text = if (tab.title.length > 20) tab.title.take(20) + "..." else tab.title
                textSize = 12f
                setTextColor(Color.parseColor("#cccccc"))
                maxLines = 1
                typeface = Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val closeBtn = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(Color.parseColor("#cccccc"))
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(
                    (28 * resources.displayMetrics.density).toInt(),
                    (28 * resources.displayMetrics.density).toInt()
                )
                setPadding(
                    (4 * resources.displayMetrics.density).toInt(),
                    (4 * resources.displayMetrics.density).toInt(),
                    (4 * resources.displayMetrics.density).toInt(),
                    (4 * resources.displayMetrics.density).toInt()
                )
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setOnClickListener { tabManager.closeTab(index) }
            }

            tabView.addView(titleView)
            tabView.addView(closeBtn)

            val params = LinearLayout.LayoutParams(
                (160 * resources.displayMetrics.density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { marginEnd = (2 * resources.displayMetrics.density).toInt() }

            tabContainer.addView(tabView, params)
        }

        // Add "+" button
        val addBtn = TextView(this).apply {
            text = "+"
            textSize = 18f
            setTextColor(Color.parseColor("#4fc3f7"))
            gravity = Gravity.CENTER
            setPadding(16, 0, 16, 0)
            setOnClickListener {
                tabManager.createTab("about:blank")
            }
        }
        tabContainer.addView(
            addBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    // ─── Find in Page ────────────────────────────────────

    private fun showFindBar() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        findInput.text.clear()
    }

    private fun hideFindBar() {
        findBar.visibility = View.GONE
        tabManager.activeTab?.webView?.clearMatches()
        findInput.text.clear()
        findCount.text = ""
    }

    // ─── Navigation ──────────────────────────────────────

    private fun navigateToUrl(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.startsWith("localhost") || input.startsWith("127.0.0.1") ||
                input.startsWith("10.0.2.2") -> "http://$input"
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${URLEncoder.encode(input, "UTF-8")}"
        }
        tabManager.activeTab?.webView?.loadUrl(url)
    }

    private fun viewPageSource() {
        val webView = tabManager.activeTab?.webView ?: return
        webView.evaluateJavascript(
            "(function() { return document.documentElement.outerHTML; })();"
        ) { html ->
            // html comes back as a JSON-encoded string
            val decoded = html
                ?.removeSurrounding("\"")
                ?.replace("\\n", "\n")
                ?.replace("\\t", "\t")
                ?.replace("\\\"", "\"")
                ?.replace("\\/", "/")
                ?.replace("\\\\", "\\")

            if (decoded != null) {
                val sourceTab = tabManager.createTab("about:blank")
                sourceTab.webView.loadDataWithBaseURL(
                    null,
                    """
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <style>
                            body {
                                background: #1e1e2e;
                                color: #cdd6f4;
                                font-family: monospace;
                                font-size: 13px;
                                padding: 8px;
                                white-space: pre-wrap;
                                word-wrap: break-word;
                            }
                        </style>
                    </head>
                    <body></body>
                    <script>
                        document.body.textContent = ${android.util.Base64.encodeToString(
                            decoded.toByteArray(), android.util.Base64.NO_WRAP
                        ).let { "atob('$it')" }};
                    </script>
                    </html>
                    """.trimIndent(),
                    "text/html",
                    "UTF-8",
                    null
                )
                sourceTab.title = "Source: ${webView.url}"
            }
        }
    }

    // ─── Menu ────────────────────────────────────────────

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_new_tab -> {
                    tabManager.createTab("about:blank")
                    if (!isTabStripVisible) toggleTabStrip()
                    true
                }
                R.id.menu_devtools -> {
                    toggleDevTools()
                    true
                }
                R.id.menu_view_source -> {
                    viewPageSource()
                    true
                }
                R.id.menu_find_in_page -> {
                    showFindBar()
                    true
                }
                R.id.menu_dock_bottom -> {
                    setDockMode(DockMode.BOTTOM)
                    true
                }
                R.id.menu_dock_right -> {
                    setDockMode(DockMode.RIGHT)
                    true
                }
                R.id.menu_inspect -> {
                    sendInspectCommand()
                    true
                }
                R.id.menu_devtools_zoom -> {
                    showZoomDialog("개발자 도구 배율", devtoolsZoom) { zoom ->
                        devtoolsZoom = zoom
                        prefs.edit().putInt("devtools_zoom", zoom).apply()
                        devtoolsWebView.setInitialScale(TabManager.initialScalePercent(this, zoom))
                        // 배율은 화면을 다시 불러와야 적용된다
                        if (isDevToolsVisible) loadDevToolsFrontend()
                    }
                    true
                }
                R.id.menu_page_zoom -> {
                    showZoomDialog("웹페이지 배율", tabManager.pageZoom) { zoom ->
                        prefs.edit().putInt("page_zoom", zoom).apply()
                        tabManager.updatePageZoom(zoom)
                    }
                    true
                }
                R.id.menu_close_tab -> {
                    tabManager.closeCurrentTab()
                    true
                }
                R.id.menu_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showZoomDialog(title: String, current: Int, onPicked: (Int) -> Unit) {
        val labels = ZOOM_STEPS.map { "$it%" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, ZOOM_STEPS.indexOf(current)) { dialog, which ->
                onPicked(ZOOM_STEPS[which])
                dialog.dismiss()
            }
            .show()
    }

    // ─── Lifecycle ───────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            findBar.visibility == View.VISIBLE -> hideFindBar()
            isDevToolsVisible -> hideDevTools()
            tabManager.activeTab?.webView?.canGoBack() == true -> {
                tabManager.activeTab?.webView?.goBack()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCdpBridge()
        devToolsServer?.stop()
        devtoolsWebView.destroy()
        tabManager.destroyAll()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 도킹 위치는 바꾸지 않는다. 사용자가 고른 위치와 비율 그대로 다시 배치만 한다.
        if (isDevToolsVisible) applyDockLayout()
    }

    override fun onResume() {
        super.onResume()
        // Apply settings that may have changed
        applySettings()
    }

    private fun applySettings() {
        val jsEnabled = prefs.getBoolean("javascript_enabled", true)
        val blockImages = prefs.getBoolean("block_images", false)
        val customUa = prefs.getString("custom_user_agent", "") ?: ""

        tabManager.allTabs.forEach { tab ->
            tab.webView.settings.javaScriptEnabled = jsEnabled
            tab.webView.settings.blockNetworkImage = blockImages
            if (customUa.isNotBlank()) {
                tab.webView.settings.userAgentString = customUa
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.let { url ->
            tabManager.createTab(url)
        }
    }
}

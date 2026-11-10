package com.unixshells.devbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.util.Log

data class Tab(
    val id: Int,
    val webView: WebView,
    var title: String = "New Tab",
    var url: String = "about:blank",
    var favicon: Bitmap? = null
)

class TabManager(
    private val context: Context,
    private val onTabChanged: (Tab) -> Unit,
    private val onTabListChanged: () -> Unit,
    private val onPageStarted: (String) -> Unit,
    private val onPageFinished: (String) -> Unit,
    private val onTitleChanged: (String) -> Unit
) {
    companion object {
        private const val TAG = "TabManager"

        /**
         * WebView.setInitialScale은 값을 "CSS 1px = 화면 실제 픽셀 1개" 기준(%)으로 해석한다.
         * 그래서 100을 그대로 넘기면 글자가 화면 밀도만큼(예: 2.6배) 작아진다.
         * 화면 밀도를 곱해야 배율 100%가 보통 크기(CSS 1px = 1dp)가 된다.
         */
        fun initialScalePercent(context: Context, zoomPercent: Int): Int =
            (zoomPercent * context.resources.displayMetrics.density).toInt()
        // 기기의 기본 UA에서 Chrome 버전을 찾지 못할 때만 쓰는 값
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val tabs = mutableListOf<Tab>()
    private var activeTabIndex = -1
    private var nextId = 0
    var isDesktopMode = true
        private set

    /** 웹페이지 배율(%). 모든 탭과 새 탭에 같은 값을 쓴다. */
    var pageZoom = 100

    /** 모바일 모드에서 웹페이지를 보여 줄 폭(dp). 창 폭이 이보다 좁으면 창 폭을 따른다. */
    var mobileWidthDp = 412

    /** 설정의 사용자 지정 User-Agent. 있으면 PC/모바일 모드의 UA보다 우선한다. */
    var customUserAgent = ""
        set(value) {
            field = value
            tabs.forEach { applyUserAgent(it.webView) }
        }

    val activeTab: Tab? get() = tabs.getOrNull(activeTabIndex)
    val tabCount: Int get() = tabs.size
    val allTabs: List<Tab> get() = tabs.toList()

    @SuppressLint("SetJavaScriptEnabled")
    fun createTab(url: String = "about:blank"): Tab {
        val webView = WebView(context)
        configureWebView(webView)

        val tab = Tab(id = nextId++, webView = webView, url = url)
        tabs.add(tab)
        switchToTab(tabs.size - 1)
        webView.loadUrl(url)
        onTabListChanged()
        return tab
    }

    fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val tab = tabs.removeAt(index)
        tab.webView.destroy()

        if (tabs.isEmpty()) {
            createTab()
        } else {
            val newIndex = when {
                index <= activeTabIndex && activeTabIndex > 0 -> activeTabIndex - 1
                index < activeTabIndex -> activeTabIndex
                else -> minOf(index, tabs.size - 1)
            }
            switchToTab(newIndex)
        }
        onTabListChanged()
    }

    fun closeCurrentTab() {
        if (activeTabIndex >= 0) closeTab(activeTabIndex)
    }

    fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        activeTabIndex = index
        onTabChanged(tabs[index])
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        applyViewSettings(webView)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                findTabByWebView(view)?.let { tab ->
                    tab.url = url
                    if (tab == activeTab) onPageStarted(url)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                findTabByWebView(view)?.let { tab ->
                    tab.url = url
                    if (tab == activeTab) onPageFinished(url)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                findTabByWebView(view)?.let { tab ->
                    tab.title = title ?: tab.url
                    if (tab == activeTab) onTitleChanged(tab.title)
                    onTabListChanged()
                }
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                findTabByWebView(view)?.let { tab ->
                    tab.favicon = icon
                    onTabListChanged()
                }
            }
        }
    }

    private fun findTabByWebView(webView: WebView): Tab? {
        return tabs.find { it.webView === webView }
    }

    /**
     * 현재 PC/모바일 모드와 배율을 이 탭의 WebView 설정에 반영한다.
     *
     * - 와이드 뷰포트(useWideViewPort)를 끄면 창(또는 모바일 프레임) 폭이 그대로 페이지 폭이 되어
     *   PC 모드가 창 폭 전체로 보인다. 예전에는 켜 둔 채 UA만 바꿔서, 반응형 사이트는 넓은 덱스 창에서도
     *   모바일 레이아웃이 나왔다.
     * - 배율은 와이드 뷰포트가 꺼져 있을 때만 페이지를 다시 배치한다(켜져 있으면 핀치 확대처럼 잘림, 실측).
     *   그래서 모바일 모드도 배율이 100%가 아니면 끈다. 100%일 때는 켜서 페이지의 viewport 메타를 따른다.
     */
    private fun applyViewSettings(webView: WebView) {
        webView.settings.apply {
            useWideViewPort = !isDesktopMode && pageZoom == 100
            loadWithOverviewMode = false
        }
        webView.setInitialScale(initialScalePercent(context, pageZoom))
        applyUserAgent(webView)
    }

    private fun applyUserAgent(webView: WebView) {
        webView.settings.userAgentString = when {
            customUserAgent.isNotBlank() -> customUserAgent
            isDesktopMode -> desktopUserAgent()
            else -> null
        }
    }

    /** 기기의 기본 UA에서 Android·Mobile 부분을 PC로 바꾼다. Chrome 버전은 현재 WebView를 따라간다. */
    private fun desktopUserAgent(): String {
        val chromeMajor = Regex("""Chrome/(\d+)""")
            .find(WebSettings.getDefaultUserAgent(context))?.groupValues?.get(1)
            ?: return DESKTOP_USER_AGENT
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeMajor.0.0.0 Safari/537.36"
    }

    /** 모든 탭(열어 둔 다른 탭 포함)과 새 탭에 적용하고 다시 불러온다. */
    fun updateDesktopMode(enabled: Boolean) {
        isDesktopMode = enabled
        tabs.forEach {
            applyViewSettings(it.webView)
            reloadForViewChange(it.webView)
        }
    }

    fun updatePageZoom(zoom: Int) {
        pageZoom = zoom
        // 배율은 페이지를 다시 불러와야 적용된다
        tabs.forEach {
            applyViewSettings(it.webView)
            reloadForViewChange(it.webView)
        }
    }

    /**
     * reload()는 이전에 보던 화면 배율을 그대로 복원한다. 125%에서 100%로 되돌리면 배치는 100%인데
     * 화면만 1.25배로 남아 페이지가 잘리므로(실측: iw=733, vv=1.25), 같은 주소를 새로 불러온다.
     */
    private fun reloadForViewChange(webView: WebView) {
        val url = webView.url
        if (url.isNullOrEmpty() || url == "about:blank") webView.reload() else webView.loadUrl(url)
    }

    fun destroyAll() {
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        activeTabIndex = -1
    }
}

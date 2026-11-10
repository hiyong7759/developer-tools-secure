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
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT

            userAgentString = if (isDesktopMode) DESKTOP_USER_AGENT else null
        }
        webView.setInitialScale(initialScalePercent(context, pageZoom))

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

    fun updateDesktopMode(enabled: Boolean) {
        isDesktopMode = enabled
        tabs.forEach { tab ->
            tab.webView.settings.userAgentString =
                if (enabled) DESKTOP_USER_AGENT else null
        }
        activeTab?.webView?.reload()
    }

    fun updatePageZoom(zoom: Int) {
        pageZoom = zoom
        tabs.forEach { it.webView.setInitialScale(initialScalePercent(context, zoom)) }
        // 배율은 페이지를 다시 불러와야 적용된다
        tabs.forEach { it.webView.reload() }
    }

    fun destroyAll() {
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        activeTabIndex = -1
    }
}

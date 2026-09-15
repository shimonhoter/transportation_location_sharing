package com.shimonhoter.ridelocationshare.ui

import android.webkit.JavascriptInterface

/**
 * JS-to-Kotlin bridge for assets/map.html, injected into the WebView as
 * `window.Android`. Callbacks arrive on a WebView-owned thread, not the
 * main thread, so callers must post back to the UI thread themselves.
 */
class MapBridge(
    private val onTap: ((lat: Double, lon: Double) -> Unit)? = null,
    private val onReady: (() -> Unit)? = null
) {
    @JavascriptInterface
    fun onMapTap(lat: Double, lon: Double) {
        onTap?.invoke(lat, lon)
    }

    @JavascriptInterface
    fun onMapReady() {
        onReady?.invoke()
    }
}

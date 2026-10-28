package com.shimonhoter.ridelocationshare.ui

import android.webkit.JavascriptInterface

/**
 * JS-to-Kotlin bridge for assets/map.html, injected into the WebView as
 * `window.Android`. Callbacks arrive on a WebView-owned thread, not the
 * main thread, so callers must post back to the UI thread themselves.
 * Only MainActivity uses this: alert-zone edits (docs/SPEC_EN.md 3.5) and
 * this device's own pickup-stop candidate edits (3.9), both made directly
 * on the map.
 */
class MapBridge(
    private val onZoneAdded: ((lat: Double, lon: Double) -> Unit)? = null,
    private val onZoneMoved: ((id: String, lat: Double, lon: Double) -> Unit)? = null,
    private val onZoneResized: ((id: String, radiusMeters: Double) -> Unit)? = null,
    private val onZoneDeleted: ((id: String) -> Unit)? = null,
    private val onStopAdded: ((lat: Double, lon: Double) -> Unit)? = null,
    private val onStopMoved: ((id: String, lat: Double, lon: Double) -> Unit)? = null,
    private val onStopDeleted: ((id: String) -> Unit)? = null
) {
    /** A tap on the map while alert-zone edit mode is active, outside any existing zone — places a new one there. */
    @JavascriptInterface
    fun onZoneAdded(lat: Double, lon: Double) {
        onZoneAdded?.invoke(lat, lon)
    }

    /** The zone's center-drag handle was released at a new position. */
    @JavascriptInterface
    fun onZoneMoved(id: String, lat: Double, lon: Double) {
        onZoneMoved?.invoke(id, lat, lon)
    }

    /** The zone's edge-drag (resize) handle was released, reporting the new radius. */
    @JavascriptInterface
    fun onZoneResized(id: String, radiusMeters: Double) {
        onZoneResized?.invoke(id, radiusMeters)
    }

    /** The zone's delete handle was tapped. */
    @JavascriptInterface
    fun onZoneDeleted(id: String) {
        onZoneDeleted?.invoke(id)
    }

    /** A tap on the map while pickup-stop edit mode is active, outside any existing stop candidate — places a new one there. */
    @JavascriptInterface
    fun onStopAdded(lat: Double, lon: Double) {
        onStopAdded?.invoke(lat, lon)
    }

    /** A stop candidate's drag handle was released at a new position. */
    @JavascriptInterface
    fun onStopMoved(id: String, lat: Double, lon: Double) {
        onStopMoved?.invoke(id, lat, lon)
    }

    /** A stop candidate's delete handle was tapped. */
    @JavascriptInterface
    fun onStopDeleted(id: String) {
        onStopDeleted?.invoke(id)
    }
}

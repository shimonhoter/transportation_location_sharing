package com.shimonhoter.ridelocationshare.ui

import android.net.Uri
import android.webkit.WebView

/**
 * Loads the shared assets/map.html (WebView + MapLibre GL JS) consistently
 * across MainActivity and MapPickerActivity — the native MapLibre Android
 * SDK was dropped for a blank-screen bug on some Mali GPUs
 * (docs/SPEC_EN.md 4.1).
 */
fun WebView.loadRideMap(
    bridge: MapBridge?,
    mode: String,
    centerLat: Double = 32.0853,
    centerLon: Double = 34.7818,
    zoom: Double = 13.0,
    radiusMeters: Double? = null
) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    if (bridge != null) {
        addJavascriptInterface(bridge, "Android")
    }
    val urlBuilder = Uri.parse("file:///android_asset/map.html").buildUpon()
        .appendQueryParameter("mode", mode)
        .appendQueryParameter("lat", centerLat.toString())
        .appendQueryParameter("lon", centerLon.toString())
        .appendQueryParameter("zoom", zoom.toString())
    if (radiusMeters != null) {
        urlBuilder.appendQueryParameter("radius", radiusMeters.toString())
    }
    loadUrl(urlBuilder.build().toString())
}

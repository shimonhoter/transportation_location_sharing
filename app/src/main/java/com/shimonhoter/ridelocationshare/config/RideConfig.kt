package com.shimonhoter.ridelocationshare.config

/**
 * Server address and shared token are intentionally fixed here rather than
 * exposed in the settings UI: every passenger's device must point at the
 * same server regardless of local configuration (see docs/SPEC_EN.md 4.3).
 */
data class RideServerConfig(val baseUrl: String, val token: String)

object RideConfig {
    private const val SERVER_BASE_URL = "https://ride-location-share.onrender.com"
    private const val AUTH_TOKEN = "ride-2026-shimon-secret"

    /** MapTiler API key for the streets-v4 style; empty falls back to the public MapLibre demo style. */
    const val MAPTILER_KEY = ""

    const val LOCATION_UPDATE_INTERVAL_MS = 5_000L
    const val MIN_MOVEMENT_METERS = 150.0
    const val WORK_CHECK_INTERVAL_MINUTES = 15L

    fun default(): RideServerConfig = RideServerConfig(SERVER_BASE_URL, AUTH_TOKEN)
}

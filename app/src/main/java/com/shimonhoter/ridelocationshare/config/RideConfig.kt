package com.shimonhoter.ridelocationshare.config

object RideConfig {
    /**
     * Realtime Database URL, shown at the top of the Firebase console's
     * Realtime Database page once the database is created (looks like
     * "https://<project-id>-default-rtdb.<region>.firebasedatabase.app").
     * Passed explicitly to FirebaseDatabase.getInstance() rather than
     * relying on google-services.json auto-detecting it, since the
     * downloaded config can predate the database being created.
     */
    // TODO: replace once the Realtime Database is created in the Firebase console —
    // copy the URL shown at the top of Build > Realtime Database.
    const val FIREBASE_DATABASE_URL = "https://REPLACE-ME.firebasedatabase.app"

    /** MapTiler API key for the streets-v4 style; empty falls back to the public MapLibre demo style. */
    const val MAPTILER_KEY = ""

    const val LOCATION_UPDATE_INTERVAL_MS = 5_000L
    const val MIN_MOVEMENT_METERS = 150.0
    const val WORK_CHECK_INTERVAL_MINUTES = 15L

    /** A ride location sample older than this is treated as "no active ride" (replaces the old server-side TTL). */
    const val STALE_AFTER_SECONDS = 90.0
}

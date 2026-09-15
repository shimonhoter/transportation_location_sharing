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
    const val FIREBASE_DATABASE_URL = "https://transportationlocationsharing-default-rtdb.europe-west1.firebasedatabase.app"

    const val MIN_MOVEMENT_METERS = 150.0
    const val WORK_CHECK_INTERVAL_MINUTES = 15L

    /** A ride location sample older than this is treated as "no active ride" (replaces the old server-side TTL). */
    const val STALE_AFTER_SECONDS = 90.0

    /**
     * Only devices moving faster than this are averaged into the displayed
     * ride location — distinguishes passengers already in the moving
     * vehicle from someone still walking toward the pickup point. Falls
     * back to averaging every fresh device when none currently qualify
     * (e.g. the ride is stopped at a red light), so the map never goes
     * blank just because the vehicle is momentarily stationary.
     */
    const val MOVING_SPEED_THRESHOLD_KMH = 7.0

    /** User-configurable via Settings (Prefs.locationUpdateIntervalSeconds); this is only the default/bounds. */
    const val DEFAULT_LOCATION_UPDATE_INTERVAL_SECONDS = 5
    const val MIN_LOCATION_UPDATE_INTERVAL_SECONDS = 3
    const val MAX_LOCATION_UPDATE_INTERVAL_SECONDS = 30
}

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
     * Only devices moving faster than this (or that moved this fast within
     * [RECENTLY_MOVING_GRACE_SECONDS]) are averaged into the displayed ride
     * location — distinguishes passengers already in the moving vehicle from
     * someone still walking toward the pickup point, or someone who has
     * since gotten off and is now on foot.
     */
    const val MOVING_SPEED_THRESHOLD_KMH = 7.0

    /**
     * A device that exceeded [MOVING_SPEED_THRESHOLD_KMH] this recently is
     * still treated as "in the vehicle" even while its instantaneous speed
     * is momentarily low — covers a brief stop (red light, traffic) so the
     * ride doesn't flicker out of the aggregate. A device that has been
     * below the threshold for longer than this (e.g. a passenger who got
     * off and is now walking) drops out of the aggregate entirely, instead
     * of its now-irrelevant, jittery position being averaged in or shown
     * outright.
     */
    const val RECENTLY_MOVING_GRACE_SECONDS = 90.0

    /**
     * Corroboration: when two or more devices are moving at once, each is
     * only trusted as (part of) the ride location if at least one OTHER
     * moving device is within this many meters of it. Speed alone can't
     * tell a passenger's private car apart from the shared ride — both
     * look like "left the origin, then moved fast" — but several devices
     * moving together near each other plausibly are the same vehicle,
     * while one of several movers off on its own isn't shown. A single,
     * lone mover has nothing to corroborate against and is always trusted
     * directly (see FirebaseLocationRepository.aggregate) — this only ever
     * adds a safety net when a second broadcaster is also active; the
     * reliable fix for someone who knows in advance they're taking their
     * own car is Prefs.isSkippedToday() ("not riding today").
     * User-configurable via Settings (Prefs.corroborationRadiusMeters);
     * this is only the default/bounds.
     */
    const val DEFAULT_CORROBORATION_RADIUS_METERS = 300
    const val MIN_CORROBORATION_RADIUS_METERS = 50
    const val MAX_CORROBORATION_RADIUS_METERS = 1000

    /** User-configurable via Settings (Prefs.locationUpdateIntervalSeconds); this is only the default/bounds. */
    const val DEFAULT_LOCATION_UPDATE_INTERVAL_SECONDS = 5
    const val MIN_LOCATION_UPDATE_INTERVAL_SECONDS = 3
    const val MAX_LOCATION_UPDATE_INTERVAL_SECONDS = 30

    /** How long local ride-location history is kept on-device for the ETA estimate (Prefs.historyEnabled), opt-in via Settings. */
    const val HISTORY_RETENTION_DAYS = 30

    /** Below this average speed, a live position/speed pair isn't trusted to extrapolate an ETA — falls back to the historical estimate instead. */
    const val MIN_SPEED_FOR_LIVE_ETA_KMH = 5.0
}

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
     * Stop-and-go traffic jam detection for the broadcast *start* condition
     * (BroadcastService.checkAutoStartCondition): a car crawling in traffic
     * can spend long stretches below [MOVING_SPEED_THRESHOLD_KMH] without
     * ever cleanly crossing it on a single GPS fix, unlike a pedestrian
     * whose speed stays smooth and bounded. Within a rolling window of the
     * last [TRAFFIC_JAM_WINDOW_SECONDS], if at least one sample shows a
     * near-complete stop ([TRAFFIC_JAM_STOP_SPEED_KMH] or below) AND at
     * least one shows a brief burst clearly faster than a walking pace
     * ([TRAFFIC_JAM_BURST_SPEED_KMH] or above) — i.e. genuine stop-start
     * jitter, not just a slow, steady walk — that's treated as being in a
     * vehicle and starts the broadcast, even though no single fix crossed
     * [MOVING_SPEED_THRESHOLD_KMH]. Crossing [MOVING_SPEED_THRESHOLD_KMH]
     * itself always starts the broadcast immediately regardless of this —
     * this only widens *when else* it also starts, it never narrows the
     * main threshold.
     */
    const val TRAFFIC_JAM_WINDOW_SECONDS = 90.0
    const val TRAFFIC_JAM_STOP_SPEED_KMH = 2.0
    const val TRAFFIC_JAM_BURST_SPEED_KMH = 12.0

    /**
     * A device that exceeded [MOVING_SPEED_THRESHOLD_KMH] this recently is
     * still treated as "in the vehicle" even while its instantaneous speed
     * is momentarily low — covers a brief stop (red light, traffic) so the
     * ride doesn't flicker out of the aggregate. A device that has been
     * below the threshold for longer than this (e.g. a passenger who got
     * off and is now walking) drops out of the aggregate entirely, instead
     * of its now-irrelevant, jittery position being averaged in or shown
     * outright.
     *
     * Also doubles as a stop condition for the device's own broadcast
     * (BroadcastService.handleLocationUpdate): once a broadcasting device
     * has gone this long without exceeding the speed threshold, it stops
     * broadcasting entirely — not just dropping out of the shown aggregate
     * — alongside the safety-timer stop, whichever comes first.
     */
    const val RECENTLY_MOVING_GRACE_SECONDS = 300.0

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
     * reliable fix for someone who knows they're taking their own car is
     * Prefs.activatePrivateCarMode() ("private car").
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

    /**
     * "Private car" mode (main-screen toggle): while active, blocks both
     * automatic and manual broadcasting from this device — for a passenger
     * who knows right now, not just in advance, that they're not on the
     * shared ride. Normally auto-reverts at the end of today's broadcast
     * window (Prefs.activatePrivateCarMode) rather than requiring the user
     * to remember to turn it back off; this fixed duration is only a
     * fallback for when that window has already passed or isn't active
     * today.
     */
    const val DEFAULT_PRIVATE_CAR_DURATION_MINUTES = 120

    /**
     * How long an alert-zone notification's sound loops for before
     * auto-stopping, if the user hasn't dismissed the full-screen alert
     * popup first — a safety cap, since the sound is meant to keep ringing
     * until acknowledged, not forever if the app/device is unattended.
     * User-configurable via Settings (Prefs.alertSoundDurationSeconds);
     * this is only the default/bounds.
     */
    const val DEFAULT_ALERT_SOUND_DURATION_SECONDS = 30
    const val MIN_ALERT_SOUND_DURATION_SECONDS = 5
    const val MAX_ALERT_SOUND_DURATION_SECONDS = 300

    /** Radius a new alert zone gets when placed by tapping the map (docs/SPEC_EN.md 3.5) — the drag-to-resize handle is the intended way to adjust it afterward. */
    const val DEFAULT_ALERT_ZONE_RADIUS_METERS = 150.0
}

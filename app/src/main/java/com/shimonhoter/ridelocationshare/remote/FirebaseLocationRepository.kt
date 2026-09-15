package com.shimonhoter.ridelocationshare.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.shimonhoter.ridelocationshare.config.RideConfig
import com.shimonhoter.ridelocationshare.util.GeoUtil
import kotlinx.coroutines.tasks.await

data class RideLocation(val lat: Double, val lon: Double, val nickname: String?, val ageSeconds: Double)

private data class DeviceSample(
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val nickname: String?,
    val ageSeconds: Double,
    val lastMovingAtMillis: Long
)

/**
 * Firebase Realtime Database replacement for the old stdlib HTTP server.
 * Every broadcasting device writes to its own child under
 * "rideLocation/devices/<uid>" (keyed by its stable anonymous-auth UID)
 * rather than a single shared value — with several passengers broadcasting
 * from different points (e.g. some still walking to the pickup spot while
 * others are already in the moving vehicle), last-write-wins on one flat
 * value would show whoever happened to post most recently, which is
 * meaningless. Instead the displayed location is an on-device aggregate:
 * the average position of devices moving faster than
 * [RideConfig.MOVING_SPEED_THRESHOLD_KMH], or that did so within
 * [RideConfig.RECENTLY_MOVING_GRACE_SECONDS] (i.e. plausibly inside the
 * moving vehicle, allowing for a brief stop like a red light). A device
 * that has never yet exceeded the threshold falls back to being averaged
 * in anyway (covers the first few fixes of a ride, before GPS speed has
 * caught up) — but once a device has been confirmed moving and then drops
 * below the threshold for longer than the grace window (e.g. a passenger
 * who got off and is now on foot), it is excluded from the aggregate
 * entirely rather than its now-irrelevant, jittery position dragging or
 * replacing the shown ride location. A moving device is further only
 * trusted if corroborated by at least one other moving device within
 * [RideConfig.DEFAULT_CORROBORATION_RADIUS_METERS] (see
 * Prefs.corroborationRadiusMeters) — speed alone can't tell a passenger's
 * private car apart from the shared ride, but a lone mover with nobody
 * else nearby isn't shown as the ride location. No history is kept: stale
 * per-device entries are filtered out by age, not stored (docs/SPEC_EN.md
 * section 5). Every device signs in anonymously rather than carrying a
 * per-user credential (the old shared-token model has no server-side
 * equivalent in Firebase).
 */
class FirebaseLocationRepository {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val devicesRef by lazy {
        FirebaseDatabase.getInstance(RideConfig.FIREBASE_DATABASE_URL).getReference("rideLocation/devices")
    }

    private suspend fun ensureSignedIn(): String {
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
        }
        return auth.currentUser!!.uid
    }

    suspend fun postLocation(lat: Double, lon: Double, speedKmh: Double, nickname: String?, lastMovingAtMillis: Long): Boolean {
        return try {
            val uid = ensureSignedIn()
            val data = mapOf(
                "lat" to lat,
                "lon" to lon,
                "speedKmh" to speedKmh,
                "nickname" to nickname,
                "updatedAt" to System.currentTimeMillis(),
                "lastMovingAt" to lastMovingAtMillis
            )
            devicesRef.child(uid).setValue(data).await()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Removes this device's own entry so it's excluded from the aggregate immediately, rather than waiting for it to go stale. */
    suspend fun clearOwnLocation() {
        try {
            val uid = ensureSignedIn()
            devicesRef.child(uid).removeValue().await()
        } catch (_: Exception) {
            // Best-effort: if this fails, the entry still expires on its own via the staleness check.
        }
    }

    /**
     * Attaches a live listener over all devices; the callback fires
     * immediately with the current aggregate and again on every subsequent
     * change to any device's entry. [corroborationRadiusMeters] is invoked
     * fresh on every firing (not just once at attach time) so a changed
     * Settings value applies immediately, matching how every other setting
     * in this app behaves.
     */
    fun observeLocation(corroborationRadiusMeters: () -> Int, onChange: (RideLocation?) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onChange(aggregate(snapshot, corroborationRadiusMeters()))
            }

            override fun onCancelled(error: DatabaseError) {
                onChange(null)
            }
        }
        devicesRef.addValueEventListener(listener)
        return listener
    }

    fun removeListener(listener: ValueEventListener) {
        devicesRef.removeEventListener(listener)
    }

    private fun aggregate(snapshot: DataSnapshot, corroborationRadiusMeters: Int): RideLocation? {
        val now = System.currentTimeMillis()
        val fresh = snapshot.children.mapNotNull { parseSample(it, now) }
        if (fresh.isEmpty()) return null

        val graceMillis = (RideConfig.RECENTLY_MOVING_GRACE_SECONDS * 1000).toLong()
        val hasEverMoved = fresh.filter { it.lastMovingAtMillis > 0L }
        val recentlyMoving = hasEverMoved.filter { now - it.lastMovingAtMillis <= graceMillis }

        // Corroboration: a moving device only counts if at least one OTHER
        // moving device is within corroborationRadiusMeters of it. Speed
        // alone can't tell a passenger's own private car apart from the
        // shared ride — both look like "left the origin, then moved fast" —
        // but several devices moving together near each other plausibly are
        // the same vehicle, while a lone mover isn't shown as the ride.
        val corroborated = recentlyMoving.filter { candidate ->
            recentlyMoving.any { other ->
                other !== candidate && GeoUtil.distanceMeters(candidate.lat, candidate.lon, other.lat, other.lon) <= corroborationRadiusMeters
            }
        }

        // Prefer corroborated, currently/recently moving devices. If none
        // qualify but some devices have never yet registered a moving
        // sample (e.g. the ride just started), average all fresh devices as
        // a startup fallback. If every fresh device HAS moved before but
        // none are corroborated right now (gotten off and on foot, or off
        // on their own uncorroborated), exclude them rather than show a
        // stale or unverified position.
        val chosen = when {
            corroborated.isNotEmpty() -> corroborated
            hasEverMoved.size < fresh.size -> fresh
            else -> return null
        }

        val avgLat = chosen.sumOf { it.lat } / chosen.size
        val avgLon = chosen.sumOf { it.lon } / chosen.size
        val freshestAgeSeconds = chosen.minOf { it.ageSeconds }
        val nickname = chosen.firstNotNullOfOrNull { it.nickname }
        return RideLocation(avgLat, avgLon, nickname, freshestAgeSeconds)
    }

    private fun parseSample(child: DataSnapshot, now: Long): DeviceSample? {
        val lat = child.child("lat").getValue(Double::class.java) ?: return null
        val lon = child.child("lon").getValue(Double::class.java) ?: return null
        val updatedAt = child.child("updatedAt").getValue(Long::class.java) ?: return null
        val ageSeconds = (now - updatedAt) / 1000.0
        if (ageSeconds > RideConfig.STALE_AFTER_SECONDS) return null
        val speedKmh = child.child("speedKmh").getValue(Double::class.java) ?: 0.0
        val nickname = child.child("nickname").getValue(String::class.java)
        val lastMovingAtMillis = child.child("lastMovingAt").getValue(Long::class.java) ?: 0L
        return DeviceSample(lat, lon, speedKmh, nickname, ageSeconds, lastMovingAtMillis)
    }
}

package com.shimonhoter.ridelocationshare.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.shimonhoter.ridelocationshare.config.RideConfig
import kotlinx.coroutines.tasks.await

data class RideLocation(val lat: Double, val lon: Double, val nickname: String?, val ageSeconds: Double)

private data class DeviceSample(val lat: Double, val lon: Double, val speedKmh: Double, val nickname: String?, val ageSeconds: Double)

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
 * [RideConfig.MOVING_SPEED_THRESHOLD_KMH] (i.e. plausibly inside the moving
 * vehicle), falling back to averaging every fresh device if none currently
 * qualify — e.g. everyone in the vehicle stopped at a red light, which must
 * not make the ride disappear from the map. No history is kept: stale
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

    suspend fun postLocation(lat: Double, lon: Double, speedKmh: Double, nickname: String?): Boolean {
        return try {
            val uid = ensureSignedIn()
            val data = mapOf(
                "lat" to lat,
                "lon" to lon,
                "speedKmh" to speedKmh,
                "nickname" to nickname,
                "updatedAt" to System.currentTimeMillis()
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
     * change to any device's entry.
     */
    fun observeLocation(onChange: (RideLocation?) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onChange(aggregate(snapshot))
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

    private fun aggregate(snapshot: DataSnapshot): RideLocation? {
        val now = System.currentTimeMillis()
        val fresh = snapshot.children.mapNotNull { parseSample(it, now) }
        if (fresh.isEmpty()) return null

        val moving = fresh.filter { it.speedKmh > RideConfig.MOVING_SPEED_THRESHOLD_KMH }
        val chosen = moving.ifEmpty { fresh }

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
        return DeviceSample(lat, lon, speedKmh, nickname, ageSeconds)
    }
}

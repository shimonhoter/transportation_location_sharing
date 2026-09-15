package com.shimonhoter.ridelocationshare.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.shimonhoter.ridelocationshare.config.RideConfig
import kotlinx.coroutines.tasks.await

data class RideLocation(val lat: Double, val lon: Double, val nickname: String?, val ageSeconds: Double)

/**
 * Firebase Realtime Database replacement for the old stdlib HTTP server:
 * a single "rideLocation" node holds only the latest sample, overwritten on
 * every write (no history — docs/SPEC_EN.md section 5). Every passenger's
 * device signs in anonymously (docs' shared-token model has no server-side
 * equivalent in Firebase, so security rules just require `auth != null`)
 * rather than carrying a per-user credential.
 */
class FirebaseLocationRepository {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val rideLocationRef by lazy {
        FirebaseDatabase.getInstance(RideConfig.FIREBASE_DATABASE_URL).getReference("rideLocation")
    }

    private suspend fun ensureSignedIn() {
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
        }
    }

    suspend fun postLocation(lat: Double, lon: Double, nickname: String?): Boolean {
        return try {
            ensureSignedIn()
            val data = mapOf(
                "lat" to lat,
                "lon" to lon,
                "nickname" to nickname,
                "updatedAt" to System.currentTimeMillis()
            )
            rideLocationRef.setValue(data).await()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Attaches a live listener; the callback fires immediately with the
     * current value and again on every subsequent change. Returns null once
     * the sample is older than [RideConfig.STALE_AFTER_SECONDS], mirroring
     * the old server's TTL expiry.
     */
    fun observeLocation(onChange: (RideLocation?) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onChange(parseSnapshot(snapshot))
            }

            override fun onCancelled(error: DatabaseError) {
                onChange(null)
            }
        }
        rideLocationRef.addValueEventListener(listener)
        return listener
    }

    fun removeListener(listener: ValueEventListener) {
        rideLocationRef.removeEventListener(listener)
    }

    private fun parseSnapshot(snapshot: DataSnapshot): RideLocation? {
        if (!snapshot.exists()) return null
        val lat = snapshot.child("lat").getValue(Double::class.java) ?: return null
        val lon = snapshot.child("lon").getValue(Double::class.java) ?: return null
        val updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: return null
        val ageSeconds = (System.currentTimeMillis() - updatedAt) / 1000.0
        if (ageSeconds > RideConfig.STALE_AFTER_SECONDS) return null
        val nickname = snapshot.child("nickname").getValue(String::class.java)
        return RideLocation(lat, lon, nickname, ageSeconds)
    }
}

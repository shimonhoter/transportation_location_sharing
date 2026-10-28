package com.shimonhoter.ridelocationshare.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import com.shimonhoter.ridelocationshare.config.RideConfig
import com.shimonhoter.ridelocationshare.data.GeoPoint
import com.shimonhoter.ridelocationshare.data.StopCandidate
import com.shimonhoter.ridelocationshare.util.GeoUtil
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SharedStop(val id: String, val name: String, val lat: Double, val lon: Double)
data class ActiveHandRaise(val uid: String, val stopId: String, val nickname: String?)

/** The two live listeners behind [StopsRepository.observeSharedStops] — pass
 * both back to [StopsRepository.removeSharedStopsObservation] together. */
class SharedStopsObservation internal constructor(
    internal val candidatesListener: ValueEventListener,
    internal val originHistoryListener: ValueEventListener
)

/**
 * Shared "pickup stops" feature (docs/SPEC_EN.md section 3.9): each device
 * submits its own candidate stop point(s) ([syncOwnCandidates]); every
 * client then merges every user's candidates sharing
 * [RideConfig.STOP_MERGE_RADIUS_METERS] into one averaged, named stop,
 * computed identically (and deterministically) on every device from the
 * same raw Firebase data — there's no server-side computation, matching
 * how FirebaseLocationRepository's own aggregation is entirely
 * client-side. Stops are ordered/named by distance from a statistically
 * estimated route-origin point: the average location of whichever device
 * was first to start broadcasting each day, going back up to
 * [RideConfig.ROUTE_ORIGIN_HISTORY_RETENTION_DAYS] days (claimed via a
 * Firebase transaction so exactly one device "wins" each day — see
 * [claimRouteOriginOfDay]). Also owns the "raise hand" shared state: a
 * device announces it's waiting at a specific stop, visible to everyone
 * watching the ride, until it's cancelled or MainActivity observes the
 * ride leaving that stop's zone and clears it.
 */
class StopsRepository {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database by lazy { FirebaseDatabase.getInstance(RideConfig.FIREBASE_DATABASE_URL) }
    private val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun rideRef(rideCode: String): DatabaseReference =
        database.getReference("rideLocation/rides/${sanitizeRideCode(rideCode)}")

    private fun stopCandidatesRef(rideCode: String) = rideRef(rideCode).child("stopCandidates")
    private fun routeOriginHistoryRef(rideCode: String) = rideRef(rideCode).child("routeOriginHistory")
    private fun handRaisesRef(rideCode: String) = rideRef(rideCode).child("handRaises")

    private suspend fun ensureSignedIn(): String {
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
        }
        return auth.currentUser!!.uid
    }

    /** Overwrites this device's full candidate list — it's always a
     * handful of points at most, so a wholesale replace on every edit is
     * simpler than diffing individual adds/moves/deletes server-side. */
    suspend fun syncOwnCandidates(rideCode: String, candidates: List<StopCandidate>) {
        try {
            val uid = ensureSignedIn()
            val data = candidates.associate { it.id to mapOf("lat" to it.lat, "lon" to it.lon) }
            stopCandidatesRef(rideCode).child(uid).setValue(data).await()
        } catch (_: Exception) {
            // Best-effort — this device's own local Prefs copy stays authoritative for its own editing UI either way.
        }
    }

    /** Tries to claim today as "this device was first to start
     * broadcasting" — only actually writes the first time any device
     * calls this on a given day (a Firebase transaction, so concurrent
     * callers from different devices can't both "win"); every later call
     * the same day is a harmless no-op. Called from
     * BroadcastService.beginBroadcasting(). */
    suspend fun claimRouteOriginOfDay(rideCode: String, lat: Double, lon: Double) {
        try {
            ensureSignedIn()
            val todayKey = dayKeyFormat.format(Date())
            routeOriginHistoryRef(rideCode).child(todayKey).runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    if (currentData.value == null) {
                        currentData.value = mapOf("lat" to lat, "lon" to lon)
                    }
                    return Transaction.success(currentData)
                }
                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) = Unit
            })
            pruneRouteOriginHistory(rideCode)
        } catch (_: Exception) {
            // Best-effort — losing one day's sample barely affects a 30-day average.
        }
    }

    private suspend fun pruneRouteOriginHistory(rideCode: String) {
        try {
            val cutoffKey = dayKeyFormat.format(
                Date(System.currentTimeMillis() - RideConfig.ROUTE_ORIGIN_HISTORY_RETENTION_DAYS * 24L * 60 * 60 * 1000)
            )
            val snapshot = routeOriginHistoryRef(rideCode).orderByKey().endAt(cutoffKey).get().await()
            for (child in snapshot.children) {
                if (child.key != cutoffKey) child.ref.removeValue()
            }
        } catch (_: Exception) {
            // Best-effort cleanup — a few extra old days lying around doesn't meaningfully skew a 30-day average.
        }
    }

    /**
     * Attaches two live listeners (candidates + route-origin history);
     * [onChange] fires with the freshly recomputed shared-stop list
     * whenever either changes, once both have reported at least once.
     * [nameFormat] (e.g. `{ n -> getString(R.string.stop_name_format, n) }`)
     * is injected since this class has no Context/resources of its own.
     */
    fun observeSharedStops(
        rideCode: String,
        nameFormat: (Int) -> String,
        onChange: (List<SharedStop>) -> Unit
    ): SharedStopsObservation {
        var latestCandidates: Map<String, List<StopCandidate>> = emptyMap()
        var latestOriginHistory: List<GeoPoint> = emptyList()
        var gotCandidates = false
        var gotHistory = false

        fun recompute() {
            if (!gotCandidates || !gotHistory) return
            onChange(computeSharedStops(latestCandidates, latestOriginHistory, nameFormat))
        }

        val candidatesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                latestCandidates = snapshot.children.associate { userSnap ->
                    userSnap.key.orEmpty() to userSnap.children.mapNotNull { candSnap ->
                        val lat = candSnap.child("lat").getValue(Double::class.java) ?: return@mapNotNull null
                        val lon = candSnap.child("lon").getValue(Double::class.java) ?: return@mapNotNull null
                        StopCandidate(candSnap.key.orEmpty(), lat, lon)
                    }
                }
                gotCandidates = true
                recompute()
            }
            override fun onCancelled(error: DatabaseError) {
                gotCandidates = true
                recompute()
            }
        }
        val originHistoryListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                latestOriginHistory = snapshot.children.mapNotNull { daySnap ->
                    val lat = daySnap.child("lat").getValue(Double::class.java) ?: return@mapNotNull null
                    val lon = daySnap.child("lon").getValue(Double::class.java) ?: return@mapNotNull null
                    GeoPoint(lat, lon)
                }
                gotHistory = true
                recompute()
            }
            override fun onCancelled(error: DatabaseError) {
                gotHistory = true
                recompute()
            }
        }
        stopCandidatesRef(rideCode).addValueEventListener(candidatesListener)
        routeOriginHistoryRef(rideCode).addValueEventListener(originHistoryListener)
        return SharedStopsObservation(candidatesListener, originHistoryListener)
    }

    fun removeSharedStopsObservation(rideCode: String, observation: SharedStopsObservation) {
        stopCandidatesRef(rideCode).removeEventListener(observation.candidatesListener)
        routeOriginHistoryRef(rideCode).removeEventListener(observation.originHistoryListener)
    }

    fun observeHandRaises(rideCode: String, onChange: (List<ActiveHandRaise>) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val raises = snapshot.children.mapNotNull { child ->
                    val uid = child.key ?: return@mapNotNull null
                    val stopId = child.child("stopId").getValue(String::class.java) ?: return@mapNotNull null
                    val nickname = child.child("nickname").getValue(String::class.java)
                    ActiveHandRaise(uid, stopId, nickname)
                }
                onChange(raises)
            }
            override fun onCancelled(error: DatabaseError) {
                onChange(emptyList())
            }
        }
        handRaisesRef(rideCode).addValueEventListener(listener)
        return listener
    }

    fun removeHandRaisesListener(rideCode: String, listener: ValueEventListener) {
        handRaisesRef(rideCode).removeEventListener(listener)
    }

    suspend fun setMyHandRaise(rideCode: String, stopId: String, nickname: String?) {
        try {
            val uid = ensureSignedIn()
            val data = mapOf("stopId" to stopId, "nickname" to nickname, "updatedAt" to System.currentTimeMillis())
            handRaisesRef(rideCode).child(uid).setValue(data).await()
        } catch (_: Exception) {
            // Best-effort — the button's own local Prefs state stays the UI's source of truth either way.
        }
    }

    suspend fun clearMyHandRaise(rideCode: String) {
        try {
            val uid = ensureSignedIn()
            handRaisesRef(rideCode).child(uid).removeValue().await()
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    /**
     * Pure, deterministic given the same inputs — every device computes
     * the same result from the same raw Firebase data, no server
     * computation involved (see class doc).
     */
    private fun computeSharedStops(
        candidatesByUid: Map<String, List<StopCandidate>>,
        originHistory: List<GeoPoint>,
        nameFormat: (Int) -> String
    ): List<SharedStop> {
        val allPoints = candidatesByUid.values.flatten().map { GeoPoint(it.lat, it.lon) }
        if (allPoints.isEmpty()) return emptyList()
        val centroids = cluster(allPoints)
        // No route-origin history yet (a brand-new group): fall back to the
        // centroid of all candidates as a stable, deterministic — if not
        // yet meaningful — reference point, so stops still get a
        // consistent order from day one instead of none at all; this
        // self-corrects automatically once real history accumulates.
        val origin = if (originHistory.isNotEmpty()) centroidOf(originHistory) else centroidOf(allPoints)
        val ordered = centroids.sortedBy { GeoUtil.distanceMeters(it.lat, it.lon, origin.lat, origin.lon) }
        return ordered.mapIndexed { index, point ->
            SharedStop(id = "stop_${index + 1}", name = nameFormat(index + 1), lat = point.lat, lon = point.lon)
        }
    }

    /** Greedy single-pass merge: starts a cluster from one point and
     * absorbs any other point within [RideConfig.STOP_MERGE_RADIUS_METERS]
     * of the cluster's current (growing) centroid, repeating until
     * nothing more is absorbed. Sorting the input first makes this
     * deterministic across devices. A chain of points each within range
     * of their immediate neighbor but whose combined span exceeds the
     * radius can still end up merged (the centroid keeps drifting toward
     * each new addition) — an accepted simplification given there's no
     * paid clustering service here, and real-world stops rarely form such
     * a chain. */
    private fun cluster(points: List<GeoPoint>): List<GeoPoint> {
        val remaining = points.sortedWith(compareBy({ it.lat }, { it.lon })).toMutableList()
        val clusters = mutableListOf<MutableList<GeoPoint>>()
        while (remaining.isNotEmpty()) {
            val group = mutableListOf(remaining.removeAt(0))
            var changed = true
            while (changed) {
                changed = false
                val centroid = centroidOf(group)
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val point = iterator.next()
                    if (GeoUtil.distanceMeters(point.lat, point.lon, centroid.lat, centroid.lon) <= RideConfig.STOP_MERGE_RADIUS_METERS) {
                        group.add(point)
                        iterator.remove()
                        changed = true
                    }
                }
            }
            clusters.add(group)
        }
        return clusters.map { centroidOf(it) }
    }

    private fun centroidOf(points: List<GeoPoint>): GeoPoint =
        GeoPoint(points.sumOf { it.lat } / points.size, points.sumOf { it.lon } / points.size)

    companion object {
        // Realtime Database keys may not contain these characters.
        private val FORBIDDEN_KEY_CHARS = charArrayOf('.', '$', '#', '[', ']', '/')

        private fun sanitizeRideCode(raw: String): String {
            val cleaned = raw.trim().map { if (it in FORBIDDEN_KEY_CHARS) '_' else it }.joinToString("")
            return cleaned.ifBlank { "default" }
        }
    }
}

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
private data class StopArrivalSample(val lat: Double, val lon: Double, val elapsedMillis: Long)

/** The four live listeners behind [StopsRepository.observeSharedStops] —
 * pass all four back to [StopsRepository.removeSharedStopsObservation]
 * together. */
class SharedStopsObservation internal constructor(
    internal val candidatesListener: ValueEventListener,
    internal val autoDetectedListener: ValueEventListener,
    internal val originHistoryListener: ValueEventListener,
    internal val arrivalHistoryListener: ValueEventListener
)

/**
 * Shared "pickup stops" feature (docs/SPEC_EN.md section 3.9): each device
 * submits its own candidate stop point(s) ([syncOwnCandidates]); every
 * client then merges every user's candidates sharing
 * [RideConfig.STOP_MERGE_RADIUS_METERS] into one averaged, named stop,
 * computed identically (and deterministically) on every device from the
 * same raw Firebase data — there's no server-side computation, matching
 * how FirebaseLocationRepository's own aggregation is entirely
 * client-side.
 *
 * Stops are ordered/named by their statistically observed position along
 * the route's actual timeline, not straight-line distance — a route that
 * bends or loops can have a stop that's geometrically close to the start
 * but reached late, which beeline distance would get wrong.
 * [recordStopArrival] lets any device log, once per stop per day, how
 * long after that day's route start (see below) the shared ride was
 * observed entering a stop's zone; stops are then ordered by the average
 * of that elapsed time across up to
 * [RideConfig.ROUTE_ORIGIN_HISTORY_RETENTION_DAYS] days. A stop with no
 * arrival history yet (just added, or a brand-new group with none at
 * all) is positioned using the group's own observed pace (total elapsed
 * time / total distance across stops that DO have history) applied to
 * its distance from the route origin, so the units stay consistent
 * (milliseconds, never a raw distance) — with zero history anywhere yet,
 * that reduces to ordering by plain distance from the origin, which
 * self-corrects automatically as real history accumulates.
 *
 * The "route origin" itself is the average location of whichever device
 * was first to start broadcasting each day, going back the same
 * retention window (claimed via a Firebase transaction so exactly one
 * device "wins" each day — see [claimRouteOriginOfDay]).
 *
 * Also owns the "raise hand" shared state: a device announces it's
 * waiting at a specific stop, visible to everyone watching the ride,
 * until it's cancelled or MainActivity observes the ride leaving that
 * stop's zone and clears it.
 *
 * Stops don't only come from someone tapping the map: [recordAutoDetectedStop]
 * lets a broadcasting device also contribute points automatically, whenever
 * BroadcastService notices itself continuously stopped for long enough
 * (docs/SPEC_EN.md 3.10) — those points feed into the exact same clustering
 * as manual candidates, so a location where this keeps recurring surfaces
 * as a shared stop on its own.
 */
class StopsRepository {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database by lazy { FirebaseDatabase.getInstance(RideConfig.FIREBASE_DATABASE_URL) }
    private val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun rideRef(rideCode: String): DatabaseReference =
        database.getReference("rideLocation/rides/${sanitizeRideCode(rideCode)}")

    private fun stopCandidatesRef(rideCode: String) = rideRef(rideCode).child("stopCandidates")
    private fun autoDetectedStopsRef(rideCode: String) = rideRef(rideCode).child("autoDetectedStops")
    private fun routeOriginHistoryRef(rideCode: String) = rideRef(rideCode).child("routeOriginHistory")
    private fun stopArrivalHistoryRef(rideCode: String) = rideRef(rideCode).child("stopArrivalHistory")
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

    /**
     * Records one occurrence of an automatically-detected pickup stop
     * (docs/SPEC_EN.md 3.10): BroadcastService found the device continuously
     * stopped for at least [RideConfig.DEFAULT_AUTO_STOP_DETECTION_SECONDS]
     * (as configured) while actively broadcasting. Purely additive — one
     * push-keyed entry per occurrence under today's day key, so repeated
     * detections at the same real-world location accumulate rather than
     * overwrite, letting [computeSharedStops] average them the same way it
     * averages a cluster of manually-pinned candidates. Pruned the same way
     * as the other day-keyed history nodes.
     */
    suspend fun recordAutoDetectedStop(rideCode: String, lat: Double, lon: Double) {
        try {
            ensureSignedIn()
            val todayKey = dayKeyFormat.format(Date())
            autoDetectedStopsRef(rideCode).child(todayKey).push().setValue(mapOf("lat" to lat, "lon" to lon)).await()
            pruneByDayKey(autoDetectedStopsRef(rideCode))
        } catch (_: Exception) {
            // Best-effort — losing one detection barely affects a 30-day statistical average.
        }
    }

    /** Tries to claim today as "this device was first to start
     * broadcasting" — only actually writes the first time any device
     * calls this on a given day (a Firebase transaction, so concurrent
     * callers from different devices can't both "win"); every later call
     * the same day is a harmless no-op. Called from
     * BroadcastService.beginBroadcasting(). Stores the start time
     * alongside the location — [observeTodayRouteStart] reads it back so
     * [recordStopArrival] can log elapsed time relative to it. */
    suspend fun claimRouteOriginOfDay(rideCode: String, lat: Double, lon: Double) {
        try {
            ensureSignedIn()
            val todayKey = dayKeyFormat.format(Date())
            routeOriginHistoryRef(rideCode).child(todayKey).runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    if (currentData.value == null) {
                        currentData.value = mapOf("lat" to lat, "lon" to lon, "startedAtMillis" to System.currentTimeMillis())
                    }
                    return Transaction.success(currentData)
                }
                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) = Unit
            })
            pruneByDayKey(routeOriginHistoryRef(rideCode))
        } catch (_: Exception) {
            // Best-effort — losing one day's sample barely affects a 30-day average.
        }
    }

    /** The millis [claimRouteOriginOfDay] recorded for *today* specifically
     * (not history) — [MainActivity] uses this to compute "elapsed since
     * route start" for [recordStopArrival]. Null until some device has
     * claimed today (normally within seconds of the first broadcast
     * starting). */
    fun observeTodayRouteStart(rideCode: String, onChange: (Long?) -> Unit): ValueEventListener {
        val ref = routeOriginHistoryRef(rideCode).child(dayKeyFormat.format(Date())).child("startedAtMillis")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onChange(snapshot.getValue(Long::class.java))
            }
            override fun onCancelled(error: DatabaseError) {
                onChange(null)
            }
        }
        ref.addValueEventListener(listener)
        return listener
    }

    /** Must be called with the same day as when [observeTodayRouteStart]
     * was called — if a listener happens to span midnight, this only
     * fails to detach it, a negligible one-time leak. */
    fun removeTodayRouteStartListener(rideCode: String, listener: ValueEventListener) {
        routeOriginHistoryRef(rideCode).child(dayKeyFormat.format(Date())).child("startedAtMillis").removeEventListener(listener)
    }

    /**
     * Logs that the shared ride was observed entering a stop's zone
     * [elapsedMillis] after today's route start — once per stop per day
     * (a transaction keyed by a rounded lat/lon "cell", so concurrent
     * callers from different devices can't both "win" the same
     * stop-day). The rounding is only to keep that per-day dedup key
     * short and well-formed for Firebase (which forbids '.' in keys); the
     * later read side ([computeSharedStops]) matches samples back to a
     * stop by real distance, not by this key, so drift in a stop's exact
     * centroid between days doesn't cause samples to go unmatched.
     * [lat]/[lon] should be the stop's own position, not the device's.
     */
    suspend fun recordStopArrival(rideCode: String, lat: Double, lon: Double, elapsedMillis: Long) {
        try {
            ensureSignedIn()
            val todayKey = dayKeyFormat.format(Date())
            val cellKey = "${Math.round(lat * 10000.0)}_${Math.round(lon * 10000.0)}"
            stopArrivalHistoryRef(rideCode).child(todayKey).child(cellKey).runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    if (currentData.value == null) {
                        currentData.value = mapOf("lat" to lat, "lon" to lon, "elapsedMillis" to elapsedMillis)
                    }
                    return Transaction.success(currentData)
                }
                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) = Unit
            })
            pruneByDayKey(stopArrivalHistoryRef(rideCode))
        } catch (_: Exception) {
            // Best-effort — losing one day's sample barely affects a 30-day average.
        }
    }

    /** Shared by both history nodes: both are keyed by yyyy-MM-dd day at
     * their first level. */
    private suspend fun pruneByDayKey(dayKeyedRef: DatabaseReference) {
        try {
            val cutoffKey = dayKeyFormat.format(
                Date(System.currentTimeMillis() - RideConfig.ROUTE_ORIGIN_HISTORY_RETENTION_DAYS * 24L * 60 * 60 * 1000)
            )
            val snapshot = dayKeyedRef.orderByKey().endAt(cutoffKey).get().await()
            for (child in snapshot.children) {
                if (child.key != cutoffKey) child.ref.removeValue()
            }
        } catch (_: Exception) {
            // Best-effort cleanup — a few extra old days lying around doesn't meaningfully skew a 30-day average.
        }
    }

    /**
     * Attaches four live listeners (candidates + auto-detected stops +
     * route-origin history + stop-arrival history); [onChange] fires with
     * the freshly recomputed shared-stop list whenever any of them
     * changes, once all four have reported at least once. [nameFormat] (e.g.
     * `{ n -> getString(R.string.stop_name_format, n) }`) is injected
     * since this class has no Context/resources of its own.
     */
    fun observeSharedStops(
        rideCode: String,
        nameFormat: (Int) -> String,
        onChange: (List<SharedStop>) -> Unit
    ): SharedStopsObservation {
        var latestCandidates: Map<String, List<StopCandidate>> = emptyMap()
        var latestAutoDetected: List<GeoPoint> = emptyList()
        var latestOriginHistory: List<GeoPoint> = emptyList()
        var latestArrivalHistory: List<StopArrivalSample> = emptyList()
        var gotCandidates = false
        var gotAutoDetected = false
        var gotOriginHistory = false
        var gotArrivalHistory = false

        fun recompute() {
            if (!gotCandidates || !gotAutoDetected || !gotOriginHistory || !gotArrivalHistory) return
            onChange(computeSharedStops(latestCandidates, latestAutoDetected, latestOriginHistory, latestArrivalHistory, nameFormat))
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
        val autoDetectedListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                latestAutoDetected = snapshot.children.flatMap { daySnap ->
                    daySnap.children.mapNotNull { entrySnap ->
                        val lat = entrySnap.child("lat").getValue(Double::class.java) ?: return@mapNotNull null
                        val lon = entrySnap.child("lon").getValue(Double::class.java) ?: return@mapNotNull null
                        GeoPoint(lat, lon)
                    }
                }
                gotAutoDetected = true
                recompute()
            }
            override fun onCancelled(error: DatabaseError) {
                gotAutoDetected = true
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
                gotOriginHistory = true
                recompute()
            }
            override fun onCancelled(error: DatabaseError) {
                gotOriginHistory = true
                recompute()
            }
        }
        val arrivalHistoryListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                latestArrivalHistory = snapshot.children.flatMap { daySnap ->
                    daySnap.children.mapNotNull { cellSnap ->
                        val lat = cellSnap.child("lat").getValue(Double::class.java) ?: return@mapNotNull null
                        val lon = cellSnap.child("lon").getValue(Double::class.java) ?: return@mapNotNull null
                        val elapsed = cellSnap.child("elapsedMillis").getValue(Long::class.java) ?: return@mapNotNull null
                        StopArrivalSample(lat, lon, elapsed)
                    }
                }
                gotArrivalHistory = true
                recompute()
            }
            override fun onCancelled(error: DatabaseError) {
                gotArrivalHistory = true
                recompute()
            }
        }
        stopCandidatesRef(rideCode).addValueEventListener(candidatesListener)
        autoDetectedStopsRef(rideCode).addValueEventListener(autoDetectedListener)
        routeOriginHistoryRef(rideCode).addValueEventListener(originHistoryListener)
        stopArrivalHistoryRef(rideCode).addValueEventListener(arrivalHistoryListener)
        return SharedStopsObservation(candidatesListener, autoDetectedListener, originHistoryListener, arrivalHistoryListener)
    }

    fun removeSharedStopsObservation(rideCode: String, observation: SharedStopsObservation) {
        stopCandidatesRef(rideCode).removeEventListener(observation.candidatesListener)
        autoDetectedStopsRef(rideCode).removeEventListener(observation.autoDetectedListener)
        routeOriginHistoryRef(rideCode).removeEventListener(observation.originHistoryListener)
        stopArrivalHistoryRef(rideCode).removeEventListener(observation.arrivalHistoryListener)
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
        autoDetected: List<GeoPoint>,
        originHistory: List<GeoPoint>,
        arrivalHistory: List<StopArrivalSample>,
        nameFormat: (Int) -> String
    ): List<SharedStop> {
        // Auto-detected points are mixed in alongside manually-pinned
        // candidates before clustering, not clustered separately — the same
        // centroid averaging that merges several users' manual pins also
        // gives a frequently-recurring automatic detection the "statistical
        // averaging over many occurrences" effect (docs/SPEC_EN.md 3.10),
        // and lets a detection-only location form its own stop even if no
        // one ever manually pinned it.
        val allPoints = candidatesByUid.values.flatten().map { GeoPoint(it.lat, it.lon) } + autoDetected
        if (allPoints.isEmpty()) return emptyList()
        val centroids = cluster(allPoints)
        // No route-origin history yet (a brand-new group): fall back to the
        // centroid of all candidates as a stable, deterministic — if not
        // yet meaningful — reference point, so stops still get a
        // consistent order from day one instead of none at all; this
        // self-corrects automatically once real history accumulates.
        val origin = if (originHistory.isNotEmpty()) centroidOf(originHistory) else centroidOf(allPoints)

        data class Positioned(val point: GeoPoint, val distanceFromOriginMeters: Double, val meanElapsedMillis: Double?)
        val positioned = centroids.map { point ->
            val distance = GeoUtil.distanceMeters(point.lat, point.lon, origin.lat, origin.lon)
            val matchingSamples = arrivalHistory.filter {
                GeoUtil.distanceMeters(it.lat, it.lon, point.lat, point.lon) <= RideConfig.STOP_MERGE_RADIUS_METERS
            }
            val meanElapsed = if (matchingSamples.isNotEmpty()) {
                matchingSamples.sumOf { it.elapsedMillis.toDouble() } / matchingSamples.size
            } else {
                null
            }
            Positioned(point, distance, meanElapsed)
        }

        // Order by real observed pass-time where we have it — correct even
        // for a route that bends or loops, unlike straight-line distance.
        // A stop with no matching samples yet is positioned using the
        // group's own observed pace (total elapsed / total distance,
        // across stops that DO have samples) applied to its own distance
        // from the origin, so every stop's sort key stays in the same
        // unit (milliseconds) rather than mixing distance and time
        // directly. With no samples anywhere yet, paceMillisPerMeter is
        // null and this reduces to plain distance-from-origin ordering —
        // self-correcting automatically as real history accumulates.
        val withSamples = positioned.filter { it.meanElapsedMillis != null }
        val paceMillisPerMeter = if (withSamples.isNotEmpty()) {
            val totalElapsed = withSamples.sumOf { it.meanElapsedMillis!! }
            val totalDistance = withSamples.sumOf { it.distanceFromOriginMeters }.coerceAtLeast(1.0)
            totalElapsed / totalDistance
        } else {
            null
        }
        val ordered = positioned.sortedBy { candidate ->
            candidate.meanElapsedMillis
                ?: paceMillisPerMeter?.times(candidate.distanceFromOriginMeters)
                ?: candidate.distanceFromOriginMeters
        }
        return ordered.mapIndexed { index, candidate ->
            SharedStop(id = "stop_${index + 1}", name = nameFormat(index + 1), lat = candidate.point.lat, lon = candidate.point.lon)
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

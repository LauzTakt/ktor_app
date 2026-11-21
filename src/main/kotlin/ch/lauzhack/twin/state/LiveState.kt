package ch.lauzhack.twin.state

import ch.lauzhack.twin.model.TrainInfo
import ch.lauzhack.twin.model.TrainPosition
import ch.lauzhack.twin.model.TwinStatus
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class Station(val stopId: String, val name: String, val lat: Double?, val lon: Double?)

/** Last known state of one train: its run (info) joined with its latest position (fleet). Either may be missing. */
@Serializable
data class TrainState(
    val trainId: String,
    val info: TrainInfo?,
    val position: TrainPosition?,
    /** Index in `info.calls` of the first stop not yet left (== calls.size when the run is over). */
    val nextCallIndex: Int?,
)

/**
 * In-memory last known state. Nothing here is written to disk: the retained `trains/+/info` topics rebuild it
 * a few seconds after (re)connecting, and `fleet` refreshes every position each second.
 *
 * Writers: the MQTT callback thread. Readers: HTTP/WebSocket handlers. Maps are concurrent; values are immutable.
 */
class LiveState {
    private val infos = ConcurrentHashMap<String, TrainInfo>()
    @Volatile private var positions: Map<String, TrainPosition> = emptyMap()

    /** trainId -> last station the train was seen leaving (from `fleet`: atStopId went from X to something else). */
    private val departedFrom = ConcurrentHashMap<String, String>()

    /** stopId -> trainIds whose run calls at that stop (kept in sync with [infos]). */
    private val trainsByStop = ConcurrentHashMap<String, MutableSet<String>>()
    private val stations = ConcurrentHashMap<String, Station>()

    @Volatile var status: TwinStatus? = null
        private set
    @Volatile var lastFleetTs: Long = 0
        private set

    /** Returns stations seen for the first time (so the caller can persist them). */
    fun upsertInfo(info: TrainInfo): List<Station> {
        val old = infos.put(info.trainId, info)
        val oldStops = old?.calls?.mapTo(HashSet()) { it.stopId } ?: emptySet()
        val newStops = info.calls.mapTo(HashSet()) { it.stopId }
        (oldStops - newStops).forEach { unindex(it, info.trainId) }
        (newStops - oldStops).forEach { trainsByStop.computeIfAbsent(it) { ConcurrentHashMap.newKeySet() }.add(info.trainId) }

        val added = ArrayList<Station>()
        for (c in info.calls) {
            val s = Station(c.stopId, c.name, c.lat, c.lon)
            val prev = stations.putIfAbsent(c.stopId, s)
            if (prev == null) added += s
            else if (prev != s) stations[c.stopId] = s // name/coords corrected upstream
        }
        return added
    }

    fun removeInfo(trainId: String) {
        departedFrom.remove(trainId)
        val old = infos.remove(trainId) ?: return
        old.calls.forEach { unindex(it.stopId, trainId) }
    }

    /** `fleet` carries every train in service: a train missing from it has left service. */
    fun replacePositions(snapshot: Map<String, TrainPosition>, ts: Long) {
        val previous = positions
        for ((id, p) in snapshot) {
            val before = previous[id]?.atStopId
            if (before != null && before != p.atStopId) departedFrom[id] = before
        }
        departedFrom.keys.retainAll(snapshot.keys)
        positions = snapshot
        lastFleetTs = ts
    }

    fun updateStatus(s: TwinStatus) { status = s }

    // ---- reads ----

    fun train(trainId: String): TrainState? {
        val i = infos[trainId]; val p = positions[trainId]
        return if (i == null && p == null) null else TrainState(trainId, i, p, i?.let { nextCallIndex(it, p) })
    }

    /** Latest `fleet` snapshot (immutable; replaced every second). */
    fun positions(): Map<String, TrainPosition> = positions

    fun trainIds(): Set<String> = infos.keys + positions.keys
    fun trainCount() = infos.size
    fun positionCount() = positions.size
    fun station(stopId: String): Station? = stations[stopId]
    fun stations(): Collection<Station> = stations.values

    /** Snapshot of every known train (info and/or position). */
    fun trains(): List<TrainState> = trainIds().mapNotNull { train(it) }

    /** Trains whose run calls at [stopId] (past or future calls; the caller filters by `nextCallIndex`). */
    fun trainsCallingAt(stopId: String): List<TrainState> = trainsByStop[stopId].orEmpty().mapNotNull { train(it) }

    /** Best estimate of "now" on the twin's clock (last fleet tick), falling back to the system clock. */
    fun now(): Long = lastFleetTs.takeIf { it > 0 } ?: System.currentTimeMillis()

    /**
     * First call the train has not left yet. Verified on the live feed (2026-11-21):
     * - calls with `expected*` set are always a suffix of `calls` -> that suffix is "still ahead";
     * - `actual*` is unreliable for stops passed before the twin started tracking the train;
     * - `info` lags the position by one stop for ~20 % of trains (it refreshes every ~1-2 min), so the position
     *   corrects it: standing at a station -> that call; just left station X -> the call after X.
     */
    private fun nextCallIndex(info: TrainInfo, pos: TrainPosition?): Int {
        val calls = info.calls
        var i = calls.indexOfFirst { it.expectedArr != null || it.expectedDep != null }.let { if (it < 0) calls.size else it }
        val searchFrom = (i - LAG_TOLERANCE).coerceAtLeast(0)
        fun indexOf(stopId: String) = (searchFrom until calls.size).firstOrNull { calls[it].stopId == stopId }
        pos?.atStopId?.let { at -> indexOf(at)?.let { return it } }
        departedFrom[info.trainId]?.let { dep -> indexOf(dep)?.let { k -> if (k + 1 > i) i = k + 1 } }
        return i
    }

    private companion object {
        /** How many stops `info` may lag behind the position. Observed: 1. */
        const val LAG_TOLERANCE = 2
    }

    private fun unindex(stopId: String, trainId: String) {
        trainsByStop.computeIfPresent(stopId) { _, set -> set.remove(trainId); if (set.isEmpty()) null else set }
    }
}

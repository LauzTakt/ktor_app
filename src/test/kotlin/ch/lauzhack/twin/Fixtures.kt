package ch.lauzhack.twin

import ch.lauzhack.twin.model.Call
import ch.lauzhack.twin.model.StopRef
import ch.lauzhack.twin.model.TrainInfo
import ch.lauzhack.twin.model.TrainPosition

const val T0 = 1_795_292_000_000L

/** A run over [stops], 10 min apart; calls from [aheadFrom] on carry expected* (the twin's "still ahead" suffix). */
fun info(id: String, aheadFrom: Int, stops: List<String> = listOf("A", "B", "C", "D"), line: String = "S1", delayMs: Long = 0) = TrainInfo(
    trainId = id, line = line, category = "S", number = id.takeLast(3), headsign = stops.last(),
    origin = StopRef(stops.first(), stops.first()), destination = StopRef(stops.last(), stops.last()),
    calls = stops.mapIndexed { i, s ->
        val planned = T0 + i * 600_000L
        val ahead = i >= aheadFrom
        Call(
            stopId = s, name = "Station $s", lat = 47.0 + i * 0.01, lon = 8.0 + i * 0.01,
            plannedArr = planned.takeIf { i > 0 }, plannedDep = planned.takeIf { i < stops.lastIndex },
            expectedArr = (planned + delayMs).takeIf { ahead && i > 0 }, expectedDep = (planned + delayMs).takeIf { ahead && i < stops.lastIndex },
        )
    },
)

fun pos(id: String, atStop: String?, delaySec: Int = 0, ts: Long = T0) =
    TrainPosition(trainId = id, lat = 47.0, lon = 8.0, atStopId = atStop, delaySec = delaySec, motion = if (atStop != null) "stopped" else "moving", ts = ts, streamId = "s")

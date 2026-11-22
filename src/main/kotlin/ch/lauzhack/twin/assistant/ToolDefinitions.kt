package ch.lauzhack.twin.assistant

import ch.lauzhack.twin.assistant.AssistantTools.Companion.COLOURS
import ch.lauzhack.twin.assistant.AssistantTools.Companion.ROLES
import ch.lauzhack.twin.assistant.AssistantTools.Companion.STOP
import ch.lauzhack.twin.assistant.AssistantTools.Companion.prop
import ch.lauzhack.twin.assistant.AssistantTools.Companion.tool
import ch.lauzhack.twin.common.long
import ch.lauzhack.twin.common.minutes
import ch.lauzhack.twin.model.Station
import com.anthropic.models.messages.Tool
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The tool definitions sent to the model. Keep them stable: they are part of the cached prompt. */
internal val TOOL_DEFINITIONS: List<Tool> = listOf(
    tool(
        "find_station",
        "Find stations by name (any language, accents optional). Returns up to 5 matches, best first, with their stopId. " +
            "Every other tool already accepts a station name, so use this only when a name is ambiguous.",
        required = listOf("query"),
    ) { prop("query", "string", "Station name, e.g. \"Zürich HB\", \"Genf\", \"Lausanne\".") },
    tool(
        "station_board",
        "Next departures from a station, with live expected times, delays and cancellations.",
        required = listOf("stopId"),
    ) {
        prop("stopId", "string", STOP)
        prop("windowMin", "integer", "Minutes ahead (default 60, max 240).")
    },
    tool(
        "train_status",
        "Live status of one train: where it is now (standing at a station, running, or stopped on the open line), its delay, " +
            "its next stops with expected times, and the incidents affecting it. Give trainId; or give line (e.g. \"IC8\", \"S3\") " +
            "with destination and/or stopId to find the matching trains in service, nearest to the passenger first.",
        required = emptyList(),
    ) {
        prop("trainId", "string", "Train id from another tool.")
        prop("line", "string", "Line name, e.g. IC8, IR15, S3.")
        prop("destination", "string", "Station the train is heading to (name or stopId).")
        prop("stopId", "string", "A station the train will still call at (name or stopId).")
    },
    tool(
        "plan_journey",
        "Journey planner on the live network: today's timetable with live delays, cancellations, held trains and closed " +
            "sections; transfers of at least 4 minutes, at most 2. Returns up to 4 journeys sorted by arrival, each with a " +
            "journeyId, live departure and arrival, transfers, legs and warnings; the planner's own pick is marked " +
            "plannerRecommended. Use the journeyId in show_route, start_timer and the options of respond. An empty list " +
            "means no connection in the hours the planner covers.",
        required = listOf("fromStopId", "toStopId"),
    ) {
        prop("fromStopId", "string", STOP)
        prop("toStopId", "string", STOP)
        prop("after", "string", "Leave at or after this time, HH:MM Swiss time (default: now).")
        prop("limit", "integer", "Number of journeys, 1-6 (default 4).")
        prop("onboardTrainId", "string", "The train the passenger is on, if any (default: the app's trainId): staying aboard is then an option.")
    },
    tool(
        "situation",
        "What the passenger should know about a station, a train or a trip: active incidents (marked by railway staff or " +
            "detected automatically from train movements) and real SBB service alerts. Give stopId, or trainId, or fromStopId " +
            "and toStopId.",
        required = emptyList(),
    ) {
        prop("stopId", "string", STOP)
        prop("trainId", "string", "Train id.")
        prop("fromStopId", "string", "Trip origin: $STOP")
        prop("toStopId", "string", "Trip destination: $STOP")
    },
    tool(
        "bulletins",
        "The official SBB disruption bulletins (VIA) of the control centre, latest version of each, active first: title, " +
            "lifecycle (INFO = active, ENDED = service resumed), the description lines, the latest messages (Replacement " +
            "bus, Recovery prognosis, Impact on train services, End of disruption, ...), effect, cause, until and " +
            "durationType, measures and replacement-bus stops. Use it for what happened and how long, in the bulletin's " +
            "plain words. Optional filters: stopId (a station on or at the edge of the section) and line.",
        required = emptyList(),
    ) {
        prop("stopId", "string", "Only bulletins touching this station: $STOP")
        prop("line", "string", "Only bulletins affecting this line, e.g. IC8, S3.")
    },
    tool(
        "arrivals",
        "Trains arriving soon at a station, live: line, headsign, expected arrival, minutes away, delay, stops away, " +
            "status, trainId. Use it when the passenger waits at a station for a train.",
        required = listOf("stopId"),
    ) { prop("stopId", "string", STOP) },
    tool(
        "show_route",
        "Draw a journey from plan_journey on the passenger's map (the real track geometry is added for you). The journeys " +
            "in respond's options are drawn automatically: use this only for another journey or a custom label.",
        required = listOf("journeyId", "style"),
    ) {
        prop("journeyId", "string", "A journeyId returned by plan_journey.")
        prop("style", "string", "recommended (the one you advise) or alternative.", enum = listOf("recommended", "alternative"))
        prop("label", "string", "Short map label in the passenger's language (default like \"IC8 → Bern, 18:42\").")
    },
    tool(
        "show_section",
        "Highlight the track between two stations on the map, e.g. a closed or disrupted section.",
        required = listOf("fromStopId", "toStopId", "colour", "label"),
    ) {
        prop("fromStopId", "string", STOP)
        prop("toStopId", "string", STOP)
        prop("colour", "string", "red = closed or interrupted, orange = disrupted or slow, green = fine, blue = information.",
            enum = listOf("red", "orange", "green", "blue"))
        prop("label", "string", "Short map label in the passenger's language, e.g. \"Closed: Olten – Aarau\".")
    },
    tool(
        "highlight_trains",
        "Outline live trains on the map: the passenger's train, the one to take, the trains of a line or between two stations.",
        required = listOf("trains"),
    ) {
        put("trains", buildJsonObject {
            put("type", "array")
            put("description", "Trains in service (trainId from a tool), with a short label in the passenger's language.")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("trainId") { put("type", "string") }
                    putJsonObject("label") { put("type", "string"); put("description", "e.g. \"Your train\", \"IC 8 to Bern\".") }
                }
                putJsonArray("required") { add("trainId") }
            }
        })
    },
    tool(
        "track_train",
        "Live tracking on the passenger's screen of the train they are waiting for at their station (its position and ETA).",
        required = listOf("trainId", "stopId"),
    ) {
        prop("trainId", "string", "Train id from arrivals, train_status or a journey leg.")
        prop("stopId", "string", "The station where the passenger waits: $STOP")
        prop("label", "string", "Short label in the passenger's language, e.g. \"Your IC 8\" (optional).")
    },
    tool(
        "highlight_line",
        "Highlight a whole line on the map (its trains). With route=true its path is drawn too (from one of its trains).",
        required = listOf("line"),
    ) {
        prop("line", "string", "Line name, e.g. IC8, S3.")
        prop("route", "boolean", "Also draw the line's path (default false).")
        prop("label", "string", "Short label in the passenger's language (optional).")
    },
    tool(
        "mark_stations",
        "Mark stations on the map (origin, destination, transfer, incident, or info such as a train's next stops).",
        required = listOf("stations"),
    ) {
        put("stations", buildJsonObject {
            put("type", "array")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("stopId") { put("type", "string"); put("description", STOP) }
                    putJsonObject("role") { put("type", "string"); putJsonArray("enum") { ROLES.forEach { add(it) } } }
                    putJsonObject("label") { put("type", "string"); put("description", "Short label in the passenger's language, e.g. \"02:10\" (optional).") }
                }
                putJsonArray("required") { add("stopId"); add("role") }
            }
        })
    },
    tool(
        "show_path",
        "Draw a path along consecutive stations (real track from the twin), e.g. a line's route or a train's remaining run.",
        required = listOf("stopIds"),
    ) {
        put("stopIds", buildJsonObject {
            put("type", "array")
            put("description", "At least 2 stations in travel order (stopIds or names); only the main stops are needed.")
            putJsonObject("items") { put("type", "string") }
        })
        prop("colour", "string", "Default blue.", enum = COLOURS.toList())
        prop("label", "string", "Short map label in the passenger's language (optional).")
    },
    tool(
        "label",
        "Put a short callout on the map at a station (or at lon/lat).",
        required = listOf("text"),
    ) {
        prop("stopId", "string", STOP)
        prop("lon", "number", "Longitude, if not at a station.")
        prop("lat", "number", "Latitude, if not at a station.")
        prop("text", "string", "A few words in the passenger's language.")
    },
    tool(
        "remove",
        "Remove one item drawn earlier (only useful with append=true): its id (route id, section id like sec-A-B or path:A-B, trainId, stopId, label id).",
        required = listOf("id"),
    ) { prop("id", "string", "The item's id.") },
    tool(
        "start_timer",
        "Show a countdown chip on the passenger's screen, e.g. until the recommended train leaves.",
        required = listOf("label"),
    ) {
        prop("label", "string", "Short label in the passenger's language, e.g. \"IC 8 leaves in\".")
        prop("journeyId", "string", "Count down to this journey's live departure (preferred).")
        prop("until", "string", "Otherwise the time to count down to, HH:MM Swiss time, taken from a tool result.")
    },
    tool(
        "fit",
        "Fit the map to these trains (live positions), stations and drawn sections. Optional: by default the map fits " +
            "everything drawn. Prefer it when trains are involved.",
        required = emptyList(),
    ) {
        listOf("trainIds" to "Trains in service.", "stopIds" to "Stations (stopIds or names).", "sectionIds" to "Ids of drawn sections (sec-A-B, path:A-B).")
            .forEach { (k, d) -> put(k, buildJsonObject { put("type", "array"); put("description", d); putJsonObject("items") { put("type", "string") } }) }
    },
    tool(
        "respond",
        "Your final answer to the passenger. Call it exactly once, at the end; put the drawing tools in the same turn.",
        required = listOf("answer", "summary"),
    ) {
        prop("answer", "string",
            "Spoken by the voice agent: 10-25 words, 1-2 calm sentences in the passenger's language, the answer first, no preamble; the " +
                "key fact or recommendation first. Plain text: no markdown, no lists, no ids.")
        prop("summary", "string", "One short line for the screen (at most about 60 characters), same language.")
        put("options", buildJsonObject {
            put("type", "array")
            put("description", "The journeys you compared, best first. Departure, arrival and transfers are filled in from the journeyId.")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("journeyId") { put("type", "string"); put("description", "journeyId from plan_journey.") }
                    putJsonObject("label") { put("type", "string"); put("description", "e.g. \"IC 8 at 18:42, direct\", in the passenger's language.") }
                    putJsonObject("recommended") { put("type", "boolean") }
                    putJsonObject("why") { put("type", "string"); put("description", "A few words, e.g. \"arrives first\".") }
                }
                putJsonArray("required") { add("label"); add("recommended") }
            }
        })
        put("unavailable", buildJsonObject {
            put("type", "array")
            put("description", "The out-of-scope topics the passenger asked about (you have no data on them); empty otherwise.")
            putJsonObject("items") { put("type", "string"); putJsonArray("enum") { Scope.TOPICS.forEach { add(it) } } }
        })
        prop("append", "boolean", "true only for a pure follow-up that adds to the current map picture; by default the picture is replaced.")
        prop("followUp", "string", "One short question for the passenger, only if it really helps (otherwise leave it out).")
        put("situationIds", buildJsonObject {
            put("type", "array")
            put("description", "ids of the incidents and alerts (from the tools) that matter for this passenger, most important first.")
            putJsonObject("items") { put("type", "string") }
        })
    },
)

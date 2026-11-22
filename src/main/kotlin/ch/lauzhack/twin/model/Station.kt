package ch.lauzhack.twin.model

import kotlinx.serialization.Serializable

/** A station of the network (UIC/DIDOK `stopId`, e.g. `8503000` Zürich HB), as learned from the train runs. */
@Serializable
data class Station(val stopId: String, val name: String, val lat: Double?, val lon: Double?)

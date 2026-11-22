package ch.lauzhack.twin.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Loose, null-safe JSON access for payloads we read field by field (sbb-twin's HTTP API, the model's tool inputs).
 * A missing key, a JSON null or a value of the wrong type all read as null (or empty), never as an exception.
 */

val EMPTY_ARRAY = JsonArray(emptyList())

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.objects(): List<JsonObject> = arr()?.mapNotNull { it as? JsonObject }.orEmpty()

fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
/** The array at [key], empty when missing. */
fun JsonObject?.array(key: String): JsonArray = this?.get(key).arr() ?: EMPTY_ARRAY
fun JsonObject?.objects(key: String): List<JsonObject> = this?.get(key).objects()

/** A string (or number, as text); null when missing, JSON null or blank. */
fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonObject?.int(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() }
fun JsonObject?.double(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull
fun JsonObject?.bool(key: String): Boolean? =
    (this?.get(key) as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

/** Non-blank strings of the array at [key]; a single string is split on commas (`"a, b"` -> `[a, b]`). */
fun JsonObject?.strings(key: String): List<String> = when (val v = this?.get(key)) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
    is JsonPrimitive -> if (v is JsonNull) emptyList() else v.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    else -> emptyList()
}

package ch.lauzhack.twin.common

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer

private val DIACRITICS = Regex("\\p{M}+")

/**
 * Station-name search key: trimmed, lower case, accents removed ("Zürich" and "zurich" match; ß -> ss). The one
 * normalisation behind every station search (REST `?q=`, the assistant's resolver, the passenger app's place matching).
 */
fun searchKey(s: String): String =
    Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "").replace("ß", "ss")

private val WHITESPACE = Regex("\\s+")
private val SPACED = Regex("^([A-Z]+)(\\d)")

/** Line comparison key: upper case, no whitespace ("IC 1", "ic1" and "IC1" are the same line). */
fun lineKey(line: String): String = line.uppercase().replace(WHITESPACE, "")

/** "IC1" -> "IC 1" (how lines are written for people). */
fun spacedLine(line: String): String = line.replaceFirst(SPACED, "$1 $2")

/** "Bern - Thun" -> "Bern – Thun" (sbb-twin titles use a hyphen between places). */
fun cleanDashes(text: String): String = text.replace(" - ", " – ")

fun String.capitalised(): String = replaceFirstChar { it.uppercaseChar() }

/** URL-encodes a path segment or query value (spaces as `%20`). */
fun urlEncode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

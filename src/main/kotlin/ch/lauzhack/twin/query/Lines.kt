package ch.lauzhack.twin.query

import ch.lauzhack.twin.common.lineKey
import ch.lauzhack.twin.state.TrainState

/* Line names ("IC 1", "S3", ...): one comparison rule for every filter, subscription and text match. */

/** Same line, ignoring case and spaces: "IC1", "IC 1" and "ic1" match. */
fun sameLine(a: String?, b: String?): Boolean = a != null && b != null && lineKey(a) == lineKey(b)

/** The train's line, from its info or else its last position. */
val TrainState.line: String? get() = info?.line ?: position?.line

/**
 * A line named in free text: `IC 1`, `IR15`, `ICE 70`, `S3`, `R 12`, … Multi-letter categories in any case; `S` / `R`
 * only upper case, so "it's 10" is not the S10.
 */
private val LINE_IN_TEXT = Regex("\\b(?:((?i:ICN|ICE|IRE|IC|IR|RE|EC|TGV|RJX|PE|SN))\\s?(\\d{1,3})|([SR])\\s?(\\d{1,2}))\\b")

/** The first line named in [text], as its [lineKey] ("IC 8" -> "IC8"), or null. */
fun lineIn(text: String): String? = LINE_IN_TEXT.find(text)?.let { m ->
    lineKey(m.groupValues[1].ifEmpty { m.groupValues[3] } + m.groupValues[2].ifEmpty { m.groupValues[4] })
}

/** Whether [text] mentions [line] ("IC1" in "Delays on the IC 1 between Bern and Thun"), any case, optional space. */
fun mentionsLine(line: String, text: String): Boolean = runCatching {
    val k = lineKey(line)
    val core = Regex("^([A-Z]+)(\\d+)$").matchEntire(k)?.let { "${it.groupValues[1]}\\s?${it.groupValues[2]}" } ?: Regex.escape(k)
    Regex("(^|[^A-Z0-9])$core([^0-9]|$)", RegexOption.IGNORE_CASE).containsMatchIn(text)
}.getOrDefault(false)

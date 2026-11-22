package ch.lauzhack.twin.api.v1

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Plain-text form of [ScreenDto] for microcontroller displays (GET /stations/{id}/screen.txt): one line per item,
 * `|`-separated fields, times already in Swiss local time and delays in minutes, so the device needs neither a JSON
 * parser nor time zone rules. Format in docs/API.md "Station screen, text form".
 */
object ScreenText {
    private val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZURICH)
    const val TITLE_MAX = 96
    const val WHERE_MAX = 48

    class Rendered(val body: String, val etag: String)

    fun render(s: ScreenDto): Rendered {
        val content = StringBuilder()
        for (d in s.departures) {
            content.append("D|").append(d.plannedTime?.let { hhmm(it) } ?: "--:--")
                .append('|').append(maxOf(0, d.delaySec) / 60)
                .append('|').append(field(d.line, 12))
                .append('|').append(field(d.category ?: "", 8))
                .append('|').append(field(d.to, 40))
                .append('|').append(if (d.cancelled) 1 else 0)
                .append('|').append(if (d.atStation) 1 else 0).append('\n')
        }
        for (i in s.incidents) {
            content.append("I|").append(i.severity ?: "info")
                .append('|').append(if (i.atStation) 1 else 0)
                .append('|').append(i.trains)
                .append('|').append(field(i.title, TITLE_MAX))
                .append('|').append(field(i.where ?: "", WHERE_MAX)).append('\n')
        }
        val name = field(s.name, 40)
        // The ETag covers what the screen shows except the clock, so an unchanged board answers 304 with no body.
        val etag = "\"" + sha1("$name\n$content").substring(0, 12) + "\""
        val body = "S|$name|${hhmm(s.now)}|${s.now / 1000}\n$content"
        return Rendered(body, etag)
    }

    private fun hhmm(epochMs: Long) = HHMM.format(Instant.ofEpochMilli(epochMs))

    /** No separators or line breaks inside a field; long values cut on a character boundary. */
    private fun field(v: String, max: Int): String {
        val clean = v.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim()
        if (clean.length <= max) return clean
        var end = max
        if (Character.isHighSurrogate(clean[end - 1])) end--
        return clean.substring(0, end).trimEnd()
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

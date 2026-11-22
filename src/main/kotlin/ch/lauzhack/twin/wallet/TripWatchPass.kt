package ch.lauzhack.twin.wallet

import ch.lauzhack.twin.common.ZURICH
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlinx.serialization.Serializable

/**
 * The front of every Trip Watch pass, laid out like a rail ticket in Wallet. Apple Wallet, Google Wallet and the web
 * preview all show these values. It is travel information, never a ticket: the QR code only opens the watch page.
 */
@Serializable
internal data class TripWatchPassFace(
    /** `dd.MM.yyyy HH:mm` in Europe/Zurich: planned departure of the leg the passenger boards next. */
    val departure: String,
    val date: String,
    val origin: String,
    val destination: String,
    val line: String,
    /** "Direct", "1 change" or "N changes" for the whole trip. */
    val changes: String,
    val nextStep: String,
    /** Trip Watch page encoded in the QR code. Relative when no public base URL is configured. */
    val url: String,
    val brand: String = PASS_BRAND,
    val notice: String = PASS_NOTICE,
)

internal const val PASS_BRAND = "Takt"
internal const val PASS_NOTICE = "Travel information · not a ticket"
internal const val PASS_ABOUT = "Travel information from $PASS_BRAND, based on live train data. " +
    "This pass is not a ticket and is not valid for travel."

internal fun TripWatch.passFace(site: String): TripWatchPassFace {
    val shown = relevantLeg() ?: leg
    val departure = Instant.ofEpochMilli(shown.departure)
    return TripWatchPassFace(
        departure = passDateTime.format(departure),
        date = passDate.format(departure),
        origin = legs.first().from.name,
        destination = destination.name,
        line = shown.line,
        changes = when (val count = legs.size - 1) {
            0 -> "Direct"
            1 -> "1 change"
            else -> "$count changes"
        },
        nextStep = instruction(),
        url = "$site/trip-watch.html?id=$id",
    )
}

private val passDateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZURICH)
private val passDate = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZURICH)

/** Plain red bar (3:1, the proportions of a Swiss railway flag, without any symbol). */
private val passRed = Color(235, 0, 0)

/** Apple Wallet logo: the bar alone, 45 × 15 pt, so the logo text "Takt" sits right next to it. */
internal fun passLogo(scale: Int): ByteArray {
    val image = BufferedImage(45 * scale, 15 * scale, BufferedImage.TYPE_INT_ARGB)
    val draw = image.createGraphics()
    try {
        draw.color = passRed
        draw.fillRect(0, 0, image.width, image.height)
    } finally {
        draw.dispose()
    }
    return image.png()
}

/** Google Wallet masks the logo to a circle: a white square with the bar centred well inside that circle. */
internal fun passCircleLogo(size: Int = 660): ByteArray {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val draw = image.createGraphics()
    try {
        draw.color = Color.WHITE
        draw.fillRect(0, 0, size, size)
        // 390 × 130 on 660: the bar's corners stay at 62 % of the circle's radius.
        val width = size * 13 / 22
        val height = width / 3
        draw.color = passRed
        draw.fillRect((size - width) / 2, (size - height) / 2, width, height)
    } finally {
        draw.dispose()
    }
    return image.png()
}

private fun BufferedImage.png(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(this, "png", it) }.toByteArray()

package ch.lauzhack.twin.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Deep links into the mobile app (mobile/): a scanned QR code or a tapped link to this site opens the app instead of
 * the browser when the OS has verified that the app and the domain belong together.
 *
 * - Android App Links: `/.well-known/assetlinks.json` lists the package and the SHA-256 fingerprints of the APK
 *   signing certificates (`ANDROID_CERT_SHA256`, comma-separated, `AA:BB:…`). Only a stable release key verifies.
 * - iOS Universal Links: `/.well-known/apple-app-site-association` lists `TEAMID.bundleId` (`IOS_APP_ID`). Needs the
 *   Associated Domains entitlement, i.e. a paid Apple Developer account.
 *
 * Each file answers 404 while its env var is empty.
 */
data class AppLinksConfig(
    val androidPackage: String = "ch.lauzhack.twin",
    val androidCertSha256: List<String> = emptyList(),
    val iosAppIds: List<String> = emptyList(),
) {
    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv): AppLinksConfig {
            fun list(name: String) = env(name).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return AppLinksConfig(
                androidPackage = env("ANDROID_PACKAGE")?.trim()?.takeIf { it.isNotEmpty() } ?: "ch.lauzhack.twin",
                androidCertSha256 = list("ANDROID_CERT_SHA256").map { it.uppercase() },
                iosAppIds = list("IOS_APP_ID"),
            )
        }
    }
}

private val compact = Json { prettyPrint = false }

fun Route.appLinks(config: AppLinksConfig) {
    get("/.well-known/assetlinks.json") {
        if (config.androidCertSha256.isEmpty()) return@get call.respond(HttpStatusCode.NotFound)
        val body = buildJsonArray {
            addJsonObject {
                putJsonArray("relation") { add("delegate_permission/common.handle_all_urls") }
                putJsonObject("target") {
                    put("namespace", "android_app")
                    put("package_name", config.androidPackage)
                    putJsonArray("sha256_cert_fingerprints") { config.androidCertSha256.forEach { add(it) } }
                }
            }
        }
        call.respondText(compact.encodeToString(body), ContentType.Application.Json)
    }
    get("/.well-known/apple-app-site-association") {
        if (config.iosAppIds.isEmpty()) return@get call.respond(HttpStatusCode.NotFound)
        val body = buildJsonObject {
            putJsonObject("applinks") {
                putJsonArray("details") {
                    addJsonObject {
                        putJsonArray("appIDs") { config.iosAppIds.forEach { add(it) } }
                        // Passenger pages only: staff pages, API and the voice assistant stay in the browser.
                        putJsonArray("components") {
                            addJsonObject { put("/", "/") }
                            addJsonObject { put("/", "/trip-watch.html") }
                        }
                    }
                }
            }
        }
        call.respondText(compact.encodeToString(body), ContentType.Application.Json)
    }
}

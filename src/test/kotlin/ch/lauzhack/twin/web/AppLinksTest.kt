package ch.lauzhack.twin.web

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppLinksTest {
    @Test
    fun notConfiguredAnswers404() = testApplication {
        application { routing { appLinks(AppLinksConfig()) } }
        assertEquals(HttpStatusCode.NotFound, client.get("/.well-known/assetlinks.json").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/.well-known/apple-app-site-association").status)
    }

    @Test
    fun configuredFromEnvironment() = testApplication {
        val env = mapOf("ANDROID_CERT_SHA256" to " aa:bb , cc:dd ", "IOS_APP_ID" to "ABCDE12345.ch.lauzhack.twin")
        application { routing { appLinks(AppLinksConfig.fromEnvironment { env[it] }) } }

        val android = client.get("/.well-known/assetlinks.json")
        assertEquals(HttpStatusCode.OK, android.status)
        assertTrue(android.contentType()!!.match(ContentType.Application.Json))
        val target = Json.parseToJsonElement(android.bodyAsText()).jsonArray.single().jsonObject.getValue("target").jsonObject
        assertEquals("ch.lauzhack.twin", target.getValue("package_name").jsonPrimitive.content)
        assertEquals(listOf("AA:BB", "CC:DD"), target.getValue("sha256_cert_fingerprints").jsonArray.map { it.jsonPrimitive.content })

        val ios = client.get("/.well-known/apple-app-site-association")
        assertEquals(HttpStatusCode.OK, ios.status)
        val details = Json.parseToJsonElement(ios.bodyAsText()).jsonObject.getValue("applinks").jsonObject
            .getValue("details").jsonArray.single().jsonObject
        assertEquals("ABCDE12345.ch.lauzhack.twin", details.getValue("appIDs").jsonArray.single().jsonPrimitive.content)
    }
}

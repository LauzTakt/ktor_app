package ch.lauzhack.twin

import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.model.Station
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun stationLinksResolveFromTheSavedCatalogBeforeTheFeedConnects() {
        val dbPath = createTempFile("twin-stations", ".sqlite")
        val station = Station("8503000", "Zürich HB", 47.378, 8.540)
        try {
            Database(dbPath.toString()).use { it.stations.save(listOf(station)) }
            testApplication {
                environment {
                    config = MapApplicationConfig("mqtt.enabled" to "false", "db.path" to dbPath.toString())
                }
                application { module() }

                val search = client.get("/api/v1/stations?q=zurich")
                assertEquals(HttpStatusCode.OK, search.status)
                assertEquals(station.stopId, Json.parseToJsonElement(search.bodyAsText()).jsonArray.single()
                    .jsonObject.getValue("stopId").jsonPrimitive.content)

                val detail = client.get("/api/v1/stations/${station.stopId}")
                assertEquals(HttpStatusCode.OK, detail.status)
                assertEquals(station.name, Json.parseToJsonElement(detail.bodyAsText()).jsonObject.getValue("station")
                    .jsonObject.getValue("name").jsonPrimitive.content)

                val destinations = client.get("/api/v1/stations/${station.stopId}/destinations")
                assertEquals(HttpStatusCode.OK, destinations.status)
                assertEquals("[]", destinations.bodyAsText())
                assertEquals(HttpStatusCode.OK, client.get("/?station=${station.stopId}").status)
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun healthReturnsOk() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "mqtt.enabled" to "false",
                "db.path" to kotlin.io.path.createTempFile("twin", ".sqlite").toString(),
            )
        }
        application { module() }
        val res = client.get("/health")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("ok"))
    }

    @Test
    fun swaggerUiAndSpecAreServed() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "mqtt.enabled" to "false",
                "db.path" to kotlin.io.path.createTempFile("twin", ".sqlite").toString(),
            )
        }
        application { module() }
        assertEquals(HttpStatusCode.OK, client.get("/swagger").status)
        val spec = client.get("/swagger/documentation.yaml")
        assertEquals(HttpStatusCode.OK, spec.status)
        assertTrue(spec.bodyAsText().contains("/stations/{stopId}/board"))
    }
}

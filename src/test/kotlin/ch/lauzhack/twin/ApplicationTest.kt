package ch.lauzhack.twin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
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
}

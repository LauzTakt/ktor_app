package ch.lauzhack.twin

import ch.lauzhack.twin.auth.AuthConfig
import ch.lauzhack.twin.db.Database
import ch.lauzhack.twin.state.LiveState
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AuthTest {
    private fun app(cfg: AuthConfig = AuthConfig(), dbPath: String = createTempFile("twin", ".sqlite").toString(), test: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            val db = Database(dbPath)
            application { configureHttp(LiveState(), db, cfg) { true } }
            test()
        }

    private suspend fun ApplicationTestBuilder.postJson(url: String, body: String, token: String? = null): HttpResponse = client.post(url) {
        contentType(ContentType.Application.Json); setBody(body); token?.let { bearerAuth(it) }
    }

    private suspend fun ApplicationTestBuilder.login(u: String, p: String): String {
        val r = postJson("/api/v1/auth/login", """{"username":"$u","password":"$p"}""")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private fun code(r: String) = Json.parseToJsonElement(r).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun defaultAdminCanLoginAndCallStaffEndpoints() = app {
        val token = login("admin", "admin")
        val me = client.get("/api/v1/auth/me") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("staff", Json.parseToJsonElement(me.bodyAsText()).jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(listOf("admin"), Json.parseToJsonElement(client.get("/api/v1/staff/users") { bearerAuth(token) }.bodyAsText())
            .jsonArray.map { it.jsonObject["username"]!!.jsonPrimitive.content })
    }

    @Test
    fun publicEndpointsStayOpenAndStaffOnesNeedAToken() = app {
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/status").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/incidents").status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)

        val anon = client.get("/api/v1/staff/users")
        assertEquals(HttpStatusCode.Unauthorized, anon.status)
        assertEquals("unauthorized", code(anon.bodyAsText()))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me") { bearerAuth("garbage") }.status)

        val bad = postJson("/api/v1/auth/login", """{"username":"admin","password":"nope"}""")
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertEquals("invalid_credentials", code(bad.bodyAsText()))
        assertEquals("invalid_credentials", code(postJson("/api/v1/auth/login", """{"username":"ghost","password":"x"}""").bodyAsText()))
    }

    @Test
    fun userManagementAndLogout() = app {
        val admin = login("admin", "admin")
        assertEquals(HttpStatusCode.Created, postJson("/api/v1/staff/users", """{"username":"ana","password":"secret1"}""", admin).status)
        assertEquals("user_exists", code(postJson("/api/v1/staff/users", """{"username":"ana","password":"secret1"}""", admin).bodyAsText()))
        assertEquals("bad_username", code(postJson("/api/v1/staff/users", """{"username":"a b","password":"secret1"}""", admin).bodyAsText()))
        assertEquals("bad_password", code(postJson("/api/v1/staff/users", """{"username":"bob","password":"x"}""", admin).bodyAsText()))

        val ana = login("ana", "secret1")
        // admin resets ana's password -> ana's session is revoked, new password works
        assertEquals(HttpStatusCode.NoContent, client.put("/api/v1/staff/users/ana/password") {
            contentType(ContentType.Application.Json); setBody("""{"password":"secret2"}"""); bearerAuth(admin)
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me") { bearerAuth(ana) }.status)
        val ana2 = login("ana", "secret2")

        assertEquals("cannot_delete_self", code(client.delete("/api/v1/staff/users/ana") { bearerAuth(ana2) }.bodyAsText()))
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/staff/users/ana") { bearerAuth(admin) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me") { bearerAuth(ana2) }.status) // tokens cascade

        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/auth/logout") { bearerAuth(admin) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me") { bearerAuth(admin) }.status)
    }

    @Test
    fun adminIsSeededOnlyOnceAndPasswordIsConfigurable() {
        val path = createTempFile("twin", ".sqlite").toString()
        app(AuthConfig(adminPassword = "s3cret"), path) {
            assertEquals(HttpStatusCode.Unauthorized, postJson("/api/v1/auth/login", """{"username":"admin","password":"admin"}""").status)
            val t = login("admin", "s3cret")
            client.put("/api/v1/staff/users/admin/password") { contentType(ContentType.Application.Json); setBody("""{"password":"changed"}"""); bearerAuth(t) }
        }
        // Restart on the same DB: users exist, so the configured admin password is NOT re-applied.
        app(AuthConfig(adminPassword = "s3cret"), path) {
            assertNotEquals(HttpStatusCode.OK, postJson("/api/v1/auth/login", """{"username":"admin","password":"s3cret"}""").status)
            login("admin", "changed")
        }
    }
}

package ch.lauzhack.twin.passenger

import io.ktor.server.config.ApplicationConfig

/**
 * Settings of the passenger app's server side (ported from sbb-client `server/env.ts`).
 * [elevenLabsKey] comes from `ELEVENLABS_API_KEY` (env only): it never reaches the browser and is never logged.
 */
data class PassengerConfig(
    /** sbb-twin base URL (`twin.url` / `SBB_TWIN_URL`, shared with the assistant). */
    val twinUrl: String = "http://127.0.0.1:8787",
    val elevenLabsKey: String = "",
    /** ElevenLabs conversational agent (`PASSENGER_AGENT_ID`). */
    val agentId: String = "",
    val elevenLabsUrl: String = "https://api.elevenlabs.io",
) {
    val voiceEnabled get() = elevenLabsKey.isNotBlank() && agentId.isNotBlank()

    companion object {
        fun from(conf: ApplicationConfig, env: (String) -> String? = System::getenv): PassengerConfig {
            fun s(key: String) = conf.propertyOrNull(key)?.getString()?.trim()?.takeIf { it.isNotEmpty() }
            val d = PassengerConfig()
            return PassengerConfig(
                twinUrl = s("twin.url") ?: d.twinUrl,
                elevenLabsKey = env("ELEVENLABS_API_KEY")?.trim().orEmpty(),
                agentId = s("passenger.agentId") ?: "",
                elevenLabsUrl = s("passenger.elevenLabsUrl") ?: d.elevenLabsUrl,
            )
        }
    }
}

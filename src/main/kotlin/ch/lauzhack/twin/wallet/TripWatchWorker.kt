package ch.lauzhack.twin.wallet

import ch.lauzhack.twin.db.IncidentRepository
import ch.lauzhack.twin.query.TwinQueries
import ch.lauzhack.twin.twinapi.JourneyCache
import ch.lauzhack.twin.twinapi.SbbTwinClient
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Builds Trip Watch when [config] enables it and starts its worker: the passes are refreshed on every incident change
 * (an [IncidentRepository] listener) or every 20 s. Everything is stopped with the application. Null when disabled.
 */
internal fun Application.startTripWatch(
    config: TripWatchConfig,
    queries: TwinQueries,
    journeys: JourneyCache,
    twinUrl: String,
    incidents: IncidentRepository,
    mqttConnected: () -> Boolean,
): TripWatchService? {
    if (!config.enabled) return null
    val store = TripWatchStore(config.path)
    val service = TripWatchService(
        store, TripWatchJourneys(queries, journeys, SbbTwinClient(twinUrl)),
        config.site.trimEnd('/'), config.apple(), config.google(),
        fresh = { mqttConnected() && queries.status(true).twin?.online == true },
    )
    val changes = Channel<Unit>(Channel.CONFLATED)
    incidents.addListener { _, _ -> changes.trySend(Unit) }
    val worker = CoroutineScope(Dispatchers.IO).launch {
        while (isActive) {
            try {
                service.refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warn("Trip Watch update deferred ({})", error.javaClass.simpleName)
            }
            withTimeoutOrNull(20_000) { changes.receive() }
        }
    }
    monitor.subscribe(ApplicationStopped) {
        runBlocking { worker.cancelAndJoin() }
        changes.close()
        store.close()
    }
    return service
}

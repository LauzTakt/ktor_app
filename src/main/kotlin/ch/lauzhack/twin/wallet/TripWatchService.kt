package ch.lauzhack.twin.wallet

import ch.lauzhack.twin.query.Severity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal class TripWatchService(
    val store: TripWatchStore,
    private val journeys: TripWatchJourneys,
    val site: String,
    val apple: AppleWallet?,
    val google: GoogleWallet?,
    private val fresh: () -> Boolean,
    private val time: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger("wallet")
    private val mutation = Mutex()

    suspend fun start(owner: String, selection: TripWatchSelection): TripWatch? = mutation.withLock {
        val at = time()
        val verified = journeys.resolve(selection, at) ?: return@withLock null
        val legs = verified.legs
        store.activeFor(owner).firstOrNull {
            it.expiresAt > at && it.legs.map(WatchLeg::serviceId) == legs.map(WatchLeg::serviceId) &&
                it.legs.first().from.id == legs.first().from.id && it.destination.id == legs.last().to.id
        }?.let { return@withLock it }
        val leg = legs.first()
        store.put(
            TripWatch(
                id = watchToken(), owner = owner, appleToken = watchToken(), legs = legs,
                expiresAt = legs.last().expectedArrival + 30 * 60_000L,
                advice = if (verified.onBoard) alight(leg) else leg.boardAdvice(),
                onBoard = verified.onBoard, updatedAt = at,
                // already active when the pass is made: shown, not announced
                announcedIncidents = journeys.disruptions(legs).map { it.id },
            ), alert = false,
        )
    }

    suspend fun change(id: String, owner: String, action: String): TripWatch? = mutation.withLock {
        val watch = store.find(id)?.takeIf { it.owner == owner } ?: return@withLock null
        if (watch.ended) return@withLock watch
        val at = time()
        val updated = when {
            action == "stop" || at >= watch.expiresAt -> ended(watch)
            action == "boarded" && !watch.onBoard && watch.advice.action is TripWatchAction.Board ->
                watch.copy(onBoard = true, advice = alight(watch.leg))
            action == "alighted" && watch.onBoard && watch.currentLeg + 1 < watch.legs.size ->
                watch.copy(currentLeg = watch.currentLeg + 1, onBoard = false, advice = watch.legs[watch.currentLeg + 1].boardAdvice())
            action == "alighted" && watch.onBoard -> ended(watch)
            action == "wait" && !watch.onBoard && watch.advice.action == TripWatchAction.CheckTrip ->
                watch.copy(advice = TripWatchAdvice(TripWatchAction.WaitForService))
            else -> return@withLock watch
        }
        store.put(updated.copy(updatedAt = at), alert = false)
    }

    suspend fun googleSave(id: String, owner: String): String? {
        val provider = google ?: return null
        val watch = store.find(id)?.takeIf { it.owner == owner && !it.ended && it.expiresAt > time() } ?: return null
        return mutation.withLock {
            val live = store.find(watch.id)?.takeIf { !it.ended && it.expiresAt > time() } ?: return@withLock null
            provider.ensurePass(live, site)
            // Registration must survive a restart. Delivery may still have to send an older queued alert.
            if (!store.googleIssued(live.id)) store.markGoogleSynced(live)
            provider.saveUrl(live)
        }
    }

    suspend fun refresh() {
        for (job in store.work()) {
            try {
                val latest = mutation.withLock {
                    val watch = store.find(job.watch.id) ?: return@withLock null
                    refreshAdvice(watch, time())
                } ?: continue
                try {
                    deliverApple(latest, job.appleSynced)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.warn("Apple Wallet update deferred ({})", error.javaClass.simpleName)
                }
                if (google != null && job.googleIssued && latest.revision > job.googleSynced) {
                    google.update(latest, site, latest.alertRevision > job.googleSynced && !latest.ended)
                    store.markGoogleSynced(latest)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warn("Trip Watch refresh deferred ({})", error.javaClass.simpleName)
            }
        }
    }

    private suspend fun refreshAdvice(watch: TripWatch, at: Long): TripWatch {
        if (watch.ended) return watch
        if (at >= watch.expiresAt) return store.put(ended(watch).copy(updatedAt = at), alert = false)
        if (!fresh()) {
            val advice = watch.advice.copy(detail = "Live information is temporarily unavailable.")
            return if (advice == watch.advice) watch else store.put(watch.copy(advice = advice, updatedAt = at), alert = false)
        }
        val remaining = watch.legs.drop(watch.currentLeg)
        val observed = remaining.map(journeys::observe)
        val usableLegs = remaining.mapIndexed { index, leg -> observed[index]?.leg ?: leg }
        val current = usableLegs.first()
        val unusable = observed.withIndex().any { (index, leg) ->
            leg?.usable(watch.onBoard && index == 0) == false
        } || usableLegs.zipWithNext().any { (a, b) -> a.expectedArrival > b.expectedDeparture } ||
            (!watch.onBoard && current.expectedDeparture < at && observed.first()?.boardingPassed != false)
        val updated = when {
            (!watch.onBoard && unusable) || watch.advice.action == TripWatchAction.WaitForService -> {
                val alternative = journeys.alternative(current.from.id, watch.destination.id, at)
                when {
                    alternative != null -> watch.copy(
                        legs = watch.legs.take(watch.currentLeg) + alternative,
                        expiresAt = maxOf(watch.expiresAt, alternative.last().expectedArrival + 30 * 60_000L),
                        advice = alternative.first().boardAdvice(),
                    )
                    watch.advice.action == TripWatchAction.WaitForService -> watch.copy(advice = TripWatchAdvice(TripWatchAction.WaitForService))
                    else -> watch.copy(advice = TripWatchAdvice(TripWatchAction.CheckTrip, "Your selected connection has changed."))
                }
            }
            unusable -> watch.copy(advice = TripWatchAdvice(TripWatchAction.CheckTrip, "Your selected connection has changed."))
            else -> watch.copy(
                legs = watch.legs.take(watch.currentLeg) + usableLegs,
                advice = if (watch.onBoard) alight(current) else current.boardAdvice(),
            )
        }
        val delivery = classifyTripWatchUpdate(watch.advice, updated.advice)
        // A new disruption on one of the remaining trains notifies the pass even when the advice stays the same
        // (Google's own reminders only come near the station; this one comes wherever the passenger is).
        val disruptions = journeys.disruptions(updated.legs.drop(updated.currentLeg))
        val news = disruptions.filter { it.id !in watch.announcedIncidents }
        val withNews = if (news.isEmpty()) updated else updated.copy(announcedIncidents = (watch.announcedIncidents + news.map { it.id }).takeLast(50))
        if (withNews == watch) return watch
        val text = news.firstOrNull()?.let { "${Severity.parse(it.severity).word}: ${it.title}. ${withNews.instruction()}." }
        return store.put(withNews.copy(updatedAt = at), alert = delivery == TripWatchDelivery.NOTIFY || news.isNotEmpty(), alertText = text)
    }

    private suspend fun deliverApple(watch: TripWatch, synced: Long) {
        val provider = apple ?: return
        if (watch.revision <= synced) return
        val registrations = store.registrations(watch.id)
        if (registrations.isEmpty()) return
        for (entry in registrations) {
            if (!provider.wake(entry.pushToken)) store.unregister(watch.id, entry.device)
        }
        store.markAppleSynced(watch)
    }

    private fun ended(watch: TripWatch) = watch.copy(
        ended = true, advice = TripWatchAdvice(TripWatchAction.CheckTrip), alertText = null,
    )

    private fun alight(leg: WatchLeg) = TripWatchAdvice(TripWatchAction.Alight(leg.serviceId, leg.to.id),
        if (watchTime(leg.expectedArrival) != watchTime(leg.arrival)) "Expected arrival ${watchTime(leg.expectedArrival)}." else null)
}

private val watchRandom = SecureRandom()
internal fun watchToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(watchRandom::nextBytes))
internal fun watchOwner(token: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray()),
)

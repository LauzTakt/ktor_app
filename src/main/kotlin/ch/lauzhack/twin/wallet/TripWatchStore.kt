package ch.lauzhack.twin.wallet

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.ResultSet

@Serializable
internal data class TripWatch(
    val id: String,
    val owner: String,
    val appleToken: String,
    val legs: List<WatchLeg>,
    val expiresAt: Long,
    val advice: TripWatchAdvice,
    val currentLeg: Int = 0,
    val onBoard: Boolean = false,
    val ended: Boolean = false,
    val updatedAt: Long = 0,
    val revision: Long = 0,
    val alertRevision: Long = 0,
    val alertText: String? = null,
) {
    val leg get() = legs[currentLeg]
    val destination get() = legs.last().to

    fun instruction(): String = when (advice.action) {
        is TripWatchAction.Board -> "Take ${leg.line} from ${leg.from.name} at ${watchTime(leg.departure)}"
        is TripWatchAction.Alight -> "Get off ${leg.line} at ${leg.to.name}"
        TripWatchAction.WaitForService -> "Wait for a confirmed departure"
        TripWatchAction.CheckTrip -> if (ended) "Trip watch ended" else "Check your connection"
    }
}

internal data class WatchRegistration(val device: String, val pushToken: String)
internal data class WatchDelivery(val watch: TripWatch, val appleSynced: Long, val googleSynced: Long, val googleIssued: Boolean)

/** A dedicated SQLite connection for watch state and provider cursors. */
internal class TripWatchStore(path: String) : AutoCloseable {
    private val connection = run {
        if (path != ":memory:") Path.of(path).parent?.let { Files.createDirectories(it) }
        DriverManager.getConnection("jdbc:sqlite:$path")
    }
    private val json = Json { ignoreUnknownKeys = true }

    init {
        connection.createStatement().use { st ->
            st.execute("PRAGMA busy_timeout = 5000")
            st.execute("PRAGMA journal_mode = WAL")
            st.execute("PRAGMA foreign_keys = ON")
            st.execute(
                """CREATE TABLE IF NOT EXISTS watch (
                    id TEXT PRIMARY KEY, owner TEXT NOT NULL, ended INTEGER NOT NULL, revision INTEGER NOT NULL UNIQUE,
                    state TEXT NOT NULL, apple_synced INTEGER DEFAULT 0, google_synced INTEGER DEFAULT 0,
                    google_issued INTEGER DEFAULT 0
                )""",
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS apple_registration (
                    watch_id TEXT REFERENCES watch(id) ON DELETE CASCADE, device TEXT, token TEXT,
                    PRIMARY KEY(watch_id, device)
                )""",
            )
        }
    }

    @Synchronized
    fun put(watch: TripWatch, alert: Boolean): TripWatch {
        connection.autoCommit = false
        try {
            val revision = connection.createStatement().use { st ->
                st.executeQuery("SELECT COALESCE(MAX(revision), 0) + 1 FROM watch").use { it.next(); it.getLong(1) }
            }
            val saved = watch.copy(
                revision = revision,
                alertRevision = if (alert) revision else watch.alertRevision,
                alertText = if (alert) watch.instruction() else watch.alertText,
            )
            connection.prepareStatement(
                """INSERT INTO watch(id, owner, ended, revision, state) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET ended=excluded.ended, revision=excluded.revision, state=excluded.state""",
            ).use { st ->
                st.setString(1, saved.id)
                st.setString(2, saved.owner)
                st.setBoolean(3, saved.ended)
                st.setLong(4, saved.revision)
                st.setString(5, json.encodeToString(saved))
                st.executeUpdate()
            }
            connection.commit()
            return saved
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    @Synchronized
    fun find(id: String): TripWatch? = connection.prepareStatement("SELECT state FROM watch WHERE id=?").use { st ->
        st.setString(1, id)
        st.executeQuery().use { if (it.next()) it.watch() else null }
    }

    @Synchronized
    fun activeFor(owner: String): List<TripWatch> =
        connection.prepareStatement("SELECT state FROM watch WHERE owner=? AND ended=0").use { st ->
            st.setString(1, owner)
            st.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.watch()) } }
        }

    @Synchronized
    fun work(): List<WatchDelivery> = connection.createStatement().use { st ->
        st.executeQuery(
            """SELECT * FROM watch WHERE ended=0 OR
                (revision>apple_synced AND EXISTS(SELECT 1 FROM apple_registration WHERE watch_id=watch.id)) OR
                (google_issued=1 AND revision>google_synced)""",
        ).use { rows ->
            buildList { while (rows.next()) add(WatchDelivery(
                rows.watch(), rows.getLong("apple_synced"), rows.getLong("google_synced"), rows.getBoolean("google_issued"),
            )) }
        }
    }

    @Synchronized
    fun googleIssued(id: String): Boolean = connection.prepareStatement("SELECT google_issued FROM watch WHERE id=?").use { st ->
        st.setString(1, id)
        st.executeQuery().use { it.next() && it.getBoolean(1) }
    }

    @Synchronized
    fun markAppleSynced(watch: TripWatch) = updateRevision("UPDATE watch SET apple_synced=MAX(apple_synced, ?) WHERE id=?", watch)

    @Synchronized
    fun markGoogleSynced(watch: TripWatch) = updateRevision(
        "UPDATE watch SET google_issued=1, google_synced=MAX(google_synced, ?) WHERE id=?", watch,
    )

    private fun updateRevision(sql: String, watch: TripWatch) {
        connection.prepareStatement(sql).use {
            it.setLong(1, watch.revision)
            it.setString(2, watch.id)
            it.executeUpdate()
        }
    }

    @Synchronized
    fun register(watch: String, device: String, token: String): Boolean {
        val created = connection.prepareStatement("INSERT OR IGNORE INTO apple_registration VALUES (?, ?, ?)").use {
            it.setString(1, watch); it.setString(2, device); it.setString(3, token)
            it.executeUpdate() == 1
        }
        if (!created) connection.prepareStatement("UPDATE apple_registration SET token=? WHERE watch_id=? AND device=?").use {
            it.setString(1, token); it.setString(2, watch); it.setString(3, device)
            it.executeUpdate()
        }
        return created
    }

    @Synchronized
    fun unregister(watch: String, device: String) {
        connection.prepareStatement("DELETE FROM apple_registration WHERE watch_id=? AND device=?").use {
            it.setString(1, watch); it.setString(2, device)
            it.executeUpdate()
        }
    }

    @Synchronized
    fun registrations(watch: String): List<WatchRegistration> =
        connection.prepareStatement("SELECT * FROM apple_registration WHERE watch_id=?").use { st ->
            st.setString(1, watch)
            st.executeQuery().use { rows -> buildList {
                while (rows.next()) add(WatchRegistration(rows.getString("device"), rows.getString("token")))
            } }
        }

    @Synchronized
    fun updated(device: String, after: Long): List<TripWatch> =
        connection.prepareStatement(
            "SELECT state FROM watch JOIN apple_registration ON watch.id=watch_id WHERE device=? AND revision>? ORDER BY revision",
        ).use { st ->
            st.setString(1, device)
            st.setLong(2, after)
            st.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.watch()) } }
        }

    private fun ResultSet.watch(): TripWatch = json.decodeFromString(getString("state"))

    @Synchronized
    override fun close() = connection.close()
}

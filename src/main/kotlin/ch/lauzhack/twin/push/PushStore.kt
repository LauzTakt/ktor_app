package ch.lauzhack.twin.push

import ch.lauzhack.twin.db.Database
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.util.Base64

/** A device that can receive push notifications: a browser (Web Push) or a native app (FCM token). */
data class PushDevice(
    val id: String,
    /** `web` | `fcm`. */
    val kind: String,
    /** Web Push endpoint URL, or the FCM registration token. */
    val address: String,
    /** Web Push only: client public key (base64url, uncompressed P-256) and auth secret (base64url). */
    val p256dh: String?,
    val auth: String?,
    /** Free text from the client: `android`, `ios`, `web`, ... */
    val platform: String?,
    val createdAt: Long,
)

/** What a device follows: a train (optionally with the station the passenger gets off at) or a whole line. */
data class PushSubscription(
    val id: String,
    val deviceId: String,
    /** `train` | `line`. */
    val kind: String,
    /** trainId, or the line name (e.g. `IC 1`). */
    val target: String,
    /** Train only: arrival alerts for this station (null = incidents only). */
    val stopId: String?,
    val createdAt: Long,
    /** Arrival alerts already sent (so they go out once). */
    val approachingSent: Boolean = false,
    val arrivedSent: Boolean = false,
    /** Labels stored at creation, for notification texts and the subscription list. */
    val label: String? = null,
    val stopName: String? = null,
)

/**
 * SQLite tables for push notifications (same file as [Database], through [Database.withConnection]):
 * - `push_device`: one row per browser / app installation (unique endpoint or FCM token);
 * - `push_subscription`: train or line followed by a device (deleted with the device);
 * - `push_notified`: incidents already announced to a subscription, so it gets exactly one "created" and one
 *   "resolved" notification per incident;
 * - `push_setting`: key/value (the generated VAPID key pair).
 */
class PushStore(private val db: Database) {
    private val rnd = SecureRandom()

    init {
        db.withConnection { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS push_device (
                      id          TEXT PRIMARY KEY,             -- d-<random>, also the client's secret handle
                      kind        TEXT NOT NULL,                -- web | fcm
                      address     TEXT NOT NULL UNIQUE,         -- Web Push endpoint or FCM token
                      p256dh      TEXT,
                      auth        TEXT,
                      platform    TEXT,
                      created_at  INTEGER NOT NULL,
                      updated_at  INTEGER NOT NULL
                    )""",
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS push_subscription (
                      id               TEXT PRIMARY KEY,        -- s-<random>
                      device_id        TEXT NOT NULL REFERENCES push_device(id) ON DELETE CASCADE,
                      kind             TEXT NOT NULL,           -- train | line
                      target           TEXT NOT NULL,           -- trainId or line name
                      stop_id          TEXT,                    -- train: arrival station (NULL = incidents only)
                      label            TEXT,                    -- e.g. "IC 1 to Genève-Aéroport"
                      stop_name        TEXT,
                      approaching_sent INTEGER NOT NULL DEFAULT 0,
                      arrived_sent     INTEGER NOT NULL DEFAULT 0,
                      created_at       INTEGER NOT NULL,
                      UNIQUE(device_id, kind, target)
                    )""",
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_push_subscription_device ON push_subscription(device_id)")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS push_notified (
                      subscription_id TEXT NOT NULL REFERENCES push_subscription(id) ON DELETE CASCADE,
                      incident_id     TEXT NOT NULL,
                      PRIMARY KEY (subscription_id, incident_id)
                    )""",
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_push_notified_incident ON push_notified(incident_id)")
                st.executeUpdate("CREATE TABLE IF NOT EXISTS push_setting (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            }
        }
    }

    private fun newId(prefix: String): String {
        val b = ByteArray(16).also(rnd::nextBytes)
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    // ---------------------------------------------------------------- settings

    fun setting(key: String): String? = db.withConnection { c ->
        c.prepareStatement("SELECT value FROM push_setting WHERE key=?").use { ps ->
            ps.setString(1, key); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun putSetting(key: String, value: String) = db.withConnection { c ->
        c.prepareStatement("INSERT INTO push_setting(key, value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use { ps ->
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate()
        }
    }

    // ---------------------------------------------------------------- devices

    /** Registers a device, or refreshes it if the same endpoint / token is already known (same id is returned). */
    fun upsertDevice(kind: String, address: String, p256dh: String?, auth: String?, platform: String?, now: Long): PushDevice =
        db.withConnection { c ->
            val existing = c.deviceBy("address", address)
            if (existing != null) {
                c.prepareStatement("UPDATE push_device SET kind=?, p256dh=?, auth=?, platform=?, updated_at=? WHERE id=?").use { ps ->
                    ps.setString(1, kind); ps.setString(2, p256dh); ps.setString(3, auth); ps.setString(4, platform)
                    ps.setLong(5, now); ps.setString(6, existing.id); ps.executeUpdate()
                }
                existing.copy(kind = kind, p256dh = p256dh, auth = auth, platform = platform)
            } else {
                val d = PushDevice(newId("d-"), kind, address, p256dh, auth, platform, now)
                c.prepareStatement("INSERT INTO push_device(id, kind, address, p256dh, auth, platform, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)").use { ps ->
                    ps.setString(1, d.id); ps.setString(2, kind); ps.setString(3, address); ps.setString(4, p256dh)
                    ps.setString(5, auth); ps.setString(6, platform); ps.setLong(7, now); ps.setLong(8, now); ps.executeUpdate()
                }
                d
            }
        }

    fun device(id: String): PushDevice? = db.withConnection { it.deviceBy("id", id) }

    /** Deletes the device and (cascade) its subscriptions. */
    fun deleteDevice(id: String): Boolean = db.withConnection { c ->
        c.prepareStatement("DELETE FROM push_device WHERE id=?").use { ps -> ps.setString(1, id); ps.executeUpdate() > 0 }
    }

    private fun Connection.deviceBy(col: String, value: String): PushDevice? =
        prepareStatement("SELECT * FROM push_device WHERE $col=?").use { ps ->
            ps.setString(1, value)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else PushDevice(
                    rs.getString("id"), rs.getString("kind"), rs.getString("address"), rs.getString("p256dh"),
                    rs.getString("auth"), rs.getString("platform"), rs.getLong("created_at"),
                )
            }
        }

    // ---------------------------------------------------------------- subscriptions

    /** Creates the subscription, or replaces the one the device already has for the same train / line. */
    fun upsertSubscription(deviceId: String, kind: String, target: String, stopId: String?, label: String?, stopName: String?, now: Long): PushSubscription =
        db.withConnection { c ->
            c.prepareStatement("DELETE FROM push_subscription WHERE device_id=? AND kind=? AND target=?").use { ps ->
                ps.setString(1, deviceId); ps.setString(2, kind); ps.setString(3, target); ps.executeUpdate()
            }
            val s = PushSubscription(newId("s-"), deviceId, kind, target, stopId, now, label = label, stopName = stopName)
            c.prepareStatement(
                "INSERT INTO push_subscription(id, device_id, kind, target, stop_id, label, stop_name, created_at) VALUES (?,?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, s.id); ps.setString(2, deviceId); ps.setString(3, kind); ps.setString(4, target)
                ps.setString(5, stopId); ps.setString(6, label); ps.setString(7, stopName); ps.setLong(8, now); ps.executeUpdate()
            }
            s
        }

    fun subscriptions(deviceId: String): List<PushSubscription> =
        selectSubs("SELECT * FROM push_subscription WHERE device_id=? ORDER BY created_at", deviceId)

    fun allSubscriptions(): List<PushSubscription> = selectSubs("SELECT * FROM push_subscription")

    fun subscription(id: String): PushSubscription? = selectSubs("SELECT * FROM push_subscription WHERE id=?", id).firstOrNull()

    fun deleteSubscription(deviceId: String, id: String): Boolean = db.withConnection { c ->
        c.prepareStatement("DELETE FROM push_subscription WHERE id=? AND device_id=?").use { ps ->
            ps.setString(1, id); ps.setString(2, deviceId); ps.executeUpdate() > 0
        }
    }

    fun deleteSubscription(id: String): Boolean = db.withConnection { c ->
        c.prepareStatement("DELETE FROM push_subscription WHERE id=?").use { ps -> ps.setString(1, id); ps.executeUpdate() > 0 }
    }

    fun markArrivalSent(id: String, approaching: Boolean, arrived: Boolean) = db.withConnection { c ->
        c.prepareStatement("UPDATE push_subscription SET approaching_sent=MAX(approaching_sent, ?), arrived_sent=MAX(arrived_sent, ?) WHERE id=?").use { ps ->
            ps.setInt(1, if (approaching) 1 else 0); ps.setInt(2, if (arrived) 1 else 0); ps.setString(3, id); ps.executeUpdate()
        }
    }

    private fun selectSubs(sql: String, vararg args: String): List<PushSubscription> = db.withConnection { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSub()) } }
        }
    }

    private fun ResultSet.toSub() = PushSubscription(
        id = getString("id"), deviceId = getString("device_id"), kind = getString("kind"), target = getString("target"),
        stopId = getString("stop_id"), createdAt = getLong("created_at"),
        approachingSent = getInt("approaching_sent") != 0, arrivedSent = getInt("arrived_sent") != 0,
        label = getString("label"), stopName = getString("stop_name"),
    )

    // ---------------------------------------------------------------- incidents already announced

    /** Records that [subscriptionId] was told about [incidentId]; false if it already was. */
    fun markNotified(subscriptionId: String, incidentId: String): Boolean = db.withConnection { c ->
        c.prepareStatement("INSERT OR IGNORE INTO push_notified(subscription_id, incident_id) VALUES (?,?)").use { ps ->
            ps.setString(1, subscriptionId); ps.setString(2, incidentId); ps.executeUpdate() > 0
        }
    }

    /** Subscriptions that were told about [incidentId]; the records are deleted (the incident is over). */
    fun takeNotified(incidentId: String): List<PushSubscription> = db.withConnection { c ->
        val subs = c.prepareStatement(
            "SELECT s.* FROM push_subscription s JOIN push_notified n ON n.subscription_id = s.id WHERE n.incident_id=?",
        ).use { ps -> ps.setString(1, incidentId); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSub()) } } }
        c.prepareStatement("DELETE FROM push_notified WHERE incident_id=?").use { ps -> ps.setString(1, incidentId); ps.executeUpdate() }
        subs
    }
}

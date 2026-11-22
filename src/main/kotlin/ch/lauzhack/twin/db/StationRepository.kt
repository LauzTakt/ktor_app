package ch.lauzhack.twin.db

import ch.lauzhack.twin.model.Station

/** `station`: every station seen in a train run so far (outlives the trains in service). */
class StationRepository(private val db: Sqlite) {
    init {
        db.migrate(
            """
            CREATE TABLE IF NOT EXISTS station (
              stop_id       TEXT PRIMARY KEY,           -- UIC/DIDOK id, e.g. 8503000 (Zürich HB)
              name          TEXT NOT NULL,
              lat           REAL,
              lon           REAL,
              first_seen_at INTEGER NOT NULL,           -- epoch ms
              updated_at    INTEGER NOT NULL
            )""",
        )
    }

    /** Upserts [stations] (async); a row only changes when name or position did. */
    fun save(stations: List<Station>) {
        if (stations.isEmpty()) return
        db.submit { conn ->
            val now = System.currentTimeMillis()
            conn.prepareStatement(
                """INSERT INTO station(stop_id, name, lat, lon, first_seen_at, updated_at) VALUES (?,?,?,?,?,?)
                   ON CONFLICT(stop_id) DO UPDATE SET name=excluded.name, lat=excluded.lat, lon=excluded.lon,
                   updated_at=excluded.updated_at
                   WHERE name IS NOT excluded.name OR lat IS NOT excluded.lat OR lon IS NOT excluded.lon""",
            ).use { ps ->
                for (s in stations) {
                    ps.setString(1, s.stopId); ps.setString(2, s.name)
                    ps.setObject(3, s.lat); ps.setObject(4, s.lon)
                    ps.setLong(5, now); ps.setLong(6, now)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    fun all(): List<Station> = db.query("SELECT stop_id, name, lat, lon FROM station") {
        Station(it.getString("stop_id"), it.getString("name"), it.doubleOrNull("lat"), it.doubleOrNull("lon"))
    }
}

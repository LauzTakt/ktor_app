package ch.lauzhack.twin.db

/**
 * The app's SQLite file (`db.path`) and its repositories. Only low-volume data lives here: incidents and their
 * history, the station catalog, staff reports, staff users and tokens (push keeps its own tables, see
 * `push/PushStore.kt`, on the same [sqlite]). Train state is deliberately NOT persisted (see LiveState).
 */
class Database(path: String) : AutoCloseable {
    val sqlite = Sqlite(path)
    val stations = StationRepository(sqlite)
    val incidents = IncidentRepository(sqlite)
    val reports = ReportRepository(sqlite)
    val users = UserRepository(sqlite)
    val tokens = TokenRepository(sqlite)

    override fun close() = sqlite.close()
}

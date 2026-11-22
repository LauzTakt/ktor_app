package ch.lauzhack.twin.db

import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One SQLite file: one connection, one writer thread. Every repository of the file shares it.
 *
 * - [submit]: async write, queued on the `sqlite-writer` thread (MQTT callbacks never block on disk).
 * - [withConnection]: synchronous access under the lock (reads, and writes whose caller needs the result).
 * - [transaction]: like [withConnection], all statements committed together.
 *
 * WAL mode; volumes are tiny, so one lock is plenty.
 */
class Sqlite(path: String) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Sqlite::class.java)
    private val conn: Connection = open(path)
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "sqlite-writer").apply { isDaemon = true } }

    init {
        log.info("SQLite ready at {}", File(path).absolutePath)
    }

    /** Queues [block] on the writer thread; a failure is logged, never thrown to the caller. */
    fun submit(block: (Connection) -> Unit) {
        writer.execute {
            try {
                synchronized(lock) { block(conn) }
            } catch (e: Exception) {
                log.error("SQLite write failed", e)
            }
        }
    }

    /** Synchronous access to the connection under the lock. Keep [block] short: it holds the lock. */
    fun <T> withConnection(block: (Connection) -> T): T = synchronized(lock) { block(conn) }

    /** [block] in one transaction (rolled back if it throws). */
    fun <T> transaction(block: (Connection) -> T): T = synchronized(lock) {
        conn.autoCommit = false
        try {
            block(conn).also { conn.commit() }
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** Rows of [sql] with string [args], mapped by [map]. */
    fun <T> query(sql: String, vararg args: String, map: (ResultSet) -> T): List<T> = withConnection { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    /** Runs DDL statements (a repository's `CREATE TABLE/INDEX IF NOT EXISTS`). */
    fun migrate(vararg ddl: String) = withConnection { c -> c.createStatement().use { st -> ddl.forEach { st.executeUpdate(it) } } }

    override fun close() {
        writer.shutdown()
        writer.awaitTermination(5, TimeUnit.SECONDS)
        synchronized(lock) { conn.close() }
    }

    companion object {
        /** Opens [path] (creating its directory) with the app's PRAGMAs: WAL, foreign keys, 5 s busy timeout. */
        fun open(path: String): Connection {
            if (path != ":memory:") File(path).absoluteFile.parentFile?.mkdirs()
            return DriverManager.getConnection("jdbc:sqlite:$path").also { c ->
                c.createStatement().use { st ->
                    st.execute("PRAGMA busy_timeout=5000")
                    st.execute("PRAGMA journal_mode=WAL")
                    st.execute("PRAGMA synchronous=NORMAL")
                    st.execute("PRAGMA foreign_keys=ON")
                }
            }
        }
    }
}

internal fun ResultSet.longOrNull(col: String): Long? = getLong(col).takeUnless { wasNull() }
internal fun ResultSet.doubleOrNull(col: String): Double? = getDouble(col).takeUnless { wasNull() }

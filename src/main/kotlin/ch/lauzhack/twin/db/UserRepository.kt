package ch.lauzhack.twin.db

import java.sql.ResultSet

/** `app_user`: staff accounts (see auth/Auth.kt); seeded with admin/admin. Synchronous. */
class UserRepository(private val db: Sqlite) {
    init {
        db.migrate(
            """
            CREATE TABLE IF NOT EXISTS app_user (       -- staff accounts (see auth/Auth.kt); seeded with admin/admin
              username      TEXT PRIMARY KEY,
              password_hash TEXT NOT NULL,              -- pbkdf2-sha256$<iterations>$<salt b64>$<hash b64>
              role          TEXT NOT NULL DEFAULT 'staff',
              created_at    INTEGER NOT NULL,
              updated_at    INTEGER NOT NULL
            )""",
        )
    }

    fun count(): Int = db.query("SELECT COUNT(*) AS n FROM app_user") { it.getInt("n") }.first()

    fun get(username: String): UserRow? = db.query("SELECT * FROM app_user WHERE username=?", username) { it.toUserRow() }.firstOrNull()

    fun all(): List<UserRow> = db.query("SELECT * FROM app_user ORDER BY username") { it.toUserRow() }

    /** Returns false if the username is taken. */
    fun insert(u: UserRow): Boolean = db.withConnection { conn ->
        conn.prepareStatement("INSERT OR IGNORE INTO app_user(username, password_hash, role, created_at, updated_at) VALUES (?,?,?,?,?)").use { ps ->
            ps.setString(1, u.username); ps.setString(2, u.passwordHash); ps.setString(3, u.role)
            ps.setLong(4, u.createdAt); ps.setLong(5, u.updatedAt)
            ps.executeUpdate() > 0
        }
    }

    fun updatePassword(username: String, passwordHash: String, at: Long): Boolean = db.withConnection { conn ->
        conn.prepareStatement("UPDATE app_user SET password_hash=?, updated_at=? WHERE username=?").use { ps ->
            ps.setString(1, passwordHash); ps.setLong(2, at); ps.setString(3, username); ps.executeUpdate() > 0
        }
    }

    /** Deletes the user and (cascade) its tokens. */
    fun delete(username: String): Boolean = db.withConnection { conn ->
        conn.prepareStatement("DELETE FROM app_user WHERE username=?").use { ps -> ps.setString(1, username); ps.executeUpdate() > 0 }
    }

    private fun ResultSet.toUserRow() = UserRow(
        getString("username"), getString("password_hash"), getString("role"), getLong("created_at"), getLong("updated_at"),
    )
}

/** One row of `app_user`. Every account is `staff` for now. */
data class UserRow(val username: String, val passwordHash: String, val role: String, val createdAt: Long, val updatedAt: Long)

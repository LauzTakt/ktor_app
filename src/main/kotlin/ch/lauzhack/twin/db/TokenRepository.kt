package ch.lauzhack.twin.db

/** `auth_token`: staff bearer tokens; only their SHA-256 is stored. Synchronous. Needs [UserRepository]'s table. */
class TokenRepository(private val db: Sqlite) {
    init {
        db.migrate(
            """
            CREATE TABLE IF NOT EXISTS auth_token (     -- bearer tokens; only their SHA-256 is stored
              token_hash    TEXT PRIMARY KEY,
              username      TEXT NOT NULL REFERENCES app_user(username) ON DELETE CASCADE,
              created_at    INTEGER NOT NULL,
              expires_at    INTEGER NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS ix_auth_token_user ON auth_token(username)",
        )
    }

    fun insert(tokenHash: String, username: String, createdAt: Long, expiresAt: Long) = db.withConnection { conn ->
        conn.prepareStatement("INSERT INTO auth_token(token_hash, username, created_at, expires_at) VALUES (?,?,?,?)").use { ps ->
            ps.setString(1, tokenHash); ps.setString(2, username); ps.setLong(3, createdAt); ps.setLong(4, expiresAt); ps.executeUpdate()
        }
    }

    /** Username owning a non-expired token, or null. */
    fun userOf(tokenHash: String, now: Long): String? =
        db.query("SELECT username, expires_at FROM auth_token WHERE token_hash=?", tokenHash) { rs ->
            rs.getString("username").takeIf { rs.getLong("expires_at") > now }
        }.firstOrNull()

    fun delete(tokenHash: String) = db.withConnection { conn ->
        conn.prepareStatement("DELETE FROM auth_token WHERE token_hash=?").use { ps -> ps.setString(1, tokenHash); ps.executeUpdate() }
    }

    /** Revokes every token of [username] except [keepHash] (e.g. the caller's own session). */
    fun deleteAllOf(username: String, keepHash: String? = null) = db.withConnection { conn ->
        conn.prepareStatement("DELETE FROM auth_token WHERE username=? AND token_hash<>?").use { ps ->
            ps.setString(1, username); ps.setString(2, keepHash ?: ""); ps.executeUpdate()
        }
    }

    fun deleteExpired(now: Long) = db.withConnection { conn ->
        conn.prepareStatement("DELETE FROM auth_token WHERE expires_at<=?").use { ps -> ps.setLong(1, now); ps.executeUpdate() }
    }
}

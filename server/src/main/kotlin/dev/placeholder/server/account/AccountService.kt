package dev.placeholder.server.account

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2
import dev.placeholder.server.ConflictException
import dev.placeholder.server.InvalidInputException
import dev.placeholder.server.UnauthorizedException
import dev.placeholder.server.infra.InvalidationBus
import dev.placeholder.shared.Credentials
import dev.placeholder.shared.Rules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.Optional

/** A signed-in session: who it is and the opaque token for the cookie. */
data class SignedIn(
    val user: User,
    val token: String,
    val expiresAt: Instant,
)

class AccountService(
    private val repository: AccountRepository,
    private val bus: InvalidationBus,
) {
    private val random = SecureRandom()

    /** token hash → user (or empty for "no such session"), so most requests skip the database. */
    private val sessionCache: Cache<String, Optional<User>> =
        Caffeine
            .newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build()

    init {
        bus.listen { topic, key -> if (topic == SESSION_TOPIC) sessionCache.invalidate(key) }
    }

    suspend fun signUp(
        credentials: Credentials,
        now: Instant = Instant.now(),
    ): SignedIn {
        val email = Rules.normalizeEmail(credentials.email)
        if (!Rules.isEmail(email)) throw InvalidInputException("That doesn't look like an email address.")
        val password = credentials.password
        if (password.length < Rules.MIN_PASSWORD) {
            throw InvalidInputException("Use at least ${Rules.MIN_PASSWORD} characters for your password.")
        }
        if (password.length > Rules.MAX_PASSWORD) throw InvalidInputException("That password is too long.")

        val hash = hash(password)
        val user =
            repository.createUser(email, hash, now)
                ?: throw ConflictException("There's already an account for $email. Sign in instead.")
        return startSession(user, now)
    }

    suspend fun signIn(
        credentials: Credentials,
        now: Instant = Instant.now(),
    ): SignedIn {
        val email = Rules.normalizeEmail(credentials.email)
        val stored = if (Rules.isEmail(email)) repository.findByEmail(email) else null
        // Check against a dummy hash when there's no such account, so response time doesn't reveal which emails exist.
        val ok = verify(credentials.password.take(Rules.MAX_PASSWORD), stored?.passwordHash ?: DUMMY_HASH)
        if (stored == null || !ok) throw UnauthorizedException("That email and password don't match.")
        return startSession(stored.user, now)
    }

    suspend fun signOut(token: String) {
        val hash = hashToken(token)
        repository.deleteSession(hash)
        bus.invalidate(SESSION_TOPIC, hash)
    }

    /** The user behind a session cookie, or null if it's unknown or expired. */
    suspend fun resolve(
        token: String,
        now: Instant = Instant.now(),
    ): User? {
        if (token.length != TOKEN_LENGTH) return null
        val hash = hashToken(token)
        sessionCache.getIfPresent(hash)?.let { return it.orElse(null) }
        val user = repository.findSessionUser(hash, now)
        sessionCache.put(hash, Optional.ofNullable(user))
        return user
    }

    suspend fun cleanUp(now: Instant = Instant.now()): Int = repository.deleteExpiredSessions(now)

    private suspend fun startSession(
        user: User,
        now: Instant,
    ): SignedIn {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = TOKEN_ENCODER.encodeToString(bytes)
        val expiresAt = now.plus(SESSION_LIFETIME)
        repository.createSession(hashToken(token), user.id, now, expiresAt)
        return SignedIn(user, token, expiresAt)
    }

    // Argon2 is deliberately slow; keep it off the request threads.
    private suspend fun hash(password: String): String = withContext(Dispatchers.Default) { argon2(password) }

    private suspend fun verify(
        password: String,
        hash: String,
    ): Boolean =
        withContext(Dispatchers.Default) {
            Password.check(password, hash).with(ARGON2)
        }

    private fun hashToken(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray()))

    companion object {
        val SESSION_LIFETIME: Duration = Duration.ofDays(30)
        private const val SESSION_TOPIC = "session"

        /** 256 random bits per session token, sent as unpadded URL-safe base64: always 43 characters. */
        private const val TOKEN_BYTES = 32
        private val TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val TOKEN_LENGTH = TOKEN_ENCODER.encodeToString(ByteArray(TOKEN_BYTES)).length

        // OWASP's recommended Argon2id settings: 19 MiB memory, 2 iterations, 1 lane, a 32-byte hash, a 16-byte salt.
        private val ARGON2: Argon2Function = Argon2Function.getInstance(19_456, 2, 1, 32, Argon2.ID)
        private const val SALT_BYTES = 16

        private fun argon2(password: String): String =
            Password
                .hash(password)
                .addRandomSalt(SALT_BYTES)
                .with(ARGON2)
                .result

        /** Checked against when there's no such account, so a wrong email takes as long as a wrong password. */
        private val DUMMY_HASH: String = argon2("placeholder-dummy-password")
    }
}

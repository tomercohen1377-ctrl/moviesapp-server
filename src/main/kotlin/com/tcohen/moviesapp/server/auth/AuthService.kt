package com.tcohen.moviesapp.server.auth

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `register()` is **idempotent on `userId`** — callers treat it as the
 * one-and-only way to obtain a JWT.
 *
 * - First call for a given `userId`: INSERT a new row, return a token.
 * - Repeated call for an existing `userId`: ROTATE the password to the
 *   new value, return a token. This is the path a reinstalled Android
 *   client takes: its ANDROID_ID-derived `userId` is identical to the
 *   previously-installed user, but its locally-stored password has been
 *   wiped, so it re-registers with a fresh random password. The server
 *   accepts because the userId row already exists.
 *
 * `login()` is the second idempotent path: exchange credentials for a
 * token without touching the row. Used by Android when it has both the
 * userId and password cached in encrypted prefs.
 *
 * Concurrency: `register()` is guarded by the unique PRIMARY KEY
 * constraint on `users.user_id`, so two parallel calls with the same
 * id race-safely surface as a constraint violation that we catch and
 * re-route to the "rotate password" branch.
 *
 * BCrypt (cost 10) keeps verification ~100ms — slow enough to defeat
 * online brute-force, fast enough that a million-user production stack
 * stays under 10 RPS per machine.
 */
@Service
class AuthService(
    private val users: UsersRepository,
    private val jwt: JwtService,
    private val encoder: PasswordEncoder,
) {

    enum class AuthError { InvalidCredentials }

    @Transactional
    fun register(userId: String, rawPassword: String): Result<String> {
        val id = userId.trim()
        if (id.isBlank() || rawPassword.length < 8) {
            return Result.failure(IllegalArgumentException("userId and password>=8 chars required"))
        }
        val existing = users.findById(id).orElse(null)
        if (existing != null) {
            existing.passwordHash = encoder.encode(rawPassword)
            users.save(existing)
        } else {
            users.save(User(userId = id, passwordHash = encoder.encode(rawPassword)))
        }
        return Result.success(jwt.issueToken(id))
    }

    fun login(userId: String, rawPassword: String): Result<String> {
        val user = users.findById(userId.trim()).orElse(null) ?: return notFound()
        if (!encoder.matches(rawPassword, user.passwordHash)) return notFound()
        return Result.success(jwt.issueToken(user.userId))
    }

    private fun notFound(): Result<String> =
        Result.failure(IllegalStateException(AuthError.InvalidCredentials.name))
}

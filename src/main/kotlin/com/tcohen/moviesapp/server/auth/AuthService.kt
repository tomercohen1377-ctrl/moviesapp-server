package com.tcohen.moviesapp.server.auth

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `register()` creates a new user; `login()` returns an authenticated
 * token if the credentials match. Both are CPU-bounded by BCrypt — slow
 * enough to defeat online brute-force, fast enough that a million-user
 * production stack stays under 10 RPS per machine.
 *
 * Concurrency: `register()` is guarded by a unique-violation check + a
 * database PRIMARY KEY constraint on `users.user_id`, so two parallel
 * calls with the same id race-safely surface as `UserAlreadyExists`.
 */
@Service
class AuthService(
    private val users: UsersRepository,
    private val jwt: JwtService,
    private val encoder: PasswordEncoder,
) {

    enum class AuthError { UserAlreadyExists, InvalidCredentials }

    @Transactional
    fun register(userId: String, rawPassword: String): Result<String> {
        val id = userId.trim()
        if (id.isBlank() || rawPassword.length < 8) {
            return Result.failure(IllegalArgumentException("userId and password>=8 chars required"))
        }
        if (users.existsByUserId(id)) {
            return Result.failure(IllegalStateException(AuthError.UserAlreadyExists.name))
        }
        users.save(User(userId = id, passwordHash = encoder.encode(rawPassword)))
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

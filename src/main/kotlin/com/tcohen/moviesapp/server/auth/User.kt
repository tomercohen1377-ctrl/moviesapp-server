package com.tcohen.moviesapp.server.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A registered user of the movie-favorites backend.
 *
 * Authentication is now per-user: each user has a BCrypt-hashed password
 * and earns a short-lived JWT on login. The `userId` is the email-style
 * unique handle the Android app uses to call `/users/{userId}/favorites`.
 *
 * Bcrypt strength (cost 10) keeps verification ~100ms, slow enough to
 * defeat online brute-force without taxing the JVM.
 */
@Entity
@Table(name = "users")
class User(
    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    var userId: String = "",

    @Column(name = "password_hash", nullable = false, length = 100)
    var passwordHash: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Long = Instant.now().toEpochMilli(),
)

package com.tcohen.moviesapp.server.favorites

import kotlinx.serialization.Serializable

/**
 * Wire shape for one favorite movie — what the Android app sees over HTTP.
 *
 * `userId` is omitted from outbound payloads (the client already knows it
 * from the URL). `savedAt` is an epoch-ms timestamp so the client doesn't
 * need a date parser — Kotlin's `Instant.ofEpochMilli(...)` consumes it.
 */
@Serializable
data class FavoriteDto(
    val movieId: Int,
    val savedAt: Long,
)

/** Response body for `POST /users/{userId}/favorites/{movieId}`. */
@Serializable
data class AddFavoriteResponse(val created: Boolean)

/** Response body for `GET /users/{userId}/favorites/{movieId}`. */
@Serializable
data class IsFavoriteResponse(val isFavorite: Boolean)

/** Generic error envelope used by the auth filter and exception handlers. */
@Serializable
data class ErrorBody(val error: String)

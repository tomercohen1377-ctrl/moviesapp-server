package com.tcohen.moviesapp.server.favorites

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable

/**
 * JPA entity for one favorite movie. The composite primary key `(userId,
 * movieId)` makes re-POSTing the same movie a database-level no-op and lets
 * us look up "is this favorited?" without a list scan.
 *
 * Uses [IdClass] for the simplest composite key — both fields are part of
 * the table's primary key, no extra `@Embeddable` class needed.
 */
@Entity
@Table(name = "favorites")
@IdClass(FavoriteId::class)
class Favorite(
    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    var userId: String = "",

    @Id
    @Column(name = "movie_id", nullable = false)
    var movieId: Int = 0,

    @Column(name = "saved_at", nullable = false)
    var savedAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Favorite) return false
        return userId == other.userId && movieId == other.movieId
    }

    override fun hashCode(): Int = 31 * userId.hashCode() + movieId

    override fun toString(): String = "Favorite(userId='$userId', movieId=$movieId, savedAt=$savedAt)"
}

/**
 * Composite key required by JPA's [IdClass]. Must be `Serializable` and
 * override `equals` / `hashCode` so Hibernate can identify instances.
 */
data class FavoriteId(
    var userId: String = "",
    var movieId: Int = 0,
) : Serializable

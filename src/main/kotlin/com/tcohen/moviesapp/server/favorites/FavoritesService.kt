package com.tcohen.moviesapp.server.favorites

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Business logic for the favorites resource. Sits between the controller
 * and the [FavoritesRepository] so we can swap the storage layer without
 * touching the wire shape.
 *
 * Behaviour:
 *   - `add`      — idempotent; second call for the same `(userId, movieId)`
 *     returns `created=false`. Underlying unique key constraint makes the
 *     second insert a no-op naturally — we swallow the constraint violation.
 *   - `remove`   — returns whether anything was deleted (callers map to 204/404).
 *   - `list`     — favorites ordered by `savedAt DESC`. Matches the
 *     :app Room fallback so the wire and offline lists feel identical.
 *   - `exists`   — keystroke-cheap check used to populate `isFavorite` on
 *     the detail screen.
 *
 * The class is `open` so Spring can wrap it in a CGLIB proxy (the `kotlin-spring`
 * plugin handles this transparently for the `@Transactional` annotation).
 */
@Service
open class FavoritesService(
    private val repository: FavoritesRepository,
) {

    /**
     * @return true when a new row was inserted; false if it was already there.
     */
    @Transactional
    open fun add(userId: String, movieId: Int): Boolean {
        if (repository.existsByUserIdAndMovieId(userId, movieId)) return false
        return try {
            repository.save(Favorite(userId = userId, movieId = movieId))
            true
        } catch (_: DataIntegrityViolationException) {
            // Race condition: another thread inserted between our check and save.
            false
        }
    }

    @Transactional
    open fun remove(userId: String, movieId: Int): Boolean =
        repository.deleteByUserIdAndMovieId(userId, movieId) > 0

    @Transactional(readOnly = true)
    open fun list(userId: String): List<FavoriteDto> =
        repository.findByUserIdOrderBySavedAtDesc(userId)
            .map { FavoriteDto(it.movieId, it.savedAt) }

    @Transactional(readOnly = true)
    open fun exists(userId: String, movieId: Int): Boolean =
        repository.existsByUserIdAndMovieId(userId, movieId)
}

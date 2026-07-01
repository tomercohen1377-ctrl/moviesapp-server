package com.tcohen.moviesapp.server.favorites

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller for the favorites resource. Auth is enforced upstream by
 * `ApiKeyAuthFilter` so this controller only deals with the resource shape.
 *
 * HTTP shape (matches the Android app's view of the resource):
 *   GET    /users/{userId}/favorites              → 200 List<FavoriteDto>
 *   POST   /users/{userId}/favorites/{movieId}    → 201 if created, 200 if existed
 *   DELETE /users/{userId}/favorites/{movieId}    → 204 No Content | 404 Not Found
 *   GET    /users/{userId}/favorites/{movieId}    → 200 { isFavorite: true } | 404
 *
 * Idempotency: POST is idempotent (a duplicate returns 200 + created=false);
 * DELETE of a missing favorite returns 404.
 */
@RestController
@RequestMapping("/users/{userId}/favorites")
class FavoritesController(
    private val service: FavoritesService,
) {

    @GetMapping
    fun list(@PathVariable userId: String): List<FavoriteDto> =
        service.list(userId)

    @GetMapping("/{movieId}")
    fun isFavorite(
        @PathVariable userId: String,
        @PathVariable movieId: Int,
    ): ResponseEntity<IsFavoriteResponse> =
        if (service.exists(userId, movieId)) {
            ResponseEntity.ok(IsFavoriteResponse(isFavorite = true))
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(IsFavoriteResponse(isFavorite = false))
        }

    @PostMapping("/{movieId}")
    fun add(
        @PathVariable userId: String,
        @PathVariable movieId: Int,
    ): ResponseEntity<AddFavoriteResponse> {
        val created = service.add(userId, movieId)
        val status = if (created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(AddFavoriteResponse(created))
    }

    @DeleteMapping("/{movieId}")
    fun remove(
        @PathVariable userId: String,
        @PathVariable movieId: Int,
    ): ResponseEntity<Void> =
        if (service.remove(userId, movieId)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
}

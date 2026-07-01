package com.tcohen.moviesapp.server.favorites

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * Spring Data JPA repository — derived query methods do all the work.
 *
 * Note how thin this is. Spring Data parses the method names and generates
 * the JPQL at runtime; we never write SQL by hand for these queries.
 *
 *   - `findByXOrderByYDesc(...)`           → SELECT ... ORDER BY Y DESC
 *   - `existsByXAndY(...)`                 → SELECT 1 WHERE X=? AND Y=?
 *   - `deleteByX(...)`                     → DELETE WHERE X=?
 *   - `countByX(...)`                      → SELECT COUNT(*) WHERE X=?
 */
@Repository
interface FavoritesRepository : JpaRepository<Favorite, FavoriteId> {

    fun findByUserIdOrderBySavedAtDesc(userId: String): List<Favorite>

    fun existsByUserIdAndMovieId(userId: String, movieId: Int): Boolean

    fun deleteByUserIdAndMovieId(userId: String, movieId: Int): Long
}

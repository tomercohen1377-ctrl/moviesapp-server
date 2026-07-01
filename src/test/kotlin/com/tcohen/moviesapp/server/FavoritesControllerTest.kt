package com.tcohen.moviesapp.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.tcohen.moviesapp.server.favorites.AddFavoriteResponse
import com.tcohen.moviesapp.server.favorites.FavoriteDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * End-to-end test of the favorites resource — uses MockMvc against the
 * full Spring Boot context, real Hibernate, and Flyway-managed H2 in PostgreSQL mode.
 * No mocking of the framework — same controller/service/repository path runs in production.
 *
 * Coverage:
 *   - POST happy path (201 Created)
 *   - POST idempotent (second POST returns 200 with `created=false`)
 *   - GET list (200, ordered by `savedAt DESC`)
 *   - DELETE (204 when present; 404 when not)
 *   - Missing API key (401)
 *   - GET /{movieId} exists (200 with isFavorite=true) and missing (404)
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["api.key=test-api-key-abc"])
class FavoritesControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var mapper: ObjectMapper

    private val apiKey = "test-api-key-abc"

    @BeforeEach
    fun resetState() {
        jdbc.update("DELETE FROM favorites")
    }

    @Test
    fun `POST creates a favorite and returns 201`() {
        mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-1", 550)
                .header("X-Api-Key", apiKey)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.created").value(true))
    }

    @Test
    fun `POST twice is idempotent and returns 200 second time`() {
        mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-2", 551)
                .header("X-Api-Key", apiKey)
        ).andExpect(status().isCreated)

        val body = mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-2", 551)
                .header("X-Api-Key", apiKey)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString
        val parsed: AddFavoriteResponse = mapper.readValue(body, AddFavoriteResponse::class.java)
        assertFalse(parsed.created)
    }

    @Test
    fun `GET returns the favorites in desc savedAt order`() {
        mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-3", 100).header("X-Api-Key", apiKey)
        )
        Thread.sleep(5) // ensure distinct savedAt for ordering
        mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-3", 200).header("X-Api-Key", apiKey)
        )

        val body = mockMvc.perform(
            get("/users/{userId}/favorites", "u-3").header("X-Api-Key", apiKey)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString
        val list: List<FavoriteDto> = mapper.readValue(
            body,
            mapper.typeFactory.constructCollectionType(List::class.java, FavoriteDto::class.java),
        )
        assertEquals(listOf(200, 100), list.map { it.movieId })
    }

    @Test
    fun `GET single favorite returns isFavorite true and 404 when missing`() {
        mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-g", 9999).header("X-Api-Key", apiKey)
        )

        mockMvc.perform(
            get("/users/{userId}/favorites/{movieId}", "u-g", 9999).header("X-Api-Key", apiKey)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isFavorite").value(true))

        mockMvc.perform(
            get("/users/{userId}/favorites/{movieId}", "u-g", 1234).header("X-Api-Key", apiKey)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.isFavorite").value(false))
    }

    @Test
    fun `DELETE returns 204 when present and 404 otherwise`() {
        mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-4", 777).header("X-Api-Key", apiKey)
        )

        mockMvc.perform(
            delete("/users/{userId}/favorites/{movieId}", "u-4", 777).header("X-Api-Key", apiKey)
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            delete("/users/{userId}/favorites/{movieId}", "u-4", 777).header("X-Api-Key", apiKey)
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `requests without API key are rejected with 401`() {
        mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-5", 1) // no X-Api-Key header
        )
            .andExpect(status().isUnauthorized)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Unauthorized")))
    }

    @Test
    fun `health endpoint is open and returns ok`() {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ok"))
    }

    @Test
    fun `actuator health endpoint is open for cloud health checks`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }
}

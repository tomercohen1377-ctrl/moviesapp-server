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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Bearer-token smoke test for the favorites resource. We register a user
 * via /auth/register (no token needed), then use the issued JWT for the
 * rest of the calls — exactly what the Android client does.
 *
 * Coverage:
 *   - POST happy path (201 Created)
 *   - POST idempotent (second POST returns 200 with `created=false`)
 *   - GET list (200, ordered by `savedAt DESC`)
 *   - DELETE (204 when present; 404 when not)
 *   - Missing/invalid Bearer (401)
 *   - GET /{movieId} exists (200) and missing (404)
 */
@SpringBootTest
@AutoConfigureMockMvc
class FavoritesControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var mapper: ObjectMapper

    private lateinit var token: String

    @BeforeEach
    fun resetState() {
        jdbc.update("DELETE FROM favorites")
        jdbc.update("DELETE FROM users")
        // Bootstrap a user before each test so we always start clean.
        val body = mapper.writeValueAsString(
            mapOf("userId" to "u-bootstrap", "password" to "test-pass-1234"),
        )
        val tokenJson = mockMvc.perform(
            post("/auth/register").contentType("application/json").content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        token = mapper.readTree(tokenJson).get("accessToken").asText()
    }

    private val req = { builder: MockHttpServletRequestBuilder -> builder.header("Authorization", "Bearer $token") }

    private fun MockHttpServletRequestBuilder.authed() = req(this)

    @Test
    fun `POST creates a favorite and returns 201`() {
        mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-bootstrap", 550).authed(),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.created").value(true))
    }

    @Test
    fun `POST twice is idempotent and returns 200 second time`() {
        mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-2", 551).authed(),
        ).andExpect(status().isCreated)

        val body = mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-2", 551).authed(),
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
            post("/users/{userId}/favorites/{movieId}", "u-3", 100).authed(),
        )
        Thread.sleep(5) // ensure distinct savedAt for ordering
        mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-3", 200).authed(),
        )

        val body = mockMvc.perform(
            get("/users/{userId}/favorites", "u-3").authed(),
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
            post("/users/{userId}/favorites/{movieId}", "u-g", 9999).authed(),
        )

        mockMvc.perform(
            get("/users/{userId}/favorites/{movieId}", "u-g", 9999).authed(),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isFavorite").value(true))

        mockMvc.perform(
            get("/users/{userId}/favorites/{movieId}", "u-g", 1234).authed(),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.isFavorite").value(false))
    }

    @Test
    fun `DELETE returns 204 when present and 404 otherwise`() {
        mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-4", 777).authed(),
        )

        mockMvc.perform(
            delete("/users/{userId}/favorites/{movieId}", "u-4", 777).authed(),
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            delete("/users/{userId}/favorites/{movieId}", "u-4", 777).authed(),
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `requests without a Bearer token are rejected with 401`() {
        mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-5", 1), // no Authorization
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").exists())
    }

    @Test
    fun `requests with an invalid Bearer token are rejected with 401`() {
        mockMvc.perform(
            post("/users/{userId}/favorites/{movieId}", "u-5", 1)
                .header("Authorization", "Bearer not-a-real-token"),
        )
            .andExpect(status().isUnauthorized)
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

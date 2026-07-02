package com.tcohen.moviesapp.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * End-to-end auth flow against the real Spring Boot context.
 *
 * Coverage:
 *   - `/auth/register` happy path returns JWT
 *   - `/auth/register` repeats the same user → 409 Conflict
 *   - `/auth/token` happy path returns JWT
 *   - `/auth/token` wrong password → 401
 *   - `/auth/token` unknown user → 401
 *   - `/auth/whoami` with the issued JWT echoes `userId`
 *   - `/auth/whoami` without a JWT → 401
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var mapper: ObjectMapper

    @BeforeEach
    fun clean() { jdbc.update("DELETE FROM users") }

    private fun creds(userId: String, password: String) =
        mapper.writeValueAsString(mapOf("userId" to userId, "password" to password))

    @Test
    fun `register happy path returns JWT`() {
        mockMvc.perform(
            post("/auth/register").contentType("application/json")
                .content(creds("u1", "password-1234")),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
    }

    @Test
    fun `register duplicate returns 409`() {
        mockMvc.perform(
            post("/auth/register").contentType("application/json").content(creds("u1", "password-1234")),
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/auth/register").contentType("application/json").content(creds("u1", "another-1234")),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("UserAlreadyExists"))
    }

    @Test
    fun `login happy path returns JWT`() {
        mockMvc.perform(
            post("/auth/register").contentType("application/json").content(creds("login-user", "right-passw0rd")),
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/auth/token").contentType("application/json").content(creds("login-user", "right-passw0rd")),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").exists())
    }

    @Test
    fun `login with wrong password returns 401`() {
        mockMvc.perform(
            post("/auth/register").contentType("application/json").content(creds("login2", "correct-one")),
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/auth/token").contentType("application/json").content(creds("login2", "incorrect")),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("InvalidCredentials"))
    }

    @Test
    fun `login of unknown user returns 401`() {
        mockMvc.perform(
            post("/auth/token").contentType("application/json").content(creds("ghost", "anything-here")),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `whoami returns the JWT subject as userId`() {
        val token = mockMvc.perform(
            post("/auth/register").contentType("application/json")
                .content(creds("who-user", "secure-pass-1234")),
        ).andReturn().response.contentAsString.let {
            mapper.readTree(it).get("accessToken").asText()
        }
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/whoami")
                .header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value("who-user"))
    }

    @Test
    fun `whoami without token returns 401`() {
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/whoami"),
        )
            .andExpect(status().isUnauthorized)
    }
}

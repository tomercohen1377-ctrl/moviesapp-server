package com.tcohen.moviesapp.server.auth

import com.tcohen.moviesapp.server.favorites.ErrorBody
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * POST /auth/register   — create a user, return its first JWT
 * POST /auth/token      — login (username + password) → JWT
 * GET  /auth/jwks.json  — publish the public key as a JWK set
 *
 * These endpoints are intentionally **unauthenticated**: registration
 * bootstraps the user (the first device signs up), and login exchanges
 * for a JWT. Everything else on the server requires `Authorization:
 * Bearer <jwt>`.
 *
 * Request/response shapes are Jackson-compatible Kotlin data classes —
 * we don't reach for kotlinx.serialization here because Spring Boot's
 * default Jackson+kotlin module is already on the classpath. Keeping
 * the surface consistent avoids forcing two JSON converters.
 */
@RestController
@RequestMapping("/auth")
class AuthController(
    private val auth: AuthService,
    private val jwt: JwtService,
) {

    data class Credentials(val userId: String, val password: String)

    data class TokenResponse(
        val accessToken: String,
        val tokenType: String = "Bearer",
    )

    data class JwksResponse(val keys: List<Map<String, Any?>>)

    data class WhoAmIResponse(val userId: String)

    @PostMapping("/register")
    fun register(@RequestBody creds: Credentials): ResponseEntity<Any> =
        auth.register(creds.userId, creds.password)
            .fold(
                onSuccess = { ResponseEntity.ok(TokenResponse(accessToken = it)) },
                onFailure = { errorBody(it) },
            )

    @PostMapping("/token")
    fun token(@RequestBody creds: Credentials): ResponseEntity<Any> =
        auth.login(creds.userId, creds.password)
            .fold(
                onSuccess = { ResponseEntity.ok(TokenResponse(accessToken = it)) },
                onFailure = { errorBody(it) },
            )

    @GetMapping("/jwks.json", produces = ["application/json"])
    fun jwks(): JwksResponse = JwksResponse(keys = listOf(jwt.jwk()))

    /** Echo the bearer context back so callers can hit `/auth/whoami` for testing. */
    @GetMapping("/whoami")
    fun whoami(request: HttpServletRequest): ResponseEntity<Any> {
        val userId = request.getAttribute("userId") as? String
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorBody("Unauthorized"))
        return ResponseEntity.ok(WhoAmIResponse(userId = userId))
    }

    private fun errorBody(e: Throwable): ResponseEntity<Any> {
        val message = (e.message ?: e.javaClass.simpleName)
        val status = when (message) {
            "UserAlreadyExists" -> HttpStatus.CONFLICT
            "InvalidCredentials" -> HttpStatus.UNAUTHORIZED
            else -> HttpStatus.BAD_REQUEST
        }
        return ResponseEntity.status(status)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(ErrorBody(message))
    }
}

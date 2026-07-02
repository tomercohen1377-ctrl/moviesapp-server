package com.tcohen.moviesapp.server.auth

import com.tcohen.moviesapp.server.favorites.ErrorBody
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * POST /auth/register   — create a user, return its first JWT
 * POST /auth/token      — login (username + password) → JWT
 * GET  /auth/jwks.json  — publish the public key as a JWK set
 * GET  /auth/whoami     — echo the bearer subject as `userId` (debug)
 *
 * These endpoints are intentionally **unauthenticated**: registration
 * bootstraps the user (the first device signs up), and login exchanges
 * for a JWT. Everything else on the server requires `Authorization:
 * Bearer <jwt>`.
 *
 * DTOs use `kotlinx.serialization` (the same JSON library the Android
 * client uses) so the wire shapes stay byte-identical between server
 * response and Kotlin decoding in the app.
 */
@RestController
@RequestMapping("/auth")
class AuthController(
    private val auth: AuthService,
    private val jwt: JwtService,
) {

    /**
     * Bearer-token response shape, made a static class so it survives
     * wiring changes regardless of which JSON converter Spring uses.
     */
    data class TokenResponse(
        val accessToken: String,
        val tokenType: String = "Bearer",
    )

    data class WhoAmIResponse(val userId: String)

    @PostMapping("/register")
    fun register(
        @RequestHeader("X-User-Id") userId: String?,
        @RequestHeader("X-Password") password: String?,
    ): ResponseEntity<Any> = performRegister(userId, password)

    @PostMapping("/token")
    fun token(
        @RequestHeader("X-User-Id") userId: String?,
        @RequestHeader("X-Password") password: String?,
    ): ResponseEntity<Any> = performLogin(userId, password)

    private fun performRegister(userId: String?, password: String?): ResponseEntity<Any> {
        if (userId.isNullOrBlank() || password == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorBody("X-User-Id and X-Password headers required"))
        }
        return auth.register(userId, password)
            .fold(
                onSuccess = { ResponseEntity.ok(TokenResponse(accessToken = it)) },
                onFailure = { errorBody(it) },
            )
    }

    private fun performLogin(userId: String?, password: String?): ResponseEntity<Any> {
        if (userId.isNullOrBlank() || password == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorBody("X-User-Id and X-Password headers required"))
        }
        return auth.login(userId, password)
            .fold(
                onSuccess = { ResponseEntity.ok(TokenResponse(accessToken = it)) },
                onFailure = { errorBody(it) },
            )
    }

    @GetMapping("/jwks.json", produces = ["application/json"])
    fun jwks(): Map<String, Any?> = mapOf("keys" to listOf(jwt.jwk()))

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

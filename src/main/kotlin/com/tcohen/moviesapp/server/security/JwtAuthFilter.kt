package com.tcohen.moviesapp.server.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.tcohen.moviesapp.server.favorites.ErrorBody
import com.tcohen.moviesapp.server.auth.JwtService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val BEARER_PREFIX: String = "Bearer "

/**
 * Bearer-token authentication. Replaces the previous X-Api-Key shared-secret
 * filter; tokens are RS256-signed JWTs issued by `AuthController`.
 *
 * Open paths:
 *   - `/health`, `/actuator/health`      — liveness probes
 *   - `/auth/register`, `/auth/token`    — credential exchange
 *   - `/auth/jwks.json`                  — public key for verifiers
 *
 * Closed paths must carry:
 *   `Authorization: Bearer <jwt>` whose ISS = "moviesapp-server" and
 *   whose signature verifies against the public key in `/auth/jwks.json`.
 *
 * On success, the verified `subject` is exposed as the `userId` request
 * attribute — controllers/filters downstream can read it with
 * `request.getAttribute("userId") as String`.
 */
@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter() {

    private val mapper = ObjectMapper()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (isOpenPath(request.requestURI)) {
            filterChain.doFilter(request, response)
            return
        }
        val token = extractBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION))
        if (token == null) {
            reject(response, "Missing Bearer token")
            return
        }
        val userId = jwtService.verify(token)
        if (userId == null) {
            reject(response, "Invalid or expired token")
            return
        }
        try {
            request.setAttribute("userId", userId)
        } catch (_: IllegalStateException) {
            // Request is being recycled by the servlet container; abort.
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun extractBearerToken(header: String?): String? {
        if (header.isNullOrBlank() || !header.startsWith(BEARER_PREFIX)) return null
        val raw = header.substring(BEARER_PREFIX.length).trim()
        return raw.ifBlank { null }
    }

    private fun isOpenPath(path: String): Boolean =
        path == "/health" ||
            path.startsWith("/health/") ||
            path == "/actuator/health" ||
            path.startsWith("/actuator/health/") ||
            path == "/auth/register" ||
            path == "/auth/token" ||
            path == "/auth/jwks.json" ||
            path.startsWith("/auth/jwks.json/")

    private fun reject(response: HttpServletResponse, message: String) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        response.writer.write(mapper.writeValueAsString(ErrorBody(message)))
    }
}

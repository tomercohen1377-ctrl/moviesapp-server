package com.tcohen.moviesapp.server.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.tcohen.moviesapp.server.favorites.ErrorBody
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

private const val API_KEY_HEADER: String = "X-Api-Key"
private const val HEALTH_PATH: String = "/health"
private const val ACTUATOR_HEALTH_PATH: String = "/actuator/health"

/**
 * Simple shared-secret authentication: read the expected key from the
 * `api.key` property (which `application.yml` wires from the `API_KEY`
 * env var), reject any non-`/health` request that doesn't include a
 * matching `X-Api-Key` header.
 *
 * Implemented as a servlet filter so the gate lives in one predictable
 * place. If/when JWT lands, swap for `spring-boot-starter-security` with
 * a `Bearer` provider; controllers don't change.
 *
 * If `api.key` is empty (env not set), *every* authed request is rejected
 * — intentional. Nothing gets unrestricted access by accident.
 */
@Component
class ApiKeyAuthFilter(
    @Value("\${api.key:}") private val expectedKey: String,
) : OncePerRequestFilter() {

    private val mapper = ObjectMapper()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (isOpenHealthPath(request.requestURI)) {
            filterChain.doFilter(request, response)
            return
        }
        val provided = request.getHeader(API_KEY_HEADER)
        if (expectedKey.isBlank() || provided != expectedKey) {
            reject(response)
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun isOpenHealthPath(path: String): Boolean =
        path == HEALTH_PATH ||
            path.startsWith("$HEALTH_PATH/") ||
            path == ACTUATOR_HEALTH_PATH ||
            path.startsWith("$ACTUATOR_HEALTH_PATH/")

    @Throws(IOException::class)
    private fun reject(response: HttpServletResponse) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        response.writer.write(mapper.writeValueAsString(ErrorBody("Unauthorized")))
    }
}

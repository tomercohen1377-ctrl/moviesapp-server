package com.tcohen.moviesapp.server.health

import kotlinx.serialization.Serializable
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Liveness endpoint. Useful as a Fly.io / Render / Kubernetes health check
 * and for ad-hoc curl smoke tests.
 *
 * Unauthenticated so the platform's uptime checker can hit it without
 * needing the API key.
 */
@RestController
class HealthController {

    @GetMapping("/health", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun health(): HealthBody = HealthBody("ok")
}

@Serializable
data class HealthBody(val status: String)

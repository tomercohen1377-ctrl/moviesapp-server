package com.tcohen.moviesapp.server.security

import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Force Spring MVC to use Jackson for `application/json` request/response
 * bodies. We've configured kotlinx-serialization-json for our domain DTOs,
 * but the Spring autoconfigured `KotlinSerializationJsonHttpMessageConverter`
 * sits ahead of Jackson and rejects any `@RequestBody` data class that
 * isn't `@Serializable`. We're sticking with Jackson on the wire surface
 * (matches the Android client which uses Gson anyway).
 */
@Configuration
class WebMvcConfig : WebMvcConfigurer {

    override fun configureMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
        converters.removeAll { converter ->
            converter.canRead(Any::class.java, MediaType.APPLICATION_JSON) &&
                converter.javaClass.simpleName.contains("Serialization")
        }
        converters.add(0, MappingJackson2HttpMessageConverter())
    }
}

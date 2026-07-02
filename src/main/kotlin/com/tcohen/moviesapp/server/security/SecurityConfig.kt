package com.tcohen.moviesapp.server.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Spring Security configuration that complements `JwtAuthFilter`.
 *
 * We don't lean on Spring's HTTP-Basic / Form-login defaults — they would
 * conflict with our Bearer-token model. Instead, we tell Spring Security
 * to accept all requests and *not* create sessions; our `JwtAuthFilter`
 * (registered here on the chain before Spring's auth check) is the source
 * of truth for authentication.
 *
 * BCryptPasswordEncoder is exposed as a bean for the auth service.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(10)

    @Bean
    fun filterChain(http: HttpSecurity, jwtAuthFilter: JwtAuthFilter): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .httpBasic(AbstractHttpConfigurer<*, *>::disable)
            .formLogin(AbstractHttpConfigurer<*, *>::disable)
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            // Insert the Bearer JWT filter early so it short-circuits with
            // 401 before any other Spring Security filter reaches the request.
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}

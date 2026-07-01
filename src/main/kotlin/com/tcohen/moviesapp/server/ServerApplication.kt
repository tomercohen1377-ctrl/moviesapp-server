package com.tcohen.moviesapp.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Spring Boot entry point.
 *
 * `@SpringBootApplication` enables:
 *   - `@Configuration`     — this class is a source of bean definitions
 *   - `@EnableAutoConfiguration` — Spring Boot configures beans based on the
 *     classpath (so adding `spring-boot-starter-data-jpa` auto-configures
 *     Hibernate + DataSource)
 *   - `@ComponentScan`     — beans in `com.tcohen.moviesapp.server` and below
 *     are picked up automatically (controllers, services, repositories, filters)
 */
@SpringBootApplication
class ServerApplication

fun main(args: Array<String>) {
    runApplication<ServerApplication>(*args)
}

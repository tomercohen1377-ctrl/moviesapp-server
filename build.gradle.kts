// Moviesapp-server — Spring Boot 3.3 + Spring Data JPA + H2/Postgres.
//
// Extracted from the multi-module `Movies-App` repo so it opens directly
// in IntelliJ as its own project. See `docs/SERVER.md` and
// `docs/AWS_DEPLOYMENT_PLAN.md` for the deployment story.
//
// Run locally:    ./gradlew bootRun
// Test:           ./gradlew test
// Build fat jar:  ./gradlew bootJar   (lands in build/libs/*.jar)

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Web + JPA + validation + actuator (for /actuator/health used by ECS/App Runner)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    // Spring Security — gives us BCryptPasswordEncoder, AuthenticationManager,
    // and the request-security filter chain that we extend with our Bearer JWT filter.
    implementation(libs.spring.boot.starter.security)

    // Kotlin reflection — required by Spring Data JPA to introspect
    // Kotlin entity classes (looks for primary constructors, parameter
    // names, default-value metadata). Without this you'll see
    // `ClassNotFoundException: kotlin.reflect.full.KClasses` at startup.
    implementation(libs.kotlin.reflect)

    // Jackson Kotlin module — Spring Boot auto-registers it when present
    // and gives Jackson the KotlinModule so it can build data classes by
    // primary-constructor parameter name + type. Without it you see
    // `MismatchedInputException: no delegate- or property-based Creator`.
    implementation(libs.jackson.module.kotlin)

    // kotlinx.serialization — required for @Serializable on the wire DTOs.
    // (Same library the Android :app uses, so wire shapes stay compatible.)
    implementation(libs.kotlinx.serialization.json)

    // H2 — embedded for dev; Postgres in prod via environment variables.
    runtimeOnly(libs.h2.database)
    runtimeOnly(libs.postgresql)

    // Flyway owns schema migrations before Hibernate validates entities.
    implementation(libs.flyway.core)
    runtimeOnly("org.flywaydb:flyway-database-postgresql:10.20.0")

    // JJWT — RSA-signed bearer tokens (header + impl + jackson adapter).
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    // Structured JSON logging for CloudWatch-friendly production logs.
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.0")

    // Test
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlin.test)
}

// Spring Boot and the bundled JUnit Platform default to binary-only test
// reporting. Force JUnit XML so CI / dashboards / IDEs surface test counts
// in standard JUnit shape.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }
}

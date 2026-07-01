# Moviesapp Server

Standalone Spring Boot 3.3 backend for the [Movies-App Android client](https://github.com/tcohen/Movies-App).
Extracted from the multi-module `:server` subproject into its own repo so it
opens directly in IntelliJ without the Android toolchain noise.

> **Status:** experimental. Production hard­ening story in [`docs/AWS_DEPLOYMENT_PLAN.md`](docs/AWS_DEPLOYMENT_PLAN.md).

---

## Quick start

```bash
# one-time
git clone <repo-url>
cd moviesapp-server
./gradlew bootRun         # runs on :8080

# in another shell:
curl http://localhost:8080/health
# → {"status":"ok"}

# authed routes need X-Api-Key — set API_KEY via env, then:
curl -H "X-Api-Key: change-me" http://localhost:8080/users/me/favorites
# → []
```

## Open in IntelliJ

`File → Open…` → pick this directory. The Gradle integration is built-in; the
Run Dashboard shows `ServerApplicationKt` ready to launch.

A few tips:

- **`ServerApplicationKt`-prefixed run config**: edit → set
  `API_KEY=dev-key-123` in **Environment variables** so the filter
  authenticates your local requests.
- **`./gradlew test`** in the terminal mirrors.
- The H2 console requires `spring-boot-devtools` (not included here); use
  `psql` or `H2 Console` IntelliJ plugin to peek at the dev DB.

## HTTP surface

| Method | Path                                  | Auth | Returns                                                   |
| ------ | ------------------------------------- | ---- | --------------------------------------------------------- |
| GET    | `/health`                             | No   | `200 { "status": "ok" }`                               |
| GET    | `/actuator/health`                    | No   | `200 { "status": "UP" }`                               |
| GET    | `/users/{userId}/favorites`           | Yes  | `200 List<FavoriteDto>` (savedAt DESC)                    |
| GET    | `/users/{userId}/favorites/{movieId}` | Yes  | `200 { isFavorite }` / `404`                              |
| POST   | `/users/{userId}/favorites/{movieId}` | Yes  | `201 { created: true }` if new / `200` if already there   |
| DELETE | `/users/{userId}/favorites/{movieId}` | Yes  | `204` / `404`                                             |

Auth = `X-Api-Key: <API_KEY>` header. The `ApiKeyAuthFilter` short-circuits
to `401` for any path other than `/health` and `/actuator/health`.

### Configuration

| Env var                         | Default                             | Purpose                                      |
| ------------------------------- | ----------------------------------- | -------------------------------------------- |
| `API_KEY`                       | `""` (rejects every authed request) | Expected `X-Api-Key` header value            |
| `SERVER_PORT`                   | `8080`                              | HTTP port (Spring Boot built-in)             |
| `SPRING_PROFILES_ACTIVE`        | unset (uses H2 default)             | Set `prod` for Postgres + production logging |
| `SPRING_DATASOURCE_URL`         | H2 in-memory                        | Set to `jdbc:postgresql://…` in production   |
| `SPRING_DATASOURCE_USERNAME`    | `sa`                                | DB username                                  |
| `SPRING_DATASOURCE_PASSWORD`    | `""`                                | DB password                                  |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `validate`                          | Hibernate validates Flyway-managed schema    |

## Project structure

```
src/main/kotlin/com/tcohen/moviesapp/server/
├── ServerApplication.kt   # @SpringBootApplication entry point
├── favorites/
│   ├── Favorite.kt        # @Entity — JPA, composite key, idempotency-friendly
│   ├── FavoriteDto.kt     # wire DTOs (@Serializable)
│   ├── FavoritesRepository.kt  # JpaRepository — derived query methods
│   ├── FavoritesService.kt    # @Service — business logic
│   └── FavoritesController.kt # @RestController
├── health/HealthController.kt
└── security/ApiKeyAuthFilter.kt
src/main/resources/
├── application.yml
├── logback-spring.xml
└── db/migration/V1__create_favorites.sql
src/test/kotlin/.../FavoritesControllerTest.kt   # MockMvc, real Spring, H2
```

## Tests

`./gradlew test` brings up the full Spring Boot context against H2 in-memory
with Flyway migrations, then asserts with MockMvc. **No mocks, no stubs** —
same controller/service/repository path that runs in production.

Coverage:

| Test                                        | Expectation                                  |
| ------------------------------------------- | -------------------------------------------- |
| POST creates a favorite                     | `201 Created`, body `{"created":true}`     |
| POST twice is idempotent                    | second → `200 OK`, body `{"created":false}` |
| GET list ordering by `savedAt` DESC         | List ordered by most recent first            |
| GET single favorite (`isFavorite` toggle)   | `200` / `404`                                |
| DELETE present then missing                 | `204` then `404`                             |
| Missing API key                             | `401 Unauthorized`                           |
| `/health` is open and returns `ok`          | `200 { "status": "ok" }`                  |
| `/actuator/health` is open for cloud checks | `200 { "status": "UP" }`                  |

## Production checklist

See [`docs/AWS_DEPLOYMENT_PLAN.md`](docs/AWS_DEPLOYMENT_PLAN.md) for the
full multi-phase plan. The short version:

1. **Phase 1** — Postgres + Flyway (schema migrations via `V1__*.sql`).
2. **Phase 2** — Production-grade Dockerfile (non-root, JVM heap cap, healthcheck).
3. **Phase 3** — Deploy to AWS (App Runner or self-managed EC2 + RDS).
4. **Phase 4** — GitHub Actions + OIDC for `git push = deploy`.
5. **Phase 5** — CloudWatch observability.

## Decisions worth noting

- **Spring Data JPA, not raw JDBC.** Derived query methods (e.g.
  `deleteByUserIdAndMovieId(...)`) cut a few dozen lines per resource and
  make the data layer legible to reviewers who don't speak SQL.
- **API-key auth, not JWT.** Single shared secret for v1. JWT adds
  rotation, scopes, and `exp` validation — worth the complexity only when
  the user count grows past "me and the Android app."
- **H2 with `MODE=PostgreSQL` for dev.** Same DDL works against Postgres
  in prod — only the JDBC URL changes.
- **Open `Application.kt` once and stop editing the entry-point file.**
  Future feature folders register themselves via component scan.

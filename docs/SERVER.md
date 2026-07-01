# `:server` — Spring Boot companion service (experimental)

A small **Spring Boot 3.3 + Spring Data JPA** service that mirrors the
favorites resource owned by `MovieRepository` on the Android side. Goal is
the same as the rest of `:server`: an isolated playground for backend work
that lives in this repo so it shares Gradle conventions and deployment
plumbing with the Android app.

> **Status:** experimental. Out of the box it runs on H2 in-memory; backing
> it with Postgres is a one-line JDBC URL change (see `application.yml`).

## Quick start

```bash
# Run locally on :8080
./gradlew :server:bootRun

# Run the test suite (uses H2 in-memory — no external services)
./gradlew :server:test

# Build a runnable Spring Boot fat jar for any Docker host
./gradlew :server:bootJar
# → server/build/libs/server-0.0.1-SNAPSHOT.jar
```

Set the API key before any authed request:

```bash
export API_KEY="$(openssl rand -hex 32)"
# Then in your client:
curl -H "X-Api-Key: $API_KEY" http://localhost:8080/users/me/favorites
```

Without `API_KEY`, every authenticated route returns `401 Unauthorized`.
The `/health` endpoint stays open — Fly.io / Render / Kubernetes can use it
as a liveness probe.

## Architecture

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Spring Boot context (ServerApplication.kt)                                │
│                                                                            │
│  ┌────────────────────┐    ┌────────────────────┐    ┌─────────────────┐    │
│  │ Servlet layer      │    │ @RestController    │    │ Exception       │    │
│  │  ApiKeyAuthFilter  │───►│  /favorites/...    │───►│ handlers        │    │
│  │                    │    │  /health           │    │ (built-in)      │    │
│  └────────────────────┘    └─────────┬──────────┘    └─────────────────┘    │
│                                       │                                    │
│                                       ▼                                    │
│                            ┌────────────────────┐                          │
│                            │ @Service           │                          │
│                            │  FavoritesService  │                          │
│                            └─────────┬──────────┘                          │
│                                      │                                     │
│                                      ▼                                     │
│                            ┌────────────────────┐                          │
│                            │ JpaRepository      │   @Entity Favorite       │
│                            │ (Spring Data JPA)  │◄── @IdClass(FavoriteId) │
│                            └─────────┬──────────┘                          │
│                                      │                                     │
│                                      ▼                                     │
│                              ┌────────────────┐                            │
│                              │ H2 (dev/test)  │  MODE=PostgreSQL syntax    │
│                              │ Postgres (prod)│  ← switch JDBC URL        │
│                              └────────────────┘                            │
└────────────────────────────────────────────────────────────────────────────┘
```

The controller calls the service; the service calls the repository. JPA
extends the repository interface — derived query methods like
`findByUserIdOrderBySavedAtDesc(...)` are parsed into JPQL at runtime.
Nothing in this flow references SQL strings directly.

## HTTP surface

| Method | Path                                          | Auth | Returns                                          |
|--------|-----------------------------------------------|------|--------------------------------------------------|
| GET    | `/health`                                     | No   | `200 { "status": "ok" }`                         |
| GET    | `/users/{userId}/favorites`                   | Yes  | `200 List<FavoriteDto>` (savedAt DESC)            |
| GET    | `/users/{userId}/favorites/{movieId}`         | Yes  | `200 { isFavorite: true }` / `404`               |
| POST   | `/users/{userId}/favorites/{movieId}`         | Yes  | `201 { created: true }` if new / `200` if existed |
| DELETE | `/users/{userId}/favorites/{movieId}`         | Yes  | `204` / `404`                                    |

Auth = `X-Api-Key: <API_KEY>` header. The `ApiKeyAuthFilter` short-circuits
to `401` for any path other than `/health` if the header is missing or wrong.

### Idempotency
- `POST` is idempotent — re-favoriting the same movie returns 200 with
  `created: false`. Underlying unique key on `(user_id, movie_id)` makes
  this a database-level no-op; the race window between `existsByXxx` and
  `save(xxx)` is handled by catching `DataIntegrityViolationException`.
- `DELETE` of a non-favorite returns `404`.

## Wire DTOs

```json
// GET /users/me/favorites
[
  { "movieId": 550, "savedAt": 1735600000000 },
  { "movieId": 27205, "savedAt": 1735599000000 }
]

// POST first time
{ "created": true }

// POST second time
{ "created": false }

// DELETE — empty body, 204

// any error
{ "error": "Unauthorized" }
```

DTOs in `server/src/main/kotlin/.../favorites/FavoriteDto.kt` use
`@Serializable` (kotlinx.serialization) — wire shapes are fully
compatible with what `:app` already speaks.

## Configuration (env vars / `application.yml`)

| Variable             | Default                                          | Purpose                                       |
|----------------------|--------------------------------------------------|-----------------------------------------------|
| `API_KEY`            | `""` (rejects every authed request as 401)       | Expected `X-Api-Key` header value              |
| `SERVER_PORT`        | `8080`                                           | HTTP port (Spring Boot built-in)               |
| `SPRING_DATASOURCE_URL` | `jdbc:h2:mem:movies_app;...`                    | Swap to `jdbc:postgresql://…` for production   |
| `SPRING_DATASOURCE_USERNAME` | `sa`                                           | DB username                                    |
| `SPRING_DATASOURCE_PASSWORD` | `""`                                            | DB password                                    |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `create-drop`                            | Switch to `validate`/`none` in production      |

## Deploying

### Fly.io (recommended for an experiment)

```bash
# One-time, on your machine
brew install flyctl
fly auth signup

cd server
fly launch --no-deploy --name moviesapp-server
# → answers: region? "fra"; postgres? "no" (H2 mem for now);
#   internal port? 8080; health check path? "/health"

# Set the API key
fly secrets set API_KEY="$(openssl rand -hex 32)"

# Deploy
fly deploy

# Smoke test
curl https://moviesapp-server.fly.dev/health
```

`fly launch` auto-detects the Spring Boot Dockerfile. To use Spring Boot's
optimized layered image instead, run `./gradlew :server:bootBuildImage`
locally and push the resulting image manually.

### Render

- New **Web Service**, point at the repo root.
- Build command: `./gradlew :server:bootJar`
- Start command: `java -jar server/build/libs/server-0.0.1-SNAPSHOT.jar`
- Env: `API_KEY`, `PORT=10000` (Render's default)
- Health check path: `/health`

### Any Docker host

```bash
docker build -f server/Dockerfile -t moviesapp-server .
docker run -e API_KEY="$(openssl rand -hex 32)" -p 8080:8080 moviesapp-server
```

## Tests

`./gradlew :server:test` runs `@SpringBootTest` + MockMvc against the real
Spring context with H2 — same Hibernate, same DDL, same controller code
as production.

Coverage (`server/src/test/.../FavoritesControllerTest.kt`):

| Test                                            | Expectation                              |
|-------------------------------------------------|-----------------------------------------|
| POST creates a favorite                         | `201 Created`, body `{"created":true}`  |
| POST twice is idempotent                        | second → `200 OK`, body `{"created":false}` |
| GET returns favorites in `savedAt DESC` order   | List ordered by most recent first        |
| GET single favorite                             | `200 { isFavorite:true }` / `404` if missing |
| DELETE returns 204 when present, 404 otherwise  | both branches                           |
| Requests without API key                        | `401 Unauthorized`                      |
| `/health` is open and returns `ok`              | `200 { "status": "ok" }`                |

## Decisions worth noting

- **H2 with `MODE=PostgreSQL`.** Dialect is close to Postgres so the DDL
  Hibernate generates works against both — swap the JDBC URL and you're on
  Postgres. No H2-specific SQL has crept into the code.
- **JPA, not JdbcTemplate.** JPA's derived query methods let us write
  `findByUserIdOrderBySavedAtDesc(...)` instead of a handwriting JPQL string.
  For an experiment, the readability win is the point.
- **`create-drop` only in dev.** Production tweaks `spring.jpa.hibernate.ddl-auto=validate`
  so Hibernate refuses to start on a stale schema instead of silently
  altering it.
- **No Spring Security.** A single servlet filter handles `X-Api-Key`
  — one file, no `SecurityFilterChain`. Drop in `spring-boot-starter-security`
  the moment JWT lands; controllers don't change.
- **Kotlin with `kotlin-spring` plugin.** The plugin opens `@Component`
  / `@Configuration` classes for CGLIB proxying so `@Transactional`
  works on regular `class` (not `open class`) declarations — we still
  mark them `open` explicitly because future-you will swap repositories.

## IntelliJ workflow

This is the natural home for Spring Boot. Tips for parity between Gradle
and IDE:

1. Open the project root in IntelliJ Ultimate (Community works but loses
   the Run Dashboard and Spring Boot Actuator integration).
2. The `SPRING_BOOT` run config pops up automatically — Run → `ServerApplication.kt`.
3. Install the **Spring Boot Assistant** plugin to get auto-complete on
   `application.yml` keys and a visual binding panel for environment
   variables.
4. The `Run` tool window has a **Services** tab with the live container
   and database panel — handy for poking at H2 in dev.
5. `Ctrl+Shift+A` → "Spring" surfaces model diagrams for `@RestController`s
   and bean dependency graphs — useful for onboarding a reviewer who
   hasn't read the codebase yet.

## Future work (when this stops being an experiment)

- [ ] JWT authentication via `spring-boot-starter-security` + `Bearer`
- [ ] Postgres backing service on Fly.io with Fly-managed migrations
- [ ] Spring Boot Actuator (`/actuator/health`, `/actuator/metrics`)
- [ ] Wire `PlotExplainer` AI usage into a `usage` table on this service
- [ ] Switch `:app`'s favorites repository to call this server instead of TMDB
- [ ] Wire `spring-kafka` for "favorite added" events → downstream consumers

# Moviesapp Server

Standalone Spring Boot 3.3 backend for the [Movies-App Android client](https://github.com/tcohen/Movies-App).
Extracted from the multi-module `:server` subproject into its own repo so it
opens directly in IntelliJ without the Android toolchain noise.

> 🟢 **Live:** <https://moviesapp-server-production.up.railway.app/actuator/health> — `{"status":"UP"}`
>
> **Smoke-tested end-to-end on 2026-07-01**: `/actuator/health` ✅,
> authed `GET /users/me/favorites` ✅, `POST /users/me/favorites/550` → `201`,
> follow-up `GET` returns the persisted favorite ✅.

| Title | Link |
| --- | --- |
| Local quick start | [below](#quick-start) |
| HTTP surface | [below](#http-surface) |
| Deploy to **Railway** (live) | [DEPLOYMENT.md](DEPLOYMENT.md) |
| Full multi-host comparison | [`docs/AWS_DEPLOYMENT_PLAN.md`](docs/AWS_DEPLOYMENT_PLAN.md) |

> **Status:** production-deployed via Railway. The AWS playbook in
> [`docs/AWS_DEPLOYMENT_PLAN.md`](docs/AWS_DEPLOYMENT_PLAN.md) is kept as a
> resume-grade alternative path (Phase 1 + Phase 2 already complete in this repo).

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

## Deploy to Railway — quick walk-through

Detailed step-by-step lives in [`DEPLOYMENT.md`](DEPLOYMENT.md).

```bash
# 1. Install Railway CLI (Windows)
winget install --id Railway.Railway

# 2. Auth (browser login, like gh)
railway login

# 3. Create a project, link to this folder
railway init

# 4. Add a Postgres database plugin
railway add                # interactive: pick "PostgreSQL"

# 5. Make sure prod env vars are set (Railway injects DATABASE_URL automatically)
railway variables --set "SPRING_PROFILES_ACTIVE=prod" \
                  --set "API_KEY=$(openssl rand -hex 32)"

# 6. Deploy — Railway uses the Dockerfile in this repo
railway up

# 7. Once deployed, generate a public domain
railway domain
```

After step 7 you get a URL like `https://moviesapp-server-production.up.railway.app`.

Sanity check:

```bash
curl https://<your-domain>/actuator/health
# → {"status":"UP"}

curl -H "X-Api-Key: $API_KEY" https://<your-domain>/users/me/favorites
# → []
```

---

## HTTP surface

### Auth (open)

| Method | Path                | Auth | Returns                              | Inputs |
| ------ | ------------------- | ---- | ------------------------------------ | ------ |
| POST   | `/auth/register`    | No   | `200 { accessToken, tokenType }` | `X-User-Id`, `X-Password` headers |
| POST   | `/auth/token`       | No   | `200 { accessToken, tokenType }` | `X-User-Id`, `X-Password` headers |
| GET    | `/auth/jwks.json`   | No   | `200 { keys: [jwk] }`             | — |
| GET    | `/auth/whoami`      | Yes  | `200 { userId }`                  | — |
| GET    | `/health`           | No   | `200 { "status": "ok" }`         | — |
| GET    | `/actuator/health`  | No   | `200 { "status": "UP" }`         | — |

### Favorites

| Method | Path                                  | Auth | Returns                                                   |
| ------ | ------------------------------------- | ---- | --------------------------------------------------------- |
| GET    | `/users/{userId}/favorites`           | Yes  | `200 List<FavoriteDto>` (savedAt DESC)                    |
| GET    | `/users/{userId}/favorites/{movieId}` | Yes  | `200 { isFavorite }` / `404`                              |
| POST   | `/users/{userId}/favorites/{movieId}` | Yes  | `201 { created: true }` if new / `200` if already there   |
| DELETE | `/users/{userId}/favorites/{movieId}` | Yes  | `204` / `404`                                             |

Auth = `Authorization: Bearer <jwt>` header on `/auth/whoami` and on every
favorites endpoint. Tokens are RS256-signed JWTs, 24 h TTL by default. The
`JwtAuthFilter` short-circuits to `401` for any non-open path with a missing
or invalid token.

### Configuration

| Env var                         | Default                                   | Purpose                                       |
| ------------------------------- | ----------------------------------------- | --------------------------------------------- |
| `PORT`                          | `8080`                                    | HTTP port (Railway/Render/Heroku inject this) |
| `SERVER_PORT`                   | `8080`                                    | HTTP port (Spring Boot built-in)              |
| `SPRING_PROFILES_ACTIVE`        | unset (uses H2 default)                   | Set `prod` for Postgres + production logging  |
| `SPRING_DATASOURCE_URL`         | H2 in-memory                              | Set to `jdbc:postgresql://…` in production    |
| `SPRING_DATASOURCE_USERNAME`    | `sa`                                      | DB username                                   |
| `SPRING_DATASOURCE_PASSWORD`    | `""`                                      | DB password                                   |
| `JWT_TTL_SECONDS`               | `86400` (24 h)                            | Bearer token lifetime                         |
| `JWT_PRIVATE_KEY`               | unset (auto RSA per JVM)                  | Base64 PKCS#8 — sticky across deploys         |
| `JWT_PUBLIC_KEY`                | unset (auto RSA per JVM)                  | Base64 X.509 — published at `/auth/jwks.json` |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `validate`                                | Hibernate validates Flyway-managed schema     |

> Railway's Postgres plugin injects `DATABASE_URL` like
> `postgresql://user:pwd@host:port/railway`. Spring Boot **does not** read
> `DATABASE_URL` by default, so we map it via a one-line `application.yml`
> snippet (`${DATABASE_URL}` → `SPRING_DATASOURCE_URL`) — see
> [`DEPLOYMENT.md`](DEPLOYMENT.md) for the exact addition.

## Project structure

```
src/main/kotlin/com/tcohen/moviesapp/server/
├── ServerApplication.kt       # @SpringBootApplication entry point
├── favorites/
│   ├── Favorite.kt            # @Entity — JPA, composite key, idempotency-friendly
│   ├── FavoriteDto.kt         # wire DTOs (@Serializable)
│   ├── FavoritesRepository.kt # JpaRepository — derived query methods
│   ├── FavoritesService.kt    # @Service — business logic
│   └── FavoritesController.kt # @RestController
├── auth/
│   ├── User.kt                # @Entity — registered end users
│   ├── UsersRepository.kt     # JpaRepository
│   ├── AuthService.kt         # register / login + BCrypt + JWT minting
│   ├── AuthController.kt      # /auth/register, /auth/token, /auth/jwks.json
│   └── JwtService.kt          # RS256 sign + verify, key load
├── health/HealthController.kt
└── security/
    ├── JwtAuthFilter.kt       # Bearer token validation
    └── SecurityConfig.kt      # Spring Security chain (stateless, no Basic)
src/main/resources/
├── application.yml
├── logback-spring.xml
└── db/migration/
    ├── V1__create_favorites.sql
    └── V2__create_users.sql
src/test/kotlin/.../ — FavoritesControllerTest, AuthControllerTest

# Deployment
Dockerfile                # multi-stage JRE image, non-root
railway.json              # tells Railway to build via the Dockerfile
```

## Tests

`./gradlew test` brings up the full Spring Boot context against H2 in-memory
with Flyway migrations, then asserts with MockMvc. **No mocks, no stubs** —
same controller/service/repository path that runs in production.

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

Current state (this repo) → see [DEPLOYMENT.md](DEPLOYMENT.md) for Railway
and [`docs/AWS_DEPLOYMENT_PLAN.md`](docs/AWS_DEPLOYMENT_PLAN.md) for AWS.

1. **Phase 1** ✅ Postgres + Flyway (schema migrations via `V1__*.sql`).
2. **Phase 2** ✅ Production-grade Dockerfile (non-root, JVM, healthcheck).
3. **Phase 3** ✅ Railway deploy — **live at <https://moviesapp-server-production.up.railway.app>**.
4. **Phase 4** ⏳ GitHub Actions CI (`.github/workflows/test.yml`).
5. **Phase 5** ⏳ Structured logging + request metrics.

## Resume bullet (suggested)

```
Movies-app backend (Spring Boot 3.3, Kotlin, PostgreSQL, Docker) — movie-favorites
API live at https://moviesapp-server-production.up.railway.app; Flyway-managed
schema migrations, multi-stage Docker image running as non-root, RS256 JWT
authentication with /auth/{register,token,jwks.json}, and continuous deployment
from `tomercohen1377-ctrl/moviesapp-server`. Endpoints: /actuator/health (open),
/users/{userId}/favorites… (Authorization: Bearer <jwt>).
```

## Decisions worth noting

- **Spring Data JPA, not raw JDBC.** Derived query methods (e.g.
  `deleteByUserIdAndMovieId(...)`) cut a few dozen lines per resource and
  make the data layer legible to reviewers who don't speak SQL.
- **Bearer token (RS256 JWT), not API-key.** Per-user identity, expiry, and
  future scopes. The verification filter is a single servlet filter; the
  issuing endpoint (`/auth/token`) uses the same JJWT library, so adding
  refresh tokens or rotating keys is a one-line change.
- **H2 with `MODE=PostgreSQL` for dev.** Same DDL works against Postgres
  in prod — only the JDBC URL changes.
- **`railway.json` over `railway.toml`.** The JSON schema is the
  documented v3 config; explicit `builder: DOCKERFILE` avoids surprise
  NIXPACKS detection picking the wrong language.
- **`PORT:8080` is the contract** between Spring Boot and any modern
  PaaS — Heroku, Render, Fly, Railway all inject `PORT`. We default to 8080
  so `./gradlew bootRun` keeps working locally.
- **Open `Application.kt` once and stop editing the entry-point file.**
  Future feature folders register themselves via component scan.

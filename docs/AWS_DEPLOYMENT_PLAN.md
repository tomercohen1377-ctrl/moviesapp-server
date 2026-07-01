# `:server` deployment playbook

A concrete, sequenced plan for turning the Spring Boot service in `:server`
into a fully working **remote backend for the Android `:app`**, packaged with
Docker, with real authentication, persistence, CI/CD, and observability.

> **Audience:** you, in a weekend or two of focused work. This doc assumes
> the contents of [`docs/SERVER.md`](SERVER.md) — the local `:server`
> module is working and its tests pass green.

## Which host should I pick?

| Path                | Complexity | Cost (idle)  | When to pick                                     |
| ------------------- | ---------- | ------------ | ------------------------------------------------ |
| **Railway** (live)  | Low        | ~$1-2/mo     | First deploy. One-click Postgres, free TLS, GitHub push-to-deploy. **Currently live for this repo — see [`DEPLOYMENT.md`](../DEPLOYMENT.md).** |
| **Fly.io**          | Low        | ~$0-2/mo     | Global edge regions, Dockerfiles, free Postgres.|
| **AWS (App Runner + RDS)** | Medium | ~$25-40/mo | Showcases full AWS story on a resume.       |
| **AWS (ECS + ALB + CloudFront)** | High | ~$35-60/mo | Production-grade scale, blue-green deploys. |

**Recommendation:** start with **Railway** ([`DEPLOYMENT.md`](../DEPLOYMENT.md))
to get a live URL on a weekend. **Read the AWS path below** when you want
the resume-grade breadth of services that AWS demonstrates.

Phase 1 and Phase 2 below are **already complete in this repo** —
Flyway migrations, the production-grade `Dockerfile`, API-key auth, and
container healthcheck are all in place regardless of host.

> 🟢 **Current live deploy: Railway.** Follow [`DEPLOYMENT.md`](../DEPLOYMENT.md)
> to push updates. The AWS plan below is preserved as the resume-grade
> alternative — pick this when you want to demonstrate full AWS breadth.
> Railway deploy config: [`railway.json`](../railway.json).

---

## Target architecture

```
┌─────────────────┐                                                ┌─────────────────┐
│  Android :app   │ ─── HTTPS ───▶  Route 53 / ACM cert (optional) ─▶│  App Runner     │
│  (your phone)   │                       │                          │  (Spring Boot)  │
└─────────────────┘                       │                          └────────┬────────┘
                                          │                                   │
                                          ▼                                   ▼ (VPC peering,
                                  ┌────────────────┐                  ┌──────────────┐
                                  │ CloudFront /   │                  │ RDS Postgres │
                                  │  ALB or direct │                  │ (private VPC)│
                                  └────────────────┘                  └──────────────┘
                                          
                                          ▲                                   ▲
                                          │ IAM + OIDC                        │ Secrets Manager
                                          │                                   │ (DB password, API_KEY)

                            ┌───────────────────────┐
                            │ CloudWatch            │ ◀─── logs (App Runner / ECS)
                            │  - Logs               │ ◀─── metrics (CPU, mem, reqs)
                            │  - Metrics            │
                            │  - Alarms             │
                            └───────────────────────┘
```

Three AWS stacks you're choosing between, **ranked by complexity**:

| Stack                                       | Complexity | Cost (after Free Tier) | When to pick                                   |
|---------------------------------------------|------------|------------------------|------------------------------------------------|
| **A. App Runner + RDS**                     | Low        | ~$20-40/mo             | First deploy. Single service handles HTTPS, autoscale, logs. |
| **B. ECS Fargate + RDS + ALB + CloudFront** | Medium     | ~$35-60/mo             | More control: SSL at edge, multiple services, blue-green deploys. |
| **C. Lightsail Containers + RDS**           | High       | ~$15-25/mo (predictable bill) | Uniform flat pricing, no granular AWS-services ops. |

**Recommendation: App Runner (A)** for a first deploy. You can graduate to ECS Fargate later if you outgrow it.

---

## Cost snapshot

Always-on, **after** AWS Free Tier expires (12 months for new accounts):

| Resource                          | Cost / month    | Notes                                       |
|-----------------------------------|-----------------|---------------------------------------------|
| App Runner, 1 vCPU / 2 GB         | ~$25            | 25 vCPU-hours + 100 GB-hours free/month     |
| RDS `db.t4g.micro` Postgres        | ~$15-20         | 750 hr/mo free for 12 months                |
| Secrets Manager (1 secret)         | ~$0.40          | Free for Parameter Store (lower-tier)        |
| CloudWatch Logs (~1 GB ingested)  | ~$0.50          | Free tier: 5 GB ingest + 5 GB archive       |
| **Total**                         | **~$40-50**     | With Free Tier active: **$0-5**              |

**Cost-savers once you stop iterating:**

- Stop RDS nightly via **AWS Instance Scheduler** (saves ~$10/mo).
- Tear down App Runner during long breaks (saves the full ~$25).
- Set a **billing alarm** on day 1 (`aws cloudwatch put-metric-alarm ... EstimatedCharges > 10 USD`).

`flyctl` is optional here — note that Fly.io does exist as a faster-iteration alternative, but the user has chosen to deploy on AWS for resumability and breadth of services covered.

---

## Phase 0 — Prerequisites

*Needed before any AWS work.*

- [ ] AWS account with admin access via **IAM Identity Center** (not root).
- [ ] Billing alarm set up: `aws cloudwatch put-metric-alarm … EstimatedCharges > 10 USD`.
- [ ] AWS CLI v2 installed and authenticated: `aws configure sso` (preferred) or `aws configure` with a scoped IAM user (never root keys).
- [ ] Docker Desktop installed (`docker --version`).
- [ ] JDK 17 toolchain matching what `:server/build.gradle.kts` already pins.
- [ ] GitHub repo with admin access (you'll add secrets + OIDC later).
- [ ] A domain name if you want HTTPS under your own URL — optional, App Runner gives you `https://<service>.awsapprunner.com` for free.

**Time: 30 min.**

---

## Phase 1 — Production-ready `:server` ✅ done in this repo

*Goal: replace H2 + `ddl-auto=create-drop` with durable schema that survives
container restarts. **Already implemented in this repo** — see
[`src/main/resources/db/migration/V1__create_favorites.sql`](../src/main/resources/db/migration/V1__create_favorites.sql).*

### Changes inside `server/`

1. **Add Postgres + Flyway:**
   ```kotlin
   // libs.versions.toml
   postgresql = "42.7.4"
   flyway = "10.20.0"
   ```
   ```kotlin
   // server/build.gradle.kts
   runtimeOnly(libs.postgresql)
   implementation(libs.flyway.core)
   ```

2. **Move schema out of JPA into a migration:**
   - `server/src/main/resources/db/migration/V1__create_favorites.sql`
     ```sql
     CREATE TABLE favorites (
       user_id  VARCHAR(64) NOT NULL,
       movie_id INTEGER     NOT NULL,
       saved_at BIGINT      NOT NULL,
       PRIMARY KEY (user_id, movie_id)
     );
     ```

3. **`application.yml` for dev vs prod:**
   ```yaml
   spring:
     jpa:
       hibernate:
         ddl-auto: validate          # never `update` — silent corruption risk
   spring.config.activate.on-profile: dev
   spring.datasource.url: jdbc:h2:mem:movies_app;DB_CLOSE_DELAY=-1
   ```
   The `prod` profile reads DB credentials from environment variables.

4. **`spring-boot-starter-actuator`** for `/actuator/health` & `/actuator/info`.

5. **Structured JSON logging** with `logstash-logback-encoder` so CloudWatch can index fields.

### Test plan

- Swap test datasource to **Testcontainers Postgres** (real Postgres in Docker) via `org.testcontainers:postgresql`.
- Keep one H2 smoke test for the test that doesn't need persistence semantics.
- Tests run with a real DB; one trade-off is ~30-60 s added boot time per test class.

### What you can still do locally

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew :server:bootRun
# → H2, ddl-auto=create-drop. Fast for dev iteration.
```

**Time: 2-4 hours. Lock down time:** schema is permanent once deployed, so don't rush.

---

## Phase 2 — Production-grade Dockerfile ✅ done in this repo

The current [`Dockerfile`](../Dockerfile) **already** uses `:jdk-jammy` for
build, `:jre-jammy` for runtime, runs as non-root user `app` (uid 1001), and
hits `/actuator/health` every 30s. Same shape as the spec below — kept here
for reference only.

The `server/Dockerfile` from earlier works for local dev. For prod:

### Refinements

```dockerfile
# Build stage — same as today
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew gradlew gradlew.bat ./
COPY settings.gradle.kts build.gradle.kts ./
COPY server server
RUN chmod +x gradlew && ./gradlew :server:bootJar --no-daemon

# Runtime stage — slim base, non-root, healthchecked
FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system app && useradd --system --gid app --uid 1001 app
WORKDIR /app
COPY --from=build /src/server/build/libs/server-*.jar /app/server.jar
USER app
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/server.jar"]
```

### Two image strategies

- **Manual Dockerfile (above)**: you control everything; ~270 MB total.
- **`./gradlew :server:bootBuildImage`**: Spring's built-in layered image builder (Bellsoft Liberica base, layered for cache efficiency). Add a single line to `:server/build.gradle.kts`:
  ```kotlin
  bootBuildImage {
    imageName.set("moviesapp-server")
    environment.set("BP_JVM_VERSION" to "17")
  }
  ```
  Resulting image is ~200 MB with layer separation between dependencies, code, and resources.

**Pick manual first** — it's more transparent and the CI step can stay Gradle-only.

**Time: 30 min.**

---

## Phase 3 — AWS infrastructure (App Runner path)

### 3.1 ECR — store the Docker image

```bash
aws ecr create-repository --repository-name moviesapp-server --region us-east-1
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <account>.dkr.ecr.us-east-1.amazonaws.com

./gradlew :server:bootJar
docker build -f server/Dockerfile -t moviesapp-server .
docker tag moviesapp-server:latest <account>.dkr.ecr.us-east-1.amazonaws.com/moviesapp-server:$(git rev-parse --short HEAD)
docker push <account>.dkr.ecr.us-east-1.amazonaws.com/moviesapp-server:$(git rev-parse --short HEAD)
```

### 3.2 RDS — the database

```bash
aws rds create-db-subnet-group \
  --db-subnet-group-name moviesapp-subnets \
  --subnet-ids <subnet-a> <subnet-b> \
  --db-subnet-group-description "Subnets for movies app"

aws rds create-db-instance \
  --db-instance-identifier moviesapp-db \
  --db-instance-class db.t4g.micro \
  --engine postgres \
  --engine-version 16.4 \
  --master-username app \
  --master-user-password "$(openssl rand -hex 24)" \
  --allocated-storage 20 \
  --db-subnet-group-name moviesapp-subnets \
  --vpc-security-group-ids <sg-that-allows-app-runner> \
  --no-publicly-accessible \
  --backup-retention-period 7
```

**Critical: the SG must allow inbound 5432 from App Runner's managed security group.** App Runner needs AWS PrivateLink peering or a shared SG for non-public RDS; the official AWS guide walks through this.

### 3.3 Secrets Manager — store DB password and API_KEY

```bash
aws secretsmanager create-secret --name moviesapp/db-password --secret-string "<password>"
aws secretsmanager create-secret --name moviesapp/api-key     --secret-string "$(openssl rand -hex 32)"
```

App Runner can reference secrets directly; their values land as environment variables at container start.

### 3.4 App Runner — the runtime

```bash
aws apprunner create-service \
  --service-name moviesapp-server \
  --source-configuration '{
    "ImageRepository": {
      "ImageIdentifier": "<account>.dkr.ecr.us-east-1.amazonaws.com/moviesapp-server:latest",
      "ImageConfiguration": {
        "Port": "8080",
        "RuntimeEnvironmentVariables": {
          "SPRING_PROFILES_ACTIVE": "prod",
          "SPRING_DATASOURCE_URL": "jdbc:postgresql://<rds-endpoint>:5432/movies_app",
          "SPRING_DATASOURCE_USERNAME": "app",
          "SPRING_DATASOURCE_PASSWORD": "<from-secrets>",
          "API_KEY": "<from-secrets>"
        }
      },
      "ImageRepositoryType": "ECR"
    }
  }' \
  --instance-configuration '{"Cpu":"1 vCPU","Memory":"2 GB"}' \
  --health-check-configuration '{"Path":"/actuator/health","Interval":10,"Timeout":5,"HealthyThreshold":1,"UnhealthyThreshold":3}'
```

`aws apprunner describe-service --service-name moviesapp-server --query "Service.ServiceUrl"` returns the public HTTPS URL.

**Time: 2-4 hours first time (mostly figuring out VPC + SG topology). 30 min with a script.**

---

## Phase 4 — CI/CD with GitHub Actions

*Goal: every push to `main` deploys. PRs run tests without deploying.*

### Workflows in `.github/workflows/`

#### `ci.yml` — fires on PR + push to main

```yaml
name: CI
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17" }
      - name: Test server
        run: ./gradlew :server:test
      - name: Build Android debug (smoke)
        run: ./gradlew :app:assembleDebug
```

#### `deploy.yml` — fires on push to main only

```yaml
name: Deploy
on:
  push: { branches: [main] }
jobs:
  deploy:
    runs-on: ubuntu-latest
    permissions:
      id-token: write            # needed for OIDC
      contents: read
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17" }
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::<account>:role/GitHubActionsMoviesApp
          aws-region: us-east-1
      - run: ./gradlew :server:bootJar
      - uses: aws-actions/amazon-ecr-login@v2
      - run: |
          IMAGE=<account>.dkr.ecr.us-east-1.amazonaws.com/moviesapp-server:${{ github.sha }}
          docker build -f server/Dockerfile -t $IMAGE .
          docker push $IMAGE
      - run: |
          aws apprunner update-service \
            --service-arn <arn> \
            --source-configuration '{"ImageRepository":{"ImageIdentifier":"<account>.dkr.ecr.us-east-1.amazonaws.com/moviesapp-server:${{ github.sha }}","ImageRepositoryType":"ECR","ImageConfiguration":{"Port":"8080"}}}'
```

### OIDC federation (no long-lived AWS keys)

1. In IAM, create a role with trust policy `token.actions.githubusercontent.com`.
2. Trust policy lists the GitHub repo + branch (`Movies-App:main`) so only your repo can assume it.
3. Permission policy: scoped to `ecr:GetAuthorizationToken`, `ecr:BatchCheckLayerAvailability`, `ecr:PutImage`, `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload`, `apprunner:UpdateService`.

**No `AWS_ACCESS_KEY_ID` or `AWS_SECRET_ACCESS_KEY` ever live in GitHub Secrets.** Credentials are short-lived tokens from the OIDC handshake.

**Time: 2-3 hours including IAM debugging.**

---

## Phase 5 — Observability

App Runner ships logs to **CloudWatch Logs** automatically (`/aws/apprunner/<service>`).

Inside Spring Boot, augment with Actuator + structured logging:

```kotlin
implementation(libs.spring.boot.starter.actuator)
runtimeOnly(libs.micrometer.registry.cloudwatch)
```

```yaml
management:
  endpoints.web.exposure.include: health,info,metrics
  endpoint.health.probes.enabled: true
  endpoint.health.show-details: when-authorized
  metrics.tags.application: moviesapp-server
```

### Alarms worth setting

| Metric                       | Threshold             | Why                                  |
|------------------------------|-----------------------|--------------------------------------|
| `UnhealthyHostCount > 0`     | 1 minute              | App Runner is dropping the container |
| CPU > 80% over 5 min         | sustained             | under-provisioned                    |
| RDS `FreeableMemory < 200MB` | sustained             | connection pool too big              |
| `EstimatedCharges > $20`     | monthly               | someone forgot to delete a stack     |

**Time: 1-2 hours, mostly alarms + dashboard JSON.**

---

## Phase 6 — Wire the Android `:app`

*This is the moment that turns "I have a server" into "the server is real."*

In `app/`:

1. Add `BuildConfig` field:
   ```kotlin
   buildConfigField("String", "FAVORITES_BASE_URL", "\"https://<service>.awsapprunner.com\"")
   buildConfigField("String", "FAVORITES_API_KEY", "<from CI env>")
   ```

2. New `FavoritesServerRepository` that calls:
   - `POST /users/me/favorites/{movieId}` — toggle on
   - `DELETE /users/me/favorites/{movieId}` — toggle off
   - `GET /users/me/favorites` — list (mirror to Room as cache)

3. OkHttp interceptor adds `X-Api-Key` header from `BuildConfig`.

4. `MovieRepositoryImpl.toggleFavorite()` → calls server first; falls back to TMDB if request fails (so the app still works during AWS outages).

5. **Smoke test:** toggle a favorite in the app → check `psql` against RDS → restart the app → favorite is still there (proves true persistence).

**Time: 1-2 hours. The Android tests start depending on a mock server** — use MockWebServer for tests so CI doesn't need to hit AWS.

---

## Phase 7 — Cost hygiene

- **Daily RDS stop** at 23:00 UTC, start at 08:00 UTC, via AWS Instance Scheduler. Saves ~$10/mo.
- **App Runner `min-instances = 0`** so it scales to zero during long breaks. Cold start ~5 s.
- **Logs retention**: set CloudWatch log group retention to 7 days (default is "never expire" — expensive).
- **Single AWS account**: don't create sandbox accounts unless you have to; the bill's easier to read.

---

## Phase-sequenced checklist

| #  | Phase                              | Time          | Output                                  |
|----|------------------------------------|---------------|-----------------------------------------|
| 0  | Prerequisites                      | 30 min        | AWS account, billing alarm, tools       |
| 1  | Production-ready `:server`         | 2-4 h         | Postgres, Flyway, Actuator              |
| 2  | Production Dockerfile              | 30 min        | Multi-stage, non-root, healthcheck      |
| 3  | AWS infra (App Runner path)        | 2-4 h         | Public HTTPS URL                        |
| 4  | CI/CD (GitHub Actions + OIDC)      | 2-3 h         | `git push` = deploy                     |
| 5  | Observability                      | 1-2 h         | Logs, metrics, alarms                   |
| 6  | Android `:app` integration         | 1-2 h         | App + server share data, end-to-end test |
| 7  | Cost hygiene                       | 30 min        | Scheduler, retention policies           |
|    | **Total**                          | **10-18 h**   | A real, production-shaped backend      |

---

## Decision log — what I'd pick and why

- **Postgres RDS, not DynamoDB** — your domain is relational, Hibernate writes better SQL than NoSQL code, and Postgres lets `VECTOR` types be added later for semantic recommendations.
- **App Runner, not ECS Fargate** — first deploy. Move to Fargate when you outgrow App Runner's auto-scale OR need separate management of multiple services. App Runner's main limit is one container per service, which is exactly what you want early.
- **OIDC, not access keys** — modern AWS best practice. Avoids credential rotation, leaked-secret cleanup, and the audit-trail gap that `iam:AccessKey` events hide.
- **Flyway, not Liquibase** — lower ceremony, SQL-first migrations. Liquibase wins when you need YAML/JSON change sets and rollbacks across environments; you don't.
- **GitHub-hosted runners, not self-hosted** — no infrastructure to maintain while iterating.
- **Secrets Manager over Parameter Store** for the DB password + API key — Secret rotation callbacks (still optional for v1) and cost is negligible at 1-2 secrets.
- **`.awsapprunner.com` URL, no custom domain** for v1 — Route 53 + ACM adds ~30 min and a re-deploy when you finally want a domain. Add Phase 7.5 later.
- **Spring Boot Actuator + Micrometer CloudWatch** — Spring's first-party CloudWatch integration. Prometheus is an option but adds a sidecar.

---

## What this teaches you to talk about in interviews

> Real signal from a self-built project is worth more than 10 certifications on the resume. After this plan, you can speak concretely to:
>
> - **Cloud-native deployment:** "I run my Spring Boot service in AWS App Runner, image pushed from GitHub Actions to ECR, OIDC federated — no long-lived AWS keys."
> - **Database migrations:** "Flyway `V1`, `V2` migrations; we never used `ddl-auto=update` because it silently corrupts against drift."
> - **Observability:** "I get an SMS when health-check passes stop. I structured logs as JSON so CloudWatch Insights can aggregate request counts and p99 latencies."
> - **Cost awareness:** "Right-sized `db.t4g.micro`, automatic night-stopping, bill alarm at $10/mo. I know what each service costs."
> - **Security posture:** "Secrets in Secrets Manager not env files. API key as `X-Api-Key` interceptor. Non-root container user."

---

## Common pitfalls to avoid

- **Reading `API_KEY` from `application.yml`** instead of an env var — defeats the whole point of Secrets Manager.
- **`spring.jpa.hibernate.ddl-auto: update`** in prod — silently changes schema without a migration record.
- **`create-drop` accidentally picked up by prod profile** — database wipes itself on every restart.
- **`-publicly-accessible` on RDS** — RDS should never be public; route through VPC.
- **Storing AWS access keys in GitHub Secrets** — use OIDC instead, every time.
- **Logging at INFO everywhere** — set PROD profile to `WARN` for libraries, `INFO` for your own code. CloudWatch ingestion is metered.
- **Forgetting the `HEALTHCHECK` on the Dockerfile** — App Runner's default probe path won't match your `ContextPath` or auth.
- **First deploy without a rollback plan** — keep a known-good image tagged as `previous` for one-click restore via `aws apprunner update-service`.

---

## When this is "done"

You're "done" when all of these are true:

1. `./gradlew :server:test` passes locally and on every PR.
2. `git push origin main` triggers a deploy in under 5 minutes.
3. The deployed URL's `/actuator/health` returns `{"status":"UP"}` 24/7.
4. A toggle-favorite round trip from your Android app writes to RDS and survives a container restart.
5. A billing alarm exists. CloudWatch shows log groups and at least one metric.
6. A README section links to `docs/AWS_DEPLOYMENT_PLAN.md` (the doc the recruiter skims).

After that, the natural extensions are JWT instead of API-key, Kafka or SQS for downstream events, multi-region failover, and Prometheus + Grafana. Each is a clean isolated next step — but they're **expansion**, not prerequisite.

---

## Where I am right now if you want me to start

I've already finished Phase 0. **Phase 1** (Postgres + Flyway) is the most impactful next step — it locks in real persistence before any AWS work, and it surfaces every schema change as a clean versioned file you can review.

Other natural "start here" picks:
- **Phase 4 first** if you want CI/CD scaffolding up before any infra cost.
- **Phase 6 first** if you want the Android app making real network calls early.

Tell me which phase to start and I'll execute end-to-end (with tests). Otherwise this doc stays your north star for the next two weekends.

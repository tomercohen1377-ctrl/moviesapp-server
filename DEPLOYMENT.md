# Deploying `moviesapp-server` to Railway

This is the **fastest, free-tier path** for getting the backend that lives
in this repo onto the public internet. No AWS account, no IAM, no DNS.

Time: **~30 minutes** the first time, ~2 minutes for subsequent deploys.
Cost: **~$1-2/month** for the app + Postgres at idle (free trial credits apply).

> 🟢 **Live deployment (this repo):** <https://moviesapp-server-production.up.railway.app>
>
> **Smoke-tested on 2026-07-01**:
> - `GET /actuator/health` → `HTTP 200 {"status":"UP"}`
> - `POST /users/me/favorites/550` → `HTTP 201 {"created":true}`
> - `GET /users/me/favorites` → `HTTP 200 [{\"movieId\":550,…}]`

---

## 1. Prerequisites (already satisfied for this repo)

- [x] Working `Dockerfile` at the repo root (multi-stage, JRE, non-root).
- [x] Postgres driver + Flyway migrations in `src/main/resources/db/migration/V1__*.sql`.
- [x] `SPRING_PROFILES_ACTIVE=prod` reads Postgres JDBC URL from env vars.
- [x] `railway.json` mapping Railway to our Dockerfile.
- [x] `./gradlew test` passes green locally.

If `docker --version` works and `./gradlew test` passes, you're ready.

## 2. Install Railway CLI

Windows (PowerShell):

```powershell
winget install --id Railway.Railway
```

macOS/Linux:

```bash
brew install railway
```

Verify:

```powershell
railway --version
```

## 3. Login

```powershell
railway login
```

Browser opens, grant access. Re-run `railway whoami` to confirm.

## 4. Initialize project

From this repo's root:

```powershell
railway init
```

Pick **Create new project** → name it **`moviesapp-server`** (or anything you
like). Railway shows an empty dashboard.

## 5. Add Postgres

In the **browser dashboard** (https://railway.com/dashboard):

1. Click **+ New** → **Database** → **PostgreSQL**.
2. Railway provisions it, then injects connection details into the **shared
   variable scope** as `DATABASE_URL`.

Alternatively from CLI:

```powershell
railway add --plugin postgresql
```

## 6. Wire `DATABASE_URL` to Spring's datasource

`application.yml` already has the **prod profile** wired with three vars:
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.
The Railway Postgres plugin only provides **`DATABASE_URL`** (single URL).
Two clean fixes:

### Option A — manually split `DATABASE_URL` into 3 vars (zero code changes)

In the Railway dashboard:

1. Open your **Postgres plugin** service.
2. Click **Variables** → **+ New Variable** to create:
   - `PGUSER`, `PGPASSWORD`, `PGHOST`, `PGPORT`, `PGDATABASE` — Railway
     exposes these automatically (they're the components of `DATABASE_URL`).
   - Many Railway Postgres plugins set these as **shared variables**
     automatically. If yours already does, no action needed.
3. Open your **app service** → **Variables** → **+ New Variable**:
   - `SPRING_DATASOURCE_URL` → `jdbc:postgresql://$PGHOST:$PGPORT/$PGDATABASE`
   - `SPRING_DATASOURCE_USERNAME` → `$PGUSER`
   - `SPRING_DATASOURCE_PASSWORD` → `$PGPASSWORD`

   Railway inline-references `$PROJECT_NAME.PGUSER` from sibling services;
   use the picker UI to set references if you'd rather not write them by hand.
4. Skip — the **prod profile** is already configured to read these.

### Option B — make Spring read `DATABASE_URL` directly (one-line config)

Edit `src/main/resources/application.yml` → find the **prod profile** block
(under the first `---`). Replace the three `SPRING_DATASOURCE_*` lines with:

```yaml
spring:
  config:
    activate:
      on-profile: prod
  datasource:
    url: jdbc:${DATABASE_URL}
    username: ${SPRING_DATASOURCE_USERNAME:postgres}
    password: ${SPRING_DATASOURCE_PASSWORD:}
    driver-class-name: org.postgresql.Driver
```

Then set just two vars on the app service:

| Service variable            | Value                                          |
| --------------------------- | ---------------------------------------------- |
| `SPRING_DATASOURCE_USERNAME`| `${{Postgres.PGUSER}}`                         |
| `SPRING_DATASOURCE_PASSWORD`| `${{Postgres.PGPASSWORD}}`                     |

`DATABASE_URL` is provided automatically by the Postgres plugin. Spring
just prepends `jdbc:`. **Username/password fall back to defaults** so the
app still starts if you're testing locally.

> Pick **Option A** if you want zero code changes. Pick **Option B** for the
> "one deployment file = one URL" experience. Option A is the most explicit
> and least likely to confuse a reviewer.

## 7. Register your Android-side user

The server uses bearer tokens now — there's no shared `API_KEY` to distribute
to the Android client. **After deploying, register a user once** with two
request headers (avoiding any JSON-body parsing ambiguity):

```powershell
curl -X POST https://<your-domain>/auth/register \
     -H "X-User-Id: you" \
     -H "X-Password: your-password-1234"
# → {"accessToken":"eyJ…","tokenType":"Bearer"}
```

Save the `accessToken` somewhere safe. The Android client stores it in
`EncryptedSharedPreferences` and re-uses it until it expires (default 24 h),
then calls `/auth/token` to get a fresh one.

**Optional production hardening**:

```powershell
# Pin the JWT signing keys so tokens survive deploys/restarts.
railway variables --set "JWT_TTL_SECONDS=3600"   # 1 h
railway variables --set "JWT_PRIVATE_KEY=<base64-PKCS8-RSA-private-key>"
railway variables --set "JWT_PUBLIC_KEY=<base64-X509-RSA-public-key>"
```

(Optional) set a Customer-facing domain-friendly HTTP base for the Android app:

```powershell
railway variables --set "API_BASE_URL=https://moviesapp-server.up.railway.app"
```

(Spring won't read this today — it's a future hint for the Android client.)

## 8. Deploy

```powershell
railway up
```

This:

1. Reads `railway.json` → builds via the Dockerfile.
2. Uploads the image.
3. Starts the service with env vars.
4. Streams logs into your terminal. Press Ctrl-C to detach; the deploy
   keeps running.

First deploy takes **~3-5 min** (Gradle download, jar build, image push).
Subsequent deploys ~1 min.

## 9. Get a public URL

```powershell
railway domain
```

Railway assigns a free `*.up.railway.app` subdomain and shows you the URL.
**Save this URL** — that's the address your Android client will hit.

## 10. Smoke test

Wait ~30 seconds after `railway up` returned `Successful`, then:

```powershell
& 'C:\Windows\System32\curl.exe' https://<your-railway-domain>/actuator/health
# → {"status":"UP"}

# Register a user, capture the JWT, hit a protected endpoint.
$resp = & 'C:\Windows\System32\curl.exe' -sS -X POST `
   https://<your-railway-domain>/auth/register `
   -H 'X-User-Id: smoke' `
   -H 'X-Password: smoke-test-1234'
$token = ($resp | ConvertFrom-Json).accessToken

& 'C:\Windows\System32\curl.exe' -sS -H "Authorization: Bearer $token" `
   https://<your-railway-domain>/users/smoke/favorites
# → []
```

If you see **401 "Invalid or expired token"** → you registered as a different
userId than the one you're calling `/users/.../favorites` with. JWTs are tied
to the user that requested them.

## 11. Continuous deploy from GitHub

In the Railway dashboard:

1. Click the service → **Settings** → **Source** → **Connect Repo** → pick
   `tomercohen1377-ctrl/moviesapp-server`.
2. Pick the **`main`** branch.
3. Click **Deploy**.

Now every `git push` to `main` triggers a redeploy automatically. The
builds will use the latest commit's `Dockerfile` and `railway.json`.

## 12. Cost & cleanup

Always-on monthly cost in Railway:

| Resource             | Estimate |
| -------------------- | -------- |
| Service (1 vCPU/512 MB) | ~$0.50 |
| Postgres (free tier) | $0 in free plan, ~$1 sustained |
| Logging              | < $0.20 |
| **Total**            | **~$1-2** |

When you stop iterating:

- **Pause service** in dashboard → cost drops to near-zero (storage only).
- **Delete project** → cost drops to zero.

## 13. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `/actuator/health` returns 401 | API gateway requires auth even on health | Check Railway **Healthcheck Path** matches `/actuator/health` (this `railway.json` already does) |
| **CrashLoopBackOff** logs `FATAL: password authentication failed` | `SPRING_DATASOURCE_PASSWORD` not set | Open Postgres plugin → **Variables** → copy `DATABASE_URL` and split |
| Logs show `Connection refused` after Spring Boot startup | Postgres plugin hasn't finished provisioning yet | Wait 30s and check `railway logs`. Spring Boot fails-fast by design. |
| Android client can't reach `https://<domain>` | App requests `http://...` instead | Railway auto-issues TLS; use `https://` everywhere. |
| Deploy succeeds but `/actuator/health` is `503` | Healthcheck returns DOWN | DB is unreachable — see **"password authentication"** above first. |

---

## What's not done in this doc

- **Custom domain** (CNAME `api.your.com` to `*.up.railway.app`). Free on
  Railway; takes 5 minutes. Add a follow-up `Phase 6` to the AWS playbook
  if you want a `*.tomercohen.com` look.
- **Backups** — Railway free tier snapshots Postgres once daily. For a
  resume demo that's plenty.
- **Monitoring** — Railway shows CPU/memory graphs and log tail in the
  dashboard. Full CloudWatch observability is overkill here; revisit only
  if you scale traffic.

When you're live, paste your `https://…up.railway.app` URL into the resume
bullet, e.g.:

```
Movies-app backend (Spring Boot, Kotlin, PostgreSQL, Docker) — movie-favorites
API deployed at https://moviesapp-server-production.up.railway.app with
Flyway migrations and Docker multi-stage builds; covers authentication,
persistence, and continuous deployment via GitHub.
```

Done. 🚂

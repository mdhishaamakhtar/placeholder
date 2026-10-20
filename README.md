<!-- markdownlint-disable MD033 -->

<p align="center">
  <img src="./.github/assets/placeholder-preview.png" alt="placeholder: short links for links, code, and text">
</p>

## placeholder

placeholder turns a paste into a short link. Paste a URL and the link redirects to it. Paste code and the link opens a clean page with it, language detected, colored like your editor, and one click away from tidy. Paste notes or logs and they're set in readable type. Nothing you paste is ever run or rendered.

It's written in Kotlin from the database to the browser, and built to scale out: every server is stateless, so you add capacity by adding servers.

## What It Does

- Shortens URLs, with instant `302` redirects served from an in-memory cache
- Shares code with language detection, syntax highlighting, and Prettify for JSON, SQL, HTML, and XML
- Shares text and Markdown as a quiet, readable page
- Accounts with email and password: your links on every device, edit them in place, or set them to expire
- Link previews that show the content: share a SQL snippet in Slack and the card shows the query, colored
- Dark and light themes, rendered on the server so there's never a flash of the wrong one

<p align="center">
  <img src="./.github/assets/snip-preview.png" alt="The link preview for a shared SQL snippet" width="720">
</p>

## Tech Stack

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Ktor](https://img.shields.io/badge/Ktor-3.6-087CFA?style=for-the-badge&logo=ktor&logoColor=white)](https://ktor.io/)
[![Kobweb](https://img.shields.io/badge/Kobweb-0.25-000000?style=for-the-badge)](https://kobweb.varabyte.com/)
[![Compose HTML](https://img.shields.io/badge/Compose_HTML-1.11-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://github.com/JetBrains/compose-multiplatform#compose-html)
[![Java](https://img.shields.io/badge/Java-25-007396?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Gradle](https://img.shields.io/badge/Gradle-9.8-02303A?style=for-the-badge&logo=gradle&logoColor=white)](https://gradle.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-336791?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-8-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io/)
[![Exposed](https://img.shields.io/badge/Exposed-1.6-000000?style=for-the-badge)](https://github.com/JetBrains/Exposed)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://docs.docker.com/compose/)

## How It Fits Together

```text
            browser                                     link-preview crawlers
               │                                                 │
               ▼                                                 ▼
        ┌─────────────┐   round-robin   ┌──────────────────────────────────────────┐
        │    nginx    │ ──────────────▶ │  Ktor app × N   (stateless, identical)    │
        └─────────────┘                 │  serves the Kobweb site, the JSON API,    │
                                        │  redirects, and the preview cards         │
                                        │  ┌────────────────────────────────────┐  │
                                        │  │ Caffeine: in-memory cache per app  │  │
                                        │  └────────────────────────────────────┘  │
                                        └───────────────┬───────────┬──────────────┘
                                                        │           │
                                     cache, rate limits,│           │ snips, users,
                                     cross-app evictions│           │ sessions
                                                        ▼           ▼
                                                  ┌─────────┐  ┌──────────┐
                                                  │  Redis  │  │ Postgres │
                                                  └─────────┘  └──────────┘
```

- **One language, one deployable.** The browser app is Kotlin compiled to JavaScript with Kobweb; the server is Ktor. They share one module of request and response types and validation rules, so the page never promises something the API rejects.
- **Three cache layers for reads.** Each app keeps hot snips in memory, then Redis, then Postgres. Links made before accounts existed can never change, so they're cached forever, by browsers and CDNs too. Links with an owner can be edited, so browsers revalidate them by version (a cheap `304`), and an edit tells every app instance to drop its copy through Redis pub/sub.
- **Redis is optional.** If it's slow or down, the app keeps serving from Postgres, and rate limits fail open rather than taking the site down.
- **Accounts are deliberately boring.** Passwords are hashed with Argon2id at OWASP settings. Sessions are random tokens, stored only as a SHA-256 hash, in an HttpOnly, SameSite=Lax cookie, and signing out revokes them on the server. Writes that carry the cookie must come from the site's own origin. Sign-ins are rate-limited per IP and per email, and a wrong email gets exactly the same answer as a wrong password.
- **Expired links answer `410 Gone`.** An hourly job, run by one instance at a time via a Redis lock, removes them along with expired sessions.

## Quick Start (Docker)

Build the server and the site, then start everything: three app servers behind nginx, Postgres, and Redis.

```bash
./gradlew :server:installDist :web:assembleSite
```

```bash
docker compose --profile stack up -d --build
```

Then open [http://localhost:8080](http://localhost:8080).

## Manual Dev Setup

The project targets Java 25. If your shell defaults to another JDK, pin `JAVA_HOME` for Gradle commands.

### 1. Start Postgres and Redis

```bash
docker compose up -d
```

### 2. Run the app

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :server:run
```

This builds the Kobweb site and serves it from Ktor at [http://localhost:8080](http://localhost:8080). After changing the frontend, rebuild it with `./gradlew :web:assembleSite` and reload.

## Configuration

Settings live in [`server/src/main/resources/application.yaml`](./server/src/main/resources/application.yaml). Each one can be overridden with an environment variable, and every default works on a laptop.

| Variable | Default | What it does |
|---|---|---|
| `PORT` | `8080` | Port the server listens on |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | The site's own address: used in short links, previews, and the cross-site check. Use `https://` in production so session cookies are marked Secure. |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/placeholder` | Postgres JDBC URL |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `placeholder` | Postgres credentials |
| `DATABASE_POOL_SIZE` | `16` | Connections per app instance |
| `REDIS_URL` | `redis://localhost:6379` | Leave empty to run without Redis |
| `WEB_ROOT` | empty | Folder with the built site (`web/build/site`). Empty runs the API alone. |
| `TRUST_PROXY_HEADERS` | `false` | Trust `X-Forwarded-*`. Only behind a proxy you control. |
| `CREATES_PER_MINUTE` | `30` | New links per client per minute |
| `AUTH_PER_MINUTE` | `10` | Sign-ups and sign-ins per client per minute |

## Tests

Server tests run against real Postgres and Redis through Testcontainers, so Docker must be running.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :shared:jvmTest :shared:jsTest :server:test
```

They cover redirects, storage and reads, validation and size limits, Prettify, rate limits, running without Redis, 404 and 410 pages (including over gzip), theme rendering, and accounts: sign-up and sign-in, session revocation, the cross-site write check, ownership and edit permissions, expiry, and an edit on one app instance reaching another instance's cache.

## Benchmarks

Measured with [oha](https://github.com/hatoo/oha) against the Docker stack above (3 app servers, nginx, Postgres, and Redis), with everything, the load generator included, on one Apple M1 Pro laptop. Treat these as a floor for that one machine, not a capacity claim.

20 seconds per scenario, 200 concurrent connections (50 for creates), every request answered correctly:

| Scenario | Requests/s | p50 | p95 | p99 | Success |
|---|---:|---:|---:|---:|---:|
| GET /{slug} (link, 302) | 27,035 | 6.2 ms | 16.6 ms | 26.2 ms | 100.00% |
| GET /api/snips/{slug} (owned link) | 19,209 | 8.3 ms | 25.9 ms | 42.1 ms | 100.00% |
| GET /api/snips/{missing} (404) | 17,017 | 9.9 ms | 26.2 ms | 42.4 ms | 100.00% |
| GET /{slug} (page, gzip) | 17,961 | 8.8 ms | 27.6 ms | 43.9 ms | 100.00% |
| POST /api/snips (c=50) | 5,108 | 8.9 ms | 17.5 ms | 25.0 ms | 100.00% |

Runs on a busy laptop vary by up to a third depending on what else is running; the numbers above were taken with build tools stopped.

Reproduce them (the create test needs the per-client limit raised):

```bash
CREATES_PER_MINUTE=1000000 docker compose --profile stack up -d --build
```

```bash
bench/run.sh
```

## Project Layout

```text
shared/   Kotlin Multiplatform: API types and rules used by both server and browser
server/   Ktor: HTTP, accounts, snips, caching, preview cards, migrations
web/      Kobweb: the site, compiled from Kotlin to JavaScript
docker/   Dockerfile and nginx config
bench/    Load test script
```

## Contributor

- Md Hishaam Akhtar

<p align="center">
  Built for the "here's the SQL" moment.
</p>

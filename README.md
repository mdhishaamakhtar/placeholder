<!-- markdownlint-disable MD033 -->

<p align="center">
  <img src="./.github/assets/placeholder-preview.png" alt="placeholder: short links for links, code, and text">
</p>

## placeholder

[![CI](https://github.com/mdhishaamakhtar/placeholder/actions/workflows/ci.yml/badge.svg)](https://github.com/mdhishaamakhtar/placeholder/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](./LICENSE)

placeholder turns a paste into a short link. Paste a URL and the link redirects to it. Paste code and the link opens a clean page with it, language detected, colored like your editor, and one click away from tidy. Paste notes or logs and they're set in readable type. Nothing you paste is ever run or rendered.

It's written in Kotlin from the database to the browser, and built to scale out: every server is stateless, so you add capacity by adding servers.

## What It Does

- Shortens URLs, with instant `302` redirects served from an in-memory cache
- Shares code with language detection, syntax highlighting, and Prettify for JSON, SQL, HTML, and XML
- Shares text and Markdown as a quiet, readable page
- Accounts with email and password: your links on every device, edit them in place, or set them to expire. Making links needs an account; opening them never does
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
- **The server writes each page's head.** Kobweb builds one `index.html`; Ktor parses it once with jsoup and, per request, adds that page's title, description, Open Graph and Twitter tags, and the visitor's theme. Crawlers see the right preview without running any script, and pasted content is escaped by construction.

## Design System

The look is monochrome and typographic: black or white, one grey, and color only where it carries meaning (syntax highlighting). Every value comes from tokens at the top of [`styles.css`](./web/src/jsMain/resources/public/styles.css):

- **Type:** Hanken Grotesk for interface and prose, Geist Mono for code and numbers, both self-hosted. Six roles about 1.125x apart (14px labels and actions, 15px code, 16px body, 18px reading text, 20px page introductions) plus two display sizes for the landing headline and a new short code. All in `rem`, so browser zoom and font settings are respected.
- **Space:** one 4px-based scale (4, 8, 12, 16, 24, 32, 48, 64). Tight steps group related things; wide steps separate groups.
- **Motion:** only where it confirms something or prevents a jarring change, short (120 to 260ms), on a strong ease-out curve, with lighter versions under `prefers-reduced-motion`. The one flourish is a new short code rising in letter by letter.
- **Controls:** no browser-native widgets where they can't match the theme; menus are a themed combobox that works fully from the keyboard. Text actions get 44px touch targets on touch screens.
- **Themes:** dark by default, light on request, chosen server-side from a cookie so the first paint is already right.

## Running It Yourself

### What you need

- **Java 25.** The build always runs on Java 25, found automatically, even if your terminal's default `java` is a different version.
- **Docker**, for Postgres and Redis, and for the tests.
- **[oha](https://github.com/hatoo/oha)**, only if you want to run the benchmarks (`brew install oha`).

You don't need Gradle, Node, or anything else installed: `./gradlew` downloads the right Gradle, and the build fetches its own Node to compile the site.

### Run it

```bash
docker compose up -d
```

```bash
./gradlew :server:run
```

The first command starts Postgres and Redis in the background. The second builds the site and the server and starts it at [http://localhost:8080](http://localhost:8080); create an account there to start making links. Stop the server with `Ctrl+C`.

After changing anything in `web/`, rebuild the site and reload the page (the server picks it up without restarting):

```bash
./gradlew :web:assembleSite
```

### Run the tests

```bash
./gradlew :shared:jvmTest :shared:jsTest :server:test
```

### Run the whole stack in Docker

Three app servers behind nginx, with Postgres and Redis, the way it would run in production. Build first, then start:

```bash
./gradlew :server:installDist :web:assembleSite
```

```bash
docker compose --profile stack up -d --build
```

It's at [http://localhost:8080](http://localhost:8080), so stop `./gradlew :server:run` first if it's running.

### Redraw the preview images

The two images in this README are drawn by the same code the server uses for link previews:

```bash
./gradlew :server:renderPreviews
```

### Stop everything

Stop the containers (your data is kept):

```bash
docker compose --profile stack down
```

Stop and also delete the database and cache data, for a clean slate:

```bash
docker compose --profile stack down -v
```

Stop Gradle's background build processes:

```bash
./gradlew --stop
```

**About those background processes:** the first `./gradlew` command starts a Gradle "daemon", a Java process that stays running after the build so the next build starts in seconds instead of warming up from scratch (Kotlin starts its own compiler daemon for the same reason). They shut themselves down after a few idle hours. Stop them by hand to free memory, or before benchmarking so they don't compete for CPU.

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

Server tests run against real Postgres and Redis through Testcontainers, so Docker must be running. They cover redirects, storage and reads, validation and size limits, Prettify, rate limits, running without Redis, 404 and 410 pages (including over gzip), theme rendering, meta tags that hostile pastes can't break out of, and accounts: sign-up and sign-in, session revocation, the cross-site write check, ownership and edit permissions, expiry, and an edit on one app instance reaching another instance's cache. The shared rules (link detection, slugs, sign-in redirects) are tested on both the JVM and in a headless browser.

CI runs all of it, plus a production build of the site, on every push and pull request.

## Benchmarks

Measured with [oha](https://github.com/hatoo/oha) against the Docker stack (3 app servers, nginx, Postgres, and Redis), with everything, the load generator included, on one Apple M1 Pro laptop. Treat these as a floor for that one machine, not a capacity claim.

20 seconds per scenario, 200 concurrent connections (50 for creates), every request answered correctly:

| Scenario | Requests/s | p50 | p95 | p99 | Success |
|---|---:|---:|---:|---:|---:|
| GET /{slug} (link, 302) | 24,092 | 6.7 ms | 19.7 ms | 33.7 ms | 100.00% |
| GET /api/snips/{slug} (owned link) | 16,965 | 8.4 ms | 32.9 ms | 55.6 ms | 100.00% |
| GET /api/snips/{missing} (404) | 15,149 | 10.2 ms | 32.7 ms | 53.3 ms | 100.00% |
| GET /{slug} (page, gzip) | 15,497 | 9.0 ms | 36.7 ms | 62.1 ms | 100.00% |
| POST /api/snips (c=50) | 4,081 | 10.1 ms | 26.4 ms | 42.0 ms | 100.00% |

Runs on a laptop vary by up to a third depending on what else is running (a browser, an editor, build daemons).

To reproduce them, stop the build daemons so they don't compete for CPU, start the stack with the per-client create limit raised (otherwise the create test measures the rate limiter), and run the script:

```bash
./gradlew :server:installDist :web:assembleSite && ./gradlew --stop
```

```bash
CREATES_PER_MINUTE=1000000 docker compose --profile stack up -d --build
```

```bash
bench/run.sh
```

Results are written to `bench/results/`. Afterwards, restart the stack with the normal limit:

```bash
docker compose --profile stack up -d
```

## Project Layout

```text
shared/              Kotlin Multiplatform, used by both server and browser
  Models, Rules        request and response types, limits, link and slug rules
  Paths, Languages     every page address and language name, defined once

server/              Ktor
  config/              typed view of application.yaml
  http/                routes, plugins, error handling, sessions, the page head (AppShell)
  account/             users, sessions, Argon2id password hashing
  snip/                snips: service, repository, read-through cache, slugs
  prettify/            JSON, SQL, HTML, and XML formatters
  og/                  link-preview cards drawn with Java2D
  infra/               database, Redis, cross-instance cache eviction, rate limits, cleanup
  resources/           application.yaml, Flyway migrations, fonts for preview cards

web/                 Kobweb, compiled from Kotlin to JavaScript
  pages/               one file per route: landing, sign-in, editor, your links, a link
  editor/              the editor's state holder and its parts
  components/          shared UI: frame, menus, rows, empty and error states
  resources/public/    styles.css (the design system), fonts, favicon, robots.txt

docker/              Dockerfile and nginx config for the multi-instance stack
bench/               load test script
```

## Contributor

- Md Hishaam Akhtar

<p align="center">
  Built for the "here's the SQL" moment.
</p>

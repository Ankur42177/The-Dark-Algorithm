# Running Pulse on your laptop — step by step

A copy-paste walkthrough from a fresh Mac to a working site, with the **expected output after every
step** so you always know whether it worked. Allow about 45 minutes the first time (mostly downloads).

**Designed and developed by Ankur Kumar.** © 2026 Ravenclaw_007 LLC. All rights reserved.

> Conventions: lines starting with `$` are commands (type them without the `$`). "Expect" shows what a
> healthy result looks like. Unless a step says otherwise, run commands from the `Blog_007/` folder.

---

## Step 0 — Check your machine

| Need | Why |
|---|---|
| macOS on Apple Silicon (Intel works, just slower) | the guide uses Homebrew paths for arm64 |
| 16 GB RAM or more | Docker (8 GB) + 2–4 JVMs + Node |
| ~15 GB free disk | Docker images, Maven/npm caches, optional LLM models |

---

## Step 1 — Install the tools (one time)

### 1.1 Homebrew
```bash
$ /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
$ brew --version
```
Expect: `Homebrew 4.x` or newer.

### 1.2 Java 25 (JDK)
Download **Eclipse Temurin 25** (macOS, aarch64, .pkg) from https://adoptium.net and install it.
```bash
$ java -version
```
Expect: `openjdk version "25…"` (any vendor's JDK 25 works).
If an older Java shows up, list installed JDKs with `/usr/libexec/java_home -V` and set
`export JAVA_HOME=$(/usr/libexec/java_home -v 25)` in `~/.zshrc`.

### 1.3 Docker Desktop
Install from https://www.docker.com/products/docker-desktop, start it, then **Settings → Resources →
Memory: 8 GB** → Apply & restart.
```bash
$ docker version --format '{{.Client.Version}} / {{.Server.Version}}'
```
Expect two version numbers. If the second part errors with "Cannot connect to the Docker daemon",
Docker Desktop isn't running yet (`open -a Docker`, wait ~30 s).

### 1.4 Node.js and Git
```bash
$ brew install node git
$ node -v && npm -v && git --version
```
Expect: Node `v20.9` or newer.

### 1.5 Optional tools
```bash
$ brew install k6 jq           # load testing, pretty JSON
```
Maven is **not** needed: the project ships `./mvnw` (Maven Wrapper).

---

## Step 2 — Get the code

```bash
$ cd ~/Desktop
$ git clone https://github.com/Ankur42177/The-Dark-Algorithm.git
$ cd The-Dark-Algorithm/Blog_007
$ ls
```
Expect: `README.md  docs  infra  libs  load-tests  mvnw  pom.xml  scripts  services  web`.

---

## Step 3 — Configure secrets (`.env`)

`.env` holds every password and key. It is git-ignored, so nothing here ever reaches GitHub.

### 3.1 Create it
```bash
$ cp .env.example .env
$ for v in POSTGRES_SUPERUSER_PASSWORD CONTENT_DB_PASSWORD INGESTION_DB_PASSWORD ASSISTANT_DB_PASSWORD RABBITMQ_PASSWORD REVALIDATE_SECRET; do
    sed -i '' "s|^$v=.*|$v=$(openssl rand -hex 16)|" .env; done
$ chmod 600 .env
$ grep -c change-me .env
```
Expect: `0` (no placeholder passwords left).

### 3.2 JWT signing keys
```bash
$ ./scripts/gen-jwt-keys.sh
```
Expect: `Saved PULSE_JWT_PRIVATE_KEY / PULSE_JWT_PUBLIC_KEY to .env`.

### 3.3 Admin password
```bash
$ ./scripts/hash-password.sh
```
Type a password of at least 12 characters (nothing is shown while typing).
Expect: `Saved ADMIN_PASSWORD_HASH to .env`. The first run takes a minute (it downloads dependencies).
Your admin login is **`ankur`** + this password.

### 3.4 Website settings
```bash
$ cp web/.env.example web/.env.local
$ S=$(grep '^REVALIDATE_SECRET=' .env | cut -d= -f2)
$ sed -i '' "s|^REVALIDATE_SECRET=.*|REVALIDATE_SECRET=$S|" web/.env.local
$ cat web/.env.local
```
Expect `PULSE_API_URL` and `NEXT_PUBLIC_API_URL` = `http://localhost:8080` (the gateway) and a
`REVALIDATE_SECRET` equal to the one in `.env`.

---

## Step 4 — Start the databases and brokers

```bash
$ docker compose -f infra/docker-compose.yml --env-file .env --profile core --profile msg up -d
```
The first run downloads ~1.5 GB of images.

```bash
$ docker compose -f infra/docker-compose.yml --env-file .env --profile core --profile msg ps
```
Expect `pulse-postgres`, `pulse-redis`, `pulse-kafka`, `pulse-kafka-ui`, `pulse-rabbitmq`, all
`Up … (healthy)` after about 30–60 s.

Check the databases were created:
```bash
$ docker exec pulse-postgres psql -U postgres -lqt | cut -d'|' -f1 | grep _db
```
Expect: `content_db`, `ingestion_db`, `assistant_db`.

> Save typing: `alias dc='docker compose -f infra/docker-compose.yml --env-file .env'` in `~/.zshrc`,
> then `dc --profile core --profile msg up -d`.

---

## Step 5 — Build the backend

```bash
$ ./mvnw -DskipTests package
```
Expect `BUILD SUCCESS` (first run: several minutes of downloads). The service jars appear in
`services/*/target/`.

---

## Step 6 — Start content-service (terminal 1)

```bash
$ ./mvnw -pl services/content-service -am spring-boot:run -Dspring-boot.run.profiles=dev
```
Wait for `Started ContentServiceApplication`. On startup it applies its database migrations, loads the
sample posts (`dev` profile) and creates the admin user from `.env`.

In another terminal:
```bash
$ curl -s localhost:9081/actuator/health
$ curl -s 'localhost:8081/api/v1/posts?limit=2' | jq '.items[].title'
```
Expect `{"status":"UP",…}` and two post titles.

---

## Step 7 — Start the API gateway (terminal 2)

```bash
$ ./mvnw -pl services/api-gateway spring-boot:run
```
Wait for `Started ApiGatewayApplication`, then:
```bash
$ curl -s -i 'localhost:8080/api/v1/posts?limit=1' | grep -iE '^HTTP|x-request-id|x-ratelimit'
```
Expect `HTTP/1.1 200`, an `X-Request-Id` and an `X-RateLimit-Remaining` header.

---

## Step 8 — Start the website (terminal 3)

```bash
$ cd web
$ npm install
$ npm run build
$ npm run start
```
`npm run build` fetches posts through the gateway to pre-render pages, so steps 6 and 7 must be running.
Expect a route table listing `/`, `/tech`, `/football`, `/ai` and several `/posts/...` paths, then
`Ready … http://localhost:3000`.

Open **http://localhost:3000**. You should see the home page with Tech, Football and AI sections.

---

## Step 9 — Use it

### 9.1 Admin: write and publish a post
1. Open http://localhost:3000/admin/login and sign in as `ankur` with your password from step 3.3.
2. Click **New post**, give it a title, pick a section, write some Markdown (the right side previews it).
3. Click **Save**, then **Publish**.
4. Open the section page (for example http://localhost:3000/tech). The post is there within about a
   second: content-service wrote an event to its outbox, it went through Kafka, the cache was cleared
   and the page was refreshed. You didn't restart anything.

### 9.2 Look inside
| What | Where |
|---|---|
| Kafka events | http://localhost:8085 → Topics → `content.post.events` → Messages |
| RabbitMQ | http://localhost:15672 (user `pulse`, password `RABBITMQ_PASSWORD` from `.env`) |
| API docs | http://localhost:8081/swagger-ui.html |
| Redis keys | `docker exec -it pulse-redis redis-cli --scan --pattern 'cache:*'` |
| Trending | `curl -s localhost:8080/api/v1/trending \| jq` (open a few posts first) |
| Search | `curl -s 'localhost:8080/api/v1/search?q=kafka' \| jq '.items[].title'` |
| Rate limit | `for i in $(seq 1 70); do curl -s -o /dev/null -w '%{http_code} ' localhost:8080/api/v1/sections; done` → 200s, then 429s |

---

## Step 10 — Run the tests

```bash
$ ./mvnw verify                                   # every module: unit + integration tests
$ ./mvnw -pl services/content-service -am verify  # a single service
$ (cd web && npx tsc --noEmit)                    # website type check
```
Expect `BUILD SUCCESS`. Integration tests start their own throw-away containers (Testcontainers), so
Docker must be running, but your local data is never touched.

Load test (optional):
```bash
$ k6 run -e BASE=http://localhost:8080 load-tests/k6/phase3-cache.js
```
Expect a summary with `http_req_duration … p(95)=…ms` and a green check on the thresholds.

---

## Step 11 — Stop and start again later

| Goal | Command |
|---|---|
| Stop a service / the website | `Ctrl+C` in its terminal |
| Stop containers (keep data) | `docker compose -f infra/docker-compose.yml --env-file .env --profile core --profile msg stop` |
| Start containers again | the same command with `start` |
| Start everything again | step 4 `up -d` → steps 6, 7 → `cd web && npm run start` |
| **Delete all data** | the same compose command with `down -v` ⚠️ (databases are recreated empty on the next `up`) |

---

## Step 12 — If something goes wrong

| Symptom | Fix |
|---|---|
| `Cannot connect to the Docker daemon` | Start Docker Desktop: `open -a Docker`. |
| `Port 8081 was already in use` (or 8080/3000) | `lsof -i :8081` → find the PID → stop that process (`kill <PID>`). |
| `password authentication failed for user "content"` | You changed DB passwords after the first start. Reset with `down -v` (wipes data) and repeat step 4. |
| `Migration checksum mismatch` | A database migration file was edited. Restore it from git (`git checkout -- <file>`). |
| Website shows "No posts yet" | The gateway or content-service wasn't running during `npm run build`. Start them, rebuild, restart. |
| Admin save fails with 403 | Reload the admin page (it refreshes the CSRF cookie) and try again. |
| Login returns 429 | 5 wrong passwords in 15 minutes. Wait, or `docker exec pulse-redis redis-cli DEL auth:fail:127.0.0.1`. |
| Published post doesn't appear on the site | `REVALIDATE_SECRET` differs between `.env` and `web/.env.local`, or Kafka isn't running (`--profile msg`). |
| Everything is slow | `docker stats`: Docker may be out of memory. Close other apps or stop containers you don't need. |

---

## Coming next (not on `main` yet)

These phases are being built on the `wip/phases-5-10` branch and will get their own steps here when merged:
- **ingestion-service** (RabbitMQ feed crawler, "Around the web" on the site)
- **assistant-service** ("Ask Pulse" chatbot with a local LLM via Ollama)
- **Kubernetes** (kind + Helm + autoscaling: `scripts/k8s-up.sh`)
- **Observability** (Prometheus, Grafana, tracing) and **CI/CD**

# NutriMarket: Five-Agent Nutrition and Grocery Platform

Java 17, Spring Boot 4.1.1, MySQL and MyBatis implement **Agent 1 (Dietary Analysis)**, **Agent 2 (Health and Nutrition Assessment)**, **Agent 3 (Nutrition Planning)** **Agent 4 (Market Intelligence)** and **Agent 5 (Procurement and Member Experience, simulated partner stores)**. Agent 1 follows DA-01 through DA-07 and UC-01: meal photo and/or description → Qwen recognition → USDA nutrition lookup → member confirmation → validated record → Agent 2. Where the plan overview assigns profile management differently, the detailed requirements take precedence.

The application UI, messages, comments, documentation and model output instructions use English. Dates and numbers use English formatting while preserving the relevant timezone. Previously stored records, cached explanations and user-entered text are retained as originally supplied; changing application language does not rewrite historical evidence.

## Setup and configuration

Database credentials, external service endpoints, models and API keys belong in **`config/api-keys.yml`**. This local file is ignored by Git and excluded from the JAR. `config/api-keys.example.yml` is the template without real secrets. Copy it only when no local configuration exists:

```bash
cp config/api-keys.example.yml config/api-keys.yml
mysql -u root -p < database/create-database.sql
```

Use MySQL 8.0 or newer. Set `spring.datasource` in the local configuration:

```yaml
spring:
  datasource:
    url: "jdbc:mysql://127.0.0.1:3306/nutrimarket?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
    username: "your_database_user"
    password: "your_database_password"
```

Alternatively set `MYSQL_URL`, `MYSQL_USERNAME` and `MYSQL_PASSWORD`. The development database user needs table-creation privileges. `spring.sql.init.mode=always` runs `src/main/resources/schema.sql` on startup. It creates missing tables without deleting existing ones, but **does not upgrade older table definitions**. Follow the [migration instructions](database/migrations/README.md) before starting against an older database. Application accounts in `member_account` are separate from MySQL accounts. Tables use InnoDB and utf8mb4; photos use LONGBLOB for uploads up to 10 MB. In production, apply schema migrations separately and set `spring.sql.init.mode=never`.

Add the following under the existing `nutrimarket` key in the same configuration file; do not duplicate YAML keys:

```yaml
nutrimarket:
  planning:
    model: qwen-plus
    timeout: 120s
    market-endpoint: ""
    market-api-key: "${MARKET_AGENT_API_KEY:}"
  health:
    summary-model: qwen-plus
    summary-timeout: 30s
    expert-usernames: []
  integrations:
    vision:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      model: qwen-vl-plus
      api-key: "your_bailian_api_key"
      timeout: 120s
    nutrition:
      base-url: https://api.nal.usda.gov/fdc/v1
      api-key: "your_usda_api_key"
      timeout: 20s
    health-agent:
      endpoint: ""
      api-key: ""
      timeout: 10s
      max-attempts: 3
```

Placeholders such as `${DASHSCOPE_API_KEY:}` and `${USDA_API_KEY:}` can read keys from environment variables. Restart after changing configuration. The Qwen key must match the endpoint region. For another region or a workspace-specific endpoint, copy its **OpenAI-compatible URL ending in `/compatible-mode/v1`** from the Bailian console. No OpenAI key or Python service is required. USDA uses a separate key. Agent 2 explanations Agent 3 planning and Agent 4 semantic matching reuse the Qwen endpoint and key, with separate text model settings.

JWT configuration is also in `config/api-keys.yml`, under `nutrimarket.security.jwt`. Generate a signing secret with `openssl rand -base64 32`, then set `secret` or the `JWT_SECRET` environment variable. The template contains no usable shared secret. Missing or short secrets prevent startup; the decoded key must contain at least 32 bytes. Multiple instances need the same key. Rotating it invalidates existing tokens.

Run from the project root so the application can load `./config/api-keys.yml`:

```bash
./mvnw spring-boot:run
```

Or build and run:

```bash
./mvnw clean verify
java -jar target/NutriMarket-0.0.1-SNAPSHOT.jar
```

For another working directory, specify `--spring.config.import=file:/your/directory/api-keys.yml`. Open `http://127.0.0.1:8080/`. The configured server address is `0.0.0.0`; LAN clients use the host machine IP and port 8080, subject to firewall and network access.

Once MySQL and JWT are configured, startup does not require AI keys. A valid upload with missing keys returns a record in `DEFERRED` with `VISION_NOT_CONFIGURED` or `NUTRITION_NOT_CONFIGURED`. No nutrition data is fabricated. Configure the keys, restart and retry the original record.

## Accounts and pages

The application serves plain HTML, JavaScript and CSS without a separate frontend server. Visit the service URL instead of opening HTML files directly. Use `/register.html` to register, `/login.html` to sign in, `/` to browse meals, `/upload.html` to upload, and `/record.html?id=...` to review a meal. Agent 2 provides health profiles, weekly assessments, assessment details and expert review. Agent 3 adds weekly meal plans, publication, targeted replans and independent planning reviews. See [Agent 3](docs/PLANNING_AGENT.md), [frontend usage](docs/FRONTEND.md) and [Agent 2](docs/HEALTH_AGENT.md).

Usernames contain 3–32 ASCII letters, digits or underscores, start with a letter, and are trimmed and converted to lowercase. Passwords are stored with BCrypt. Login returns a JWT, valid for 15 minutes by default. The frontend keeps it in the current tab sessionStorage, removes it on sign-out and redirects to login after HTTP 401. Each data request is still authenticated by the backend. HTTP Basic is disabled. See [authentication](docs/AUTH.md).

`schema.sql` creates or updates the default account **`admin / elec5620`** and the default Human Expert account **`expert / elec5620`** (review access requires `expert` in `nutrimarket.health.expert-usernames`; sign in via **Human expert sign-in**). With startup SQL enabled, each startup resets its password and enables it while preserving its ID and creation time. It does not create duplicates or grant administrative or expert privileges.

## Demo data

`src/main/resources/demo-data.sql` runs after `schema.sql` on every startup, so any new database receives the presentation accounts (password `nutrimarket-demo`). Every statement is idempotent: existing rows are kept, and only the demo passwords are reset, like `admin`. Add `demo_expert` to `nutrimarket.health.expert-usernames` and keep `nutrimarket.market.test-catalogue: true` in `config/api-keys.yml`. To disable the data, set `spring.sql.init.data-locations: optional:classpath:none.sql`.

Meal plans are week-specific and expire after their week, so they are not stored in SQL. Before a presentation (within 12 hours, because catalogue evidence is re-stamped after that), run `DemoSeeder` once. It restores every demo account to the SQL baseline, discarding rehearsal changes. Then it prepares the scenarios in `demo-scenarios.json` for next week and checks each one, exiting with code 1 if any check fails. It needs the USDA API but no Qwen calls, and takes about 10–15 minutes:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--nutrimarket.demo.seed=true --server.port=0 --nutrimarket.health.scheduler-enabled=false --nutrimarket.planning.delivery-enabled=false --nutrimarket.market.delivery-enabled=false --nutrimarket.pipeline.enabled=false --nutrimarket.market.semantic-enabled=false"
```

Add `--nutrimarket.demo.keep-existing=true` to add the demo data without deleting anything, for example on the shared team database. [docs/DEMO_SCENARIOS.md](docs/DEMO_SCENARIOS.md) lists what each account contains, what it demonstrates and the presentation steps. `DemoScenarioTests` runs the same scenarios on H2.

While the application runs, the hourly health scheduler only assesses each member's previous week, so it does not touch the seeded week 2026-10-05 once that week is no longer the previous one.

## Agent 2 test account

To prepare a separate account for testing from Agent 2 onward, run:

```bash
python3 database/seed-test0001.py --execute
```

The script requires Python 3.9+, PyYAML, the `mysql` client and BCrypt-capable `htpasswd`.
It reads `config/api-keys.yml`, reuses an existing `test0001` account or creates
`test0001 / test0001` with a BCrypt password, and uses the repository's synthetic
`demo-data.sql` nutrition to prepare 21 validated meals (three per day)
for the previous completed Monday–Sunday in Australia/Sydney. It also creates an initial
health profile and matching delivered outbox/intake entries. Meals carry explicit test
provenance; no photo analysis, assessment or downstream workflow is run by the script.
Use `--week-start YYYY-MM-DD` for another completed week, or omit `--execute` to preview.
For the last 30 completed days (90 meals), run:

```bash
python3 database/seed-test0001.py --days 30 --execute
```

Combine `--days` with `--start-date YYYY-MM-DD` for a fixed period. Local meal times
remain consistent across daylight-saving changes. Existing account IDs, passwords,
health profiles and downstream results are preserved. Re-running or extending a period
adds only missing fixtures and verifies the existing record/outbox/intake links.
In Health assessment, select the seeded week's Monday and generate the report.

## Request examples

```bash
curl -X POST http://127.0.0.1:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"My-local-password-123!"}'

curl -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"My-local-password-123!"}'
```

Save the returned `accessToken` as `ACCESS_TOKEN`. Generate a UUID for each new meal; reuse it with identical fields and photo when retrying an upload after a network failure:

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  -X POST http://127.0.0.1:8080/api/v1/dietary-records \
  -F 'recordId=3ec3b7e4-bba6-481e-8f21-e6c6dfeb1001' \
  -F 'profileVersion=1' \
  -F 'mealTime=2026-10-10T08:00:00Z' \
  -F 'photoProcessingConsent=true' \
  -F 'photo=@/your/path/meal.jpg'
```

Supply the actual meal time with a timezone, for example `2026-10-10T19:00:00+11:00`. Photos must be JPEG/PNG, at most 10 MB, at least 224 pixels on each side and at most 20 million pixels. The backend decodes the content rather than trusting its extension or MIME type. The upload page accepts source photos up to 30 MB and automatically prepares their dimensions and file size before submission. Alternatively, omit the photo and submit `mealDescription` (up to 3000 characters), or send both. English and Chinese meal descriptions are supported. Text-only meals require explicit member confirmation before validation and delivery to Agent 2.

The response includes identifiers, profile version, status, foods and sources, nutrition totals, confidence, timestamps, failures and audit events. Nutrition is calculated from per-100g source values and portion sizes. USDA nutrient IDs include `1008` (energy, kcal), `1003` (protein, g), `1004` (fat, g) and `1005` (carbohydrate, g). Other available nutrients retain their IDs and units. Meal totals include only nutrients present in all food items with consistent units. Missing micronutrients are not zero; missing required macronutrients defer analysis.

Confirm the results:

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" -X POST \
  http://127.0.0.1:8080/api/v1/dietary-records/3ec3b7e4-bba6-481e-8f21-e6c6dfeb1001/confirm
```

To correct a meal, submit its **complete food list**. Nutrition is queried again and explicit confirmation is still required:

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" -X PUT \
  http://127.0.0.1:8080/api/v1/dietary-records/3ec3b7e4-bba6-481e-8f21-e6c6dfeb1001/foods \
  -H 'Content-Type: application/json' \
  -d '{"foods":[{"name":"Cooked rice","searchTerm":"Rice, white, long-grain, regular, enriched, cooked","portionGrams":180,"confidence":0.7,"fdcId":null}]}'
```

Use English search terms including cooking method. Leave `fdcId` null to search, or provide a confirmed USDA entry ID. Check the returned `matchedDescription`.

## States and delivery

All dietary endpoints require Bearer authentication; their base path is `/api/v1/dietary-records`. [API documentation](docs/API.md) and [HTTP examples](docs/dietary-api.http) cover listing, details, edits, retries, confirmation, abandonment, validated data and delivery status.

- `PENDING_MEMBER_CONFIRMATION`: confidence is below the threshold, or the member has edited results. No downstream delivery yet.
- `VALIDATED`: the record and its unique outbox entry are committed in one transaction.
- `DEFERRED`: recognition, nutrition, configuration or source-data problems; the photo and completed recognition are retained for recovery.
- `REPLACEMENT_REQUIRED`: the model cannot recognise the meal reliably; upload a new photo with a new UUID.
- `ABANDONED` / `EXPIRED`: no delivery. Pending confirmation expires after 24 hours by default, checked on reads and actions.

Invalid image content returns HTTP 422 before record creation. Oversized uploads return 413, invalid fields 400 and conflicting IDs or state transitions 409. An accepted request can return HTTP 200 with `DEFERRED`; inspect status and failures instead of treating HTTP success as analysis success.

Policies in `application.yml` control confidence, policy version, confirmation lifetime and attempts. The default maximum is three analysis attempts including the first. Food corrections start a new nutrition-query attempt sequence. Overall confidence is the minimum of recognition and matching confidence. Exact USDA text matches score 0.95 and approximate matches 0.60; these are engineering heuristics, not calibrated probabilities. At the default 0.85 threshold, approximate matches require confirmation.

`ValidatedDietaryIntakeRecord` is the versioned Agent 2 contract. It includes schemaVersion 1, IDs, profile/policy versions, foods, portions, nutrients, confidence, provenance and meal/analysis/confirmation/publication timestamps.

With `health-agent.endpoint` blank, `HealthInbox` consumes validated outbox data inside the application and deduplicates by recordId transactionally. Delivery changes from QUEUED to DELIVERED. An explicit full receiver URL enables remote delivery every 30 seconds with `Idempotency-Key: <recordId>` and optional Bearer authentication. Only 2xx indicates success; the default limit is three attempts. Failures retain the data and error code. After investigation, increasing `max-attempts` and restarting permits further attempts.

Delivery is **at least once**: a lost acknowledgement can cause another send. Receivers must deduplicate by recordId with a unique constraint and transaction, return 2xx for identical accepted records, and validate profile-version applicability before assessment. Agent 2 feeds the implemented Agent 3 planning module; Agent 3 publishes to Agent 4 market matching. Agent 4 delivers to the implemented Agent 5 local procurement module.

## Implementation and verification

MyBatis mappers persist MySQL records and the outbox. Photos are stored separately, never returned by the record API, and cleared after validation, abandonment, expiry checks or replacement requests. This is a single-instance course project: synchronous external analysis affects response time, and distributed processing is not implemented.

`member_account.id` is a UUID primary key; `username` is a unique login name. Record ownership comes from the authenticated JWT and is enforced by foreign keys and backend checks. New uploads check the current profile version when a profile exists. Public deployments need HTTPS. Password reset, refresh tokens, gateway login rate limits and optional nutrition-label OCR are outside the current scope.

The Java source follows Controller → Service → DAO. Each layer groups auth, dietary and health features. Provider clients live in `integration`, contracts in `model`, and HTML/JavaScript views in `resources/static`. See [architecture and package layout](docs/ARCHITECTURE.md).

```bash
./mvnw test
node --test src/test/frontend/upload-crypto.test.mjs
```

Backend tests exercise authentication, JWT validation, ownership, image checks, multipart uploads, pagination, portion calculations, transitions, expiry, concurrency, recovery, transaction rollback, provider protocols and delivery. Health tests also cover coverage, profile versions, review permissions and AI consent. Tests use H2/MyBatis and local or mocked providers, with no paid model requests; H2 is test-scope only. See [isolated MySQL testing](docs/API.md#mysql-integration-tests). Old H2 data is neither imported nor deleted automatically.

References: [Bailian compatible API](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions), [Qwen vision](https://help.aliyun.com/zh/model-studio/vision), [USDA API guide](https://fdc.nal.usda.gov/api-guide/), [Spring Boot starters](https://docs.spring.io/spring-boot/4.0/reference/using/build-systems.html), [MyBatis Spring Boot starter](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/).

## Agent 4 local product matching

Open **Market matching** from the sidebar. Configure `nutrimarket.market.operator-usernames` in `config/api-keys.yml` to allow a registered operator to maintain factual products on `/catalogue.html`. Local catalogue mode is enabled; no demo products are seeded. Published Agent 3 plans feed Agent 4 automatically, or can be matched from `/market.html`. The matching form opens results directly; its historical result list is not displayed. See [Agent 4 setup and contracts](docs/MARKET_AGENT.md). A blank procurement endpoint now delivers to Agent 5 in process.

## Agent 5 procurement

Open **Shopping baskets** and **Edit basket & compare options** to edit product choices, budget and fulfilment together. Don't like this product replaces a product when possible and keeps it out of later baskets; Don't like this food asks the meal planner to replace the meals that use it (publish the revised plan to get a new basket) and keeps it out of later plans; Don't need skips that ingredient for this purchase. Compare all six Economy/Balanced/Premium and Delivery/Pick-up outcomes, including per-supermarket fees, then **Save and recalculate** before **Confirm and place order**. The basket is refreshed and revalidated first, and you confirm again only if prices, stock or products changed. Orders go to **simulated partner stores**: no real supermarket order, payment or stock reservation takes place. No extra model/API key is needed. See [Agent 5 workflow, configuration and API](docs/PROCUREMENT_AGENT.md).

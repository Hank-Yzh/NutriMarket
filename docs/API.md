# Dietary Analysis REST API

Base URL: `http://127.0.0.1:8080/api/v1/dietary-records`. All dietary endpoints require `Authorization: Bearer <accessToken>` obtained through [username authentication](AUTH.md). The JWT UUID (`member_account.id`) supplies memberId; clients cannot choose ownership. HTTP Basic is disabled.

The implementation is Controller → Service → DAO (repository and MyBatis mappers) → MySQL. Executable examples are in [dietary-api.http](dietary-api.http).

## Endpoints

| Method | Relative path | Request | Response |
|---|---|---|---|
| POST | Base path | Multipart upload | Full record |
| GET | Base path | page, size, optional status | Paginated summaries |
| GET | `/{id}` | UUID | Full record |
| PUT | `/{id}/foods` | Complete food list as JSON | Recalculated record |
| POST | `/{id}/confirm` | No body | Validated record |
| POST | `/{id}/abandon` | No body | Abandoned record |
| POST | `/{id}/retry` | No body | Retried record |
| GET | `/{id}/validated` | UUID | Agent 2 contract |
| GET | `/{id}/delivery` | UUID | Delivery state |

`{id}` is the upload recordId. Successful responses return HTTP 200, including accepted requests whose analysis failed. Always inspect `status` and `failures`.

## Upload

`POST /api/v1/dietary-records` uses `multipart/form-data`:

| Field | Type | Constraints |
|---|---|---|
| recordId | UUID | Required; reuse for identical retries, generate a new ID for a new meal |
| profileVersion | long | Required and positive; current profile version when a profile exists |
| mealTime | ISO 8601 timestamp | Required with timezone, no more than five minutes in the future |
| photoProcessingConsent | boolean | Must be true; the legacy field name covers consent to analyse the submitted photo and/or description |
| photo | file | Optional with a description; JPEG/PNG, at most 10 MB, at least 224 pixels per side, at most 20 million pixels |
| mealDescription | string | Optional with a photo; at most 3000 characters; describe foods, portions and cooking methods |

At least one nonempty photo or meal description is required. Descriptions may be in
English or Chinese and may supplement a photo. Text-only requests use the same Qwen
structured food recognition and USDA nutrition lookup. They always require explicit
member confirmation before validation and delivery to Agent 2, even if the model
reports high confidence. Insufficient descriptions return REPLACEMENT_REQUIRED with
UNUSABLE_DESCRIPTION; provider failures retain the description for retries.

Identical ID, identity, metadata, photo and description return the existing record
without another model call. Changed text with the same ID returns 409. Photo-only
fingerprints remain compatible with older records. Invalid image quality returns
422, or REPLACEMENT_REQUIRED after semantic checks. Record details also include
`inputMethod` (PHOTO, TEXT or PHOTO_AND_TEXT) and the original `mealDescription`.

## List

`GET /api/v1/dietary-records?page=1&size=20&status=VALIDATED`

- page: one-based, default 1.
- size: default 20, range 1–100.
- status: optional exact state enum; omit to include all states.

Results are ordered by creation time descending, then recordId ascending. Only current-member records are queried and photos are not loaded. Expired pending records are filtered as EXPIRED and persisted accordingly. Beyond the last page, items is empty but total remains the full count.

Example structure, not actual nutrition data:

```json
{
  "items":[{
    "recordId":"3ec3b7e4-bba6-481e-8f21-e6c6dfeb1001",
    "profileVersion":1,
    "status":"DEFERRED",
    "mealTime":"2026-10-10T08:00:00Z",
    "createdAt":"2026-10-10T08:01:00Z",
    "confidence":null,
    "nutrients":{}
  }],
  "total":1,"page":1,"size":20
}
```

## Details, edits and confirmation

`GET /{id}` includes schemaVersion, recordId, memberId, profileVersion, policyVersion, status, timestamps, attempts, confidence, estimates, foodItems, nutrients, provenance, failures and audit. It never returns the original photo.

`PUT /{id}/foods` replaces the complete food list with 1–20 entries:

```json
{"foods":[{"name":"Cooked rice","searchTerm":"Rice, cooked","portionGrams":150,"confidence":0.7,"fdcId":null}]}
```

name and searchTerm are required, up to 200 characters each. Use English for USDA search terms. portionGrams ranges from 0.1 to 10000, confidence from 0 to 1. fdcId is null or a positive selected USDA entry ID. Recognition must exist before editing. Edits re-query nutrition sources; clients cannot supply calculated nutrient values. Explicit `/confirm` is still required after successful recalculation.

Nutrients are keyed by USDA ID, for example `{"1008":{"amount":195,"unit":"kcal"}}`. IDs 1008/1003/1004/1005 mean energy/protein/fat/carbohydrate. Missing values are not zero. Confidence is an engineering estimate, not a statistical guarantee.

| State | Available actions |
|---|---|
| PENDING_MEMBER_CONFIRMATION | Read, edit, confirm, abandon |
| DEFERRED | Read, retry, abandon; edit after recognition exists |
| REPLACEMENT_REQUIRED | Read, abandon; upload a replacement using a new ID |
| VALIDATED | Read, validated contract, delivery; repeated confirmation is idempotent |
| ABANDONED | Read; repeated abandonment is idempotent |
| EXPIRED | Read |

Retry does not require another photo upload; the limit includes the initial analysis attempt. Confirmation updates the record and creates a unique outbox row in one transaction, rolling back both on failure. Abandonment retains the audit and cannot revoke a validated record.

## Downstream contract and delivery

`GET /{id}/validated` requires VALIDATED. The contract contains schemaVersion, recordId, memberId, profileVersion, policyVersion, timestamp, mealTime, analyzedAt, confirmedAt, foodItems, nutrients, confidence and provenance.

Delivery response example:

```json
{"state":"QUEUED","attempts":0,"lastError":null}
```

States are QUEUED, DELIVERED and DELIVERY_FAILED; the independent adapter also retains WAITING_FOR_AGENT_2 when disconnected. A blank endpoint uses local Agent 2. See [health APIs](HEALTH_AGENT.md). Configuration is under `nutrimarket.integrations.health-agent`. Delivery is at least once: receivers must deduplicate by recordId, validate applicable profile versions and acknowledge identical repeats with 2xx. Unvalidated records have no downstream contract; these endpoints return 409.

## Errors

Errors use `{"code":"INVALID_REQUEST","message":"English explanation","timestamp":"..."}`. Authentication errors have the same shape with HTTP 401 and code UNAUTHORIZED.

| HTTP | Code | Meaning |
|---|---|---|
| 400 | INVALID_REQUEST / CONSENT_REQUIRED / INVALID_METADATA | Invalid parameters, consent or metadata |
| 401 | UNAUTHORIZED | Missing, expired or invalid token, or unavailable account |
| 404 | NOT_FOUND | Record absent or owned by another member |
| 409 | INVALID_STATE / IDEMPOTENCY_CONFLICT / RECORD_CONFLICT | Invalid transition, duplicate-content mismatch or concurrent write conflict |
| 413 | IMAGE_TOO_LARGE | Upload exceeds limit |
| 422 | INVALID_IMAGE | Invalid image content or dimensions |
| 503 | DATABASE_ERROR | Database operation failed; SQL and credentials are not exposed |

VISION_* and NUTRITION_* errors appear in a DEFERRED record failures list. An unavailable database, missing schema database or incorrect database credentials can prevent application startup and must be corrected first.

## MySQL integration tests

`./mvnw test` defaults to test-only H2 with real MyBatis and transactions. For MySQL, prepare a **separate disposable test database**, such as nutrimarket_test. Tests clear business tables in that database; never point them at actual application data.

```bash
./mvnw test -Dtest=AuthIntegrationTests,DietaryAnalysisTests \
  '-Dtest.database.url=jdbc:mysql://127.0.0.1:3306/nutrimarket_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' \
  -Dtest.database.username=your_test_user \
  -Dtest.database.schema=classpath:schema.sql
```

Use `TEST_DATABASE_PASSWORD` for the test.database.password setting instead of putting credentials in commands or version control. Tests use fixture model and nutrition services, not paid APIs.

## Nutrition Planning Agent

Agent 3 endpoints under `/api/v1/planning` are documented in [PLANNING_AGENT.md](PLANNING_AGENT.md), including generation, publication, versioned packages, targeted replans and expert review.

## Agent 4 market matching

Market matching remains available at `/market.html`, product evidence at `/market-result.html?id=...`, and the operator catalogue editor at `/catalogue.html`. The matching page contains the published-plan matching form; its historical Your market results list, list refresh button and pagination have been removed. Matching still opens the selected plan's result directly. Backend history, matching and delivery APIs remain available under `/api/v1/market`; see [MARKET_AGENT.md](MARKET_AGENT.md).

## Agent 5

See [Procurement API and order semantics](PROCUREMENT_AGENT.md#http-api). All `/api/v1/procurement` routes require JWT and enforce member or expert/report authorization.

# Agent 2: Health and Nutrition Assessment

Implements HD-01 through HD-08 / UC-02 in the `controller.health`, `service.health`, `dao.health` and `model.health` packages of the same Spring Boot application, using MyBatis, MySQL, existing JWT authentication and member UUIDs. No second service is needed.

## Workflow

1. Open **Health profile** (`/profile.html`) and supply goals, timezone, allergies, restrictions, relevant conditions, preferences and optional AI consent. Leave lists empty when none apply. Optional **About you** fields take height, weight and year of birth (given together; adults 16–100), sex and activity level. Nutrition targets are managed by the backend:
   - With measurements, the Mifflin-St Jeor equation estimates maintenance energy (resting energy × activity factor; without sex the two equations are averaged, without an activity level light activity is assumed).
   - With AI consent, Qwen generates targets from the health goals, starting from that estimate when given. Its energy must stay within 70–130% of the estimate and its protein within 0.8–2.4 g per kg, otherwise the calculation is used.
   - Without consent, measurements give calculated targets: energy ±10% around the estimate (−15% for weight-loss goals, +10% for muscle or weight-gain goals), protein 1.0–1.6 g/kg (1.6–2.2 for muscle or weight gain), fat 25–35% and carbohydrate 45–60% of energy. Without measurements the configured application defaults apply.
   - Changing the goals, the measurements or the AI consent recalculates the targets; other edits keep them. The profile page shows the current targets, their source and rationale; the expert review page also shows the measurements.
2. Saving creates version v1, then a new version on every save. `expectedVersion` detects concurrent edits. Uploads read the current profile version. New uploads with an outdated version are rejected; idempotent retries of existing uploads can retain their original version.
3. Upload and confirm meals through Agent 1. With endpoint blank, the outbox delivers to local `HealthInbox` every 30 seconds. It accepts only validated outbox records, with no client endpoint for fabricated nutrient values. The unique record_id and transaction locks prevent double counting.
4. Open **Health assessment** (`/health.html`) and choose any start date for seven completed consecutive dates within the last two years. The default is local today minus seven days through yesterday. The profile IANA timezone defines start-date 00:00 inclusive to start plus seven days 00:00 exclusive, including daylight-saving transitions. A coverage check offers **Add missing meals** or **Assess existing records** when any date has fewer than three recorded meals. At least one validated meal is needed; 21 meals are not mandatory.
5. `/assessment.html?id=...` shows coverage, recorded daily averages, target comparisons, persistent patterns, structured constraints, meal evidence, expert decisions, optional AI explanation and a meal-planning link. Partial reports explicitly show the limited coverage.
6. Assessments requiring review remain blocked until an authorised expert submits an explicit decision in `/expert.html`. Members cannot clear their own assessments.

The scheduler starts after one minute and checks the last seven completed dates in each member timezone every hour. The period unique key makes this idempotent. It does not consent to partial assessment for the member. Members without a health profile must complete it first. Manually regenerate older partial periods after adding records.

## Data and rules

- Tables: `member_health_profile` (member/version primary key), `health_intake` (meal ID primary key), and `weekly_health_assessment` (unique member/profile version/start date). Ownership uses member UUIDs with foreign keys. Arbitrary start dates and the additional coverage fields need no schema migration.
- New databases use `schema.sql`. Existing UUID-based databases can run `database/migrations/003_health_agent.sql`, or restart with `spring.sql.init.mode=always`. Migration 003 only adds tables and does not alter accounts, meal history or passwords. Username-primary-key databases need migration 002 first.
- The coverage reference is seven days with three validated meals per day; it triggers a reminder rather than a mandatory 21-meal gate. The missing-meal count sums shortfalls per date so extra entries on one date cannot hide missing dates. This prototype reference does not establish a medically complete dietary survey or prove meals were not omitted.
- Totals include validated meals only. Recorded daily average = recorded total / number of dates with recorded meals. With full coverage the divisor is seven. Unrecorded dates and meals remain unknown. A target nutrient missing from any recorded meal or having incompatible units produces MISSING_DATA, never zero, and prevents clearance.
- An explicit `allowIncomplete: true` completes a partial assessment using existing meals, with `partialDataAccepted`, `incompleteRecords`, `observedDays` and `missingMeals` in the report. Without that choice it remains INSUFFICIENT_INTAKE_DATA. Zero records remain unassessable. Non-high-risk partial results are LIMITED_DATA, with RECORDED_BELOW_TARGET / RECORDED_WITHIN_TARGET / RECORDED_ABOVE_TARGET comparisons; they do not establish actual under-eating, nutrient deficiency or disease.
- Persistent patterns require at least three covered days below or above a target by default. They do not imply consecutive days; counts are displayed explicitly.
- Experts are involved only for unusual cases. Demonstration review rules flag POTENTIAL_HIGH_RISK when the profile lists a condition that is not on `routine-conditions` (exact, case-insensitive match; unrecognised text counts as unusual), or recorded energy is below 0.5 times the target minimum / above 1.5 times the maximum on at least three covered days. Expert review is then required. Routine conditions (default: type 2 diabetes, prediabetes, high blood pressure, hypertension, high cholesterol, overweight, obesity) are recorded as planning context and cleared automatically. These engineering thresholds are **not clinically validated** and must not make automatic medical decisions. Update policy-version when changing rules.
- One assessment exists per period and profile version. New meals can update a waiting or unreviewed partial assessment under the same ID with a higher revision; the member's prior choice to accept partial records is retained. Unchanged inputs do not increase revision. Fully covered and expert-reviewed completed assessments remain fixed snapshots. A profile update allows a new assessment and marks prior versions stale, blocking further planning input. Updating partial evidence also invalidates plans based on an earlier assessment revision.
- Historical meals keep their original profileVersion and recordId. Actual intake can be compared with current targets without rewriting meal versions. A meal referencing a future profile version prevents clearance.
- Planning input requires COMPLETED, CLEARED and the current profile version, including member-accepted partial assessments. The input carries incomplete-record and observed-coverage fields so planning and AI explanation do not interpret missing meals as zero or diagnose a deficiency. High-risk assessments still require a retained CLEARED_FOR_PLANNING or MODIFIED_AND_CLEARED expert decision. Additional constraints never remove existing allergies or restrictions.
- Agent 3 consumes the guarded `planning-input` contract and rechecks current clearance before generation, publication and downstream handoff. See [Planning](PLANNING_AGENT.md) and [Market matching](MARKET_AGENT.md).

## Configuration and permissions

General policy is under `nutrimarket.health` in `application.yml`. Replaceable model settings and credentials remain in `config/api-keys.yml`:

```yaml
nutrimarket:
  health:
    summary-model: qwen-plus
    summary-timeout: 30s
    expert-usernames: [expert]
```

Merge this fragment into the existing key; do not duplicate `nutrimarket`. AI explanations reuse the `integrations.vision` base-url and api-key, while the text model is independently configurable.

`schema.sql` creates the default Human Expert account **`expert / elec5620`**; it has review access only while listed in expert-usernames. Admin is not an expert. After confirming authorisation, add further reviewer usernames to expert-usernames and restart. Experts sign in through **Human expert sign-in** on the login page and land on `/expert.html`, which lists pending and completed Agent 2 reviews. Experts can view pending assessments and associated member profiles, so configure only authorised reviewers. They cannot review themselves. Decisions are CLEARED_FOR_PLANNING, MODIFIED_AND_CLEARED, NOT_ELIGIBLE_FOR_AUTOMATION and REFER_TO_HEALTHCARE. Reviewer, time, rationale and additional constraints are retained. Decisions cannot be overwritten for the same assessment, and stale assessments cannot be cleared.

AI explanations require explicit profile consent and a user action. Only nutrient summaries, personal target ranges, coverage and assessment state are sent, plus the health goals and target rationale when the targets were AI-generated, so the explanation can say how the targets follow from the goals. Identity, photos, allergies, restrictions, conditions, preferences and expert rationale are excluded. Model instructions require English output, regardless of the input language. AI text does not participate in calculations, risk decisions or clearance. Failure leaves the rule-based report intact. Successful explanations are cached; revoking consent blocks later model calls but does not remove past explanations. Historical text is not automatically translated. Paid external model integration is not exercised by automated tests.

## API

All endpoints require `Authorization: Bearer <JWT>`. Member identity comes from the JWT, not a client-supplied member ID.

| Method | Path under `/api/v1/health` | Purpose |
|---|---|---|
| GET / PUT | `/profile` | Read current profile / save a version using expectedVersion |
| GET | `/assessments?page=1&size=10` | Own history, up to 50 per page |
| GET | `/assessment-coverage?periodStart=2026-10-14` | Own seven-day meal coverage before choosing to continue |
| POST | `/assessments` | Generate any completed seven-day period: `{"periodStart":"2026-10-14","allowIncomplete":true}`; flag defaults to false |
| GET | `/assessments/{id}` | Own report including stale indicator |
| POST | `/assessments/{id}/explanation` | Generate a consented Qwen explanation |
| GET | `/assessments/{id}/planning-input` | Agent 3 contract after version and clearance checks |
| GET | `/expert/access` | Whether the current account has expert access |
| GET | `/expert/queue?page=1&size=10` | Current-version review queue with `total`, experts only |
| GET | `/expert/reviewed?page=1&size=10` | Completed expert reviews (newest week first) with `total`, experts only |
| GET | `/expert/assessments/{id}` | Report and current profile for review, experts only |
| POST | `/expert/assessments/{id}/review` | `{decision,rationale,additionalConstraints}`, experts only |

Targets use USDA nutrient IDs: 1008 kcal; 1003/1004/1005/1079 g; 1093/1087/1089 mg. Each target is `{minimum, maximum, unit}`. The first four are required and the others optional. Enabling another target requires complete data for that nutrient before clearance.

The profile page omits `targets` and `targetSource` when saving. With AI consent, the
backend sends only the health goals to Qwen (`summary-model`) and stores the returned
energy, protein, fat and carbohydrate ranges with a short `targetRationale`. The call runs
outside the member lock and is repeated only when the goals change, consent is newly given,
or the previous targets were not AI-generated. A proposal is rejected when a range falls
outside sanity bounds (energy 1200–4000 kcal, protein 30–250 g, fat 20–180 g, carbohydrate
50–550 g), when a minimum is not below its maximum, or when the macronutrient midpoints
differ from the energy midpoint by more than 25%. A rejected proposal, a provider failure or
missing consent uses `nutrimarket.health.profile-defaults` in `application.yml` (energy
1800–2200 kcal, protein 70–130 g, fat 50–80 g and carbohydrate 200–300 g), and the target
source records why. Age, sex, height and weight are not collected, so AI targets are
moderate project ranges, not clinical prescriptions. Each assessment copies the target
source and rationale from its profile version.
API clients that supply explicit targets must also supply their source; the same
unit, range and required-nutrient checks continue to apply. Saved profiles always
contain resolved targets and source for Agents 2–5 and historical version tracking.

## Verification

Tests cover version conflicts, missing profiles, ownership, intake deduplication, week boundaries, missing nutrients, completion, concurrency, stale versions, expert permissions, self-review prevention, decision retention, AI consent, data minimisation and unchanged clearance. Tests use H2 and provider fixtures without actual accounts, meals or external model calls.

Rolling-period regression coverage includes arbitrary start dates, local date limits, daylight-saving boundaries, coverage preflight, explicit partial-data acceptance, observed-date averages, empty periods, missing nutrient safeguards, updates without duplicate reports, retained expert decisions and partial-report handoff to planning. Frontend checks cover missing-meal choices, cancellation, entry, request failures and timezone-aware defaults; local synthetic Chrome checks cover desktop and mobile layouts.

The original Agent 2 delivery passed 71 backend tests and packaging. Isolated browser checks covered profile saving, stale reports, reassessment, consent enforcement, expert submission and mobile layouts. An independent MySQL instance verified schema creation, repeated initialisation and migration 003 without touching the user database.

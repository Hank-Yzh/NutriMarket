# Agent 3: nutrition planning

Agent 3 implements the planning flow in NP-01–NP-09 / UC-03, using the existing Controller → Service → DAO structure. It accepts Agent 2's **completed, cleared assessment for the current member profile**, proposes a seven-day menu through Qwen, calculates nutrition from USDA source data, validates constraints and produces a versioned package for Agent 4.

## Use the application

1. Complete the health profile and a seven-day assessment. If meals are missing, choose to assess existing records; the partial coverage is carried to planning and does not require all 21 historical meals. Resolve any Agent 2 expert review first.
2. Open **Meal planning**, or select **Plan meals** on a cleared assessment. The start date defaults to tomorrow in the health profile timezone. Any date from today through the next 12 weeks is allowed; the plan covers that date and the following six dates.
3. Read and accept the Qwen processing consent. Generation sends constraints, nutrition comparisons, goals, preferences and ingredient nutrition data; account IDs and usernames are excluded from the model request.
4. Review the seven-day menu, daily target comparisons, ingredient quantities and USDA matches. Expand the evidence and history sections as needed.
5. A validated draft can be published after confirming source descriptions and ingredient forms. Meal plans have no expert review: human review happens on the source health assessment (Agent 2) and on shopping baskets (Agent 5). A plan that cannot pass every check pauses as `DEFERRED` with the reasons listed; the member can retry.
6. Published plans expose their immutable planning package and delivery state. A targeted replan specifies the affected meals, unavailable ingredients, reason and evidence. It invalidates the previous package immediately and requires a new publication.

Pages: `/planning.html`, `/plan.html?id=<UUID>`.

## First-pass generation and focused repair

The application supplies a fixed `mealTemplate` with keys `d0-BREAKFAST` through `d6-DINNER`. Qwen fills a keyed JSON object; day and slot are assigned by the decoder. A legacy array response remains readable but must pass the same validation. Missing keyed meals become explicit empty slots for correction, never invented food or nutrition.

The first request includes targeted per-100g nutrition, per-ingredient gram ceilings, a 1500 g meal ceiling and nutrient guidance allocated 30% to breakfast and 35% each to lunch/dinner. These shares guide an initial draft; only the existing daily target ranges are authoritative. Code still recomputes every total and can tune portions inside existing bounds.

Structural checks run before nutrient arithmetic, identifying exact duplicated/missing slots or meals exceeding eight ingredients. Malformed structure is repaired before reporting daily nutrient deficits. Subsequent requests return only failed meal slots (or all three slots of a day with failed targets). Valid days remain unchanged, including during portion tuning. Availability/preference replans never widen their permitted scope. Repeating the identical candidate and validation errors stops early with an explicit message rather than spending another identical model request.

USDA evidence is loaded with at most four concurrent planning lookups. Successful per-100g responses are cached in memory for 24 hours (at most 512 entries), keyed by provider and pinned food ID or normalized search. Concurrent identical lookups share the cached fetch. Failed lookups are not cached. Original USDA provenance timestamps are preserved; a cache hit is not represented as newly retrieved evidence. Restart clears the cache. No prices or stock use this nutrition cache.

## Automatic meal pipeline

When the health profile's **automatic meal planning** consent (`autoPlanningConsent`) is on, `MealPipeline` runs every `nutrimarket.pipeline.delay-ms` (10 s) for each member with a cleared assessment on the current profile version. It takes the member's latest cleared assessment, generates a plan starting tomorrow in the profile timezone (consent is the standing Qwen consent), and publishes a validated draft as `AUTO_PUBLISHED`. Publication then flows through the existing planning and market outboxes into market matching and a new shopping basket; the pipeline runs both outboxes right away to skip a polling interval. Each step is idempotent: an existing plan for the assessment is reused, and a plan is regenerated only when the assessment's revision changes (for example after partial records gain meals). A paused plan (`DEFERRED`) stays paused until the member retries it on the plan page. Nothing is ordered automatically; checkout still needs the member's confirmation in the basket.

`GET /api/v1/procurement/pipeline` reports the member's stage (`NO_PROFILE`, `NO_ASSESSMENT`, `CONSENT_REQUIRED`, `PLANNING`, `PLAN_PAUSED`, `MATCHING`, `BASKET_READY`) with the assessment, plan, market result and basket IDs. Set `nutrimarket.pipeline.enabled: false` to turn the scheduler off; tests do. `spring.task.scheduling.pool.size` is 4 so long model calls do not hold up the other schedulers.

## Configuration

Add or edit the existing section in `config/api-keys.yml` (do not duplicate root YAML keys):

```yaml
nutrimarket:
  planning:
    model: qwen-plus
    timeout: 120s
    market-endpoint: ""
    market-api-key: "${MARKET_AGENT_API_KEY:}"
    market-timeout: 10s
```

Qwen uses the existing `nutrimarket.integrations.vision.base-url` and `api-key`, while Agent 3 has its own text model. USDA uses the existing nutrition configuration. Changing Agent 3's model does not change Agent 1's vision model or Agent 2's explanation model. No additional API key is needed for Agent 3 if those providers are already configured.

`application.yml` sets a maximum of three model attempts per plan version and two automatic replans. All calls consume the attempt budget, including interrupted/provider attempts; repeated identical invalid output can pause earlier. Provider failures pause for a member retry; invalid numeric/ingredient candidates receive bounded automatic feedback. Exhaustion pauses the plan as `DEFERRED`; each member retry starts a fresh budget of three attempts. Once the replan limit is reached, further replans are refused with `REPLAN_LIMIT_REACHED`, the published plan stays active, and Agent 5 escalates the shopping basket to expert review. A generation lease expires after 20 minutes so a restarted service can recover interrupted work; late responses cannot overwrite the resumed version. External provider calls are outside database locks. Duplicate generation for the same member, assessment and week returns the existing plan.


## Validation and scope

- The catalog contains 37 explicit ingredient/preparation forms, covering Asian, Mediterranean, high-protein and simple Western meals, including seasonings (soy sauce, sesame oil, garlic, ginger, spring onion) with per-meal gram ceilings (oils and seasonings 20 g, olive oil 60 g, spring onion 50 g, feta 100 g). Qwen may choose only their IDs and gram quantities, plus an optional `dish` name (at most 80 characters) that must describe only the listed ingredients; without one, the title is built from the English catalog names. The member's meal styles (`mealStyles` on the health profile) are sent with their preferences, and the prompt asks for varied, cuisine-appropriate dishes rather than plain protein-and-vegetable plates. Pescatarian restrictions allow fish and shellfish. Every ingredient has synthetic products from at least two stores in `test-catalogue.json`, derived from the same pinned USDA values. USDA provides the source per-100-g data; the model's claimed totals are never accepted. Each ingredient is pinned to a verified USDA SR Legacy fdcId, because free-text search can return raw or dry forms (for example dry oats or raw rice) for cooked ingredients. Only the targeted nutrients are sent to the model.
- A plan must contain exactly 21 unique meals: seven days × breakfast/lunch/dinner. Each meal has 1–8 distinct ingredients, each 1–600 g (olive oil ≤60 g), with a maximum meal weight of 1500 g.
- Every day's calculated nutrient totals must fall inside **all user-supplied target ranges**. Validation uses unrounded decimal values. The UI rounds values for display. Missing nutrient values or incompatible units block publication; they are not converted to zero.
- When a proposal misses daily targets, the server first rescales the proposed portions toward target midpoints (each 0.25–4× the proposal, within portion and meal limits) and revalidates. Ingredients, meals and unaffected replan lines are not changed, and a `PORTIONS_ADJUSTED` audit event is recorded. Only issues that remain are returned to the model as feedback. Rescaling is skipped when any nutrient value is missing.
- Recognised allergens and vegan/vegetarian/pescatarian/free-from restrictions exclude catalog ingredients. Common phrasings such as `no seafood`, `nut-free` or `dairy allergy` are recognised (`seafood` covers fish and shellfish). Allergies or restrictions planning cannot interpret, and conditions outside the Agent 2 `routine-conditions` list, send the health assessment to Agent 2 expert review before planning; assessment-specific expert instructions from that review are passed to the model. Approximate USDA matches below 0.95 are listed as source notes for the member to check before publishing. This is not a clinical diagnosis or an automated interpretation of all possible restrictions.
- The finite catalog may not satisfy every target combination. Infeasible plans remain blocked rather than relaxing constraints. No synthetic nutrition fallback is used in production.
- Purchasing uses explicit retail ingredient names (`Rolled oats`, `Brown rice`, `Chicken breast`, etc.), independently of the pinned cooked USDA reference. Plans expose `purchaseIngredients` for their shopping totals; published requirement descriptions and catalogue/semantic searches use these names. Legacy plan reads and outbound deliveries adapt names without rewriting historical portions, nutrient evidence or outbox snapshots.
- Weights refer to the stated edible, ready-to-eat form. They do not represent raw purchasing weights, package sizes or cooking yields. No unlisted salt, oil, sauce or ingredient is included.
- Recipe-level checks do not certify SKU composition or cross-contact. Agent 4/5 must verify product-level evidence separately.

Each requirement allows the **same ingredient ID only** and the same edible quantity. Nutrient tolerances share each day's remaining target range (the distance from the day's total to the target minimum and maximum) in proportion to each ingredient's contribution, capped at `nutrimarket.planning.substitution-tolerance` (default 20%) of that contribution. Any combination of in-tolerance products therefore keeps every day inside all targets. Different ingredients require a replan and validation of the entire week's daily targets. No unverified alternative category is treated as nutritionally equivalent.

## HTTP contract

All routes require the existing Bearer JWT. Member identity always comes from the JWT. Unknown or other users' plan IDs return 404.

Base: `/api/v1/planning`.

| Method | Route | Request / result |
|---|---|---|
| GET | `/catalog` | Ingredient IDs, names, preparation queries and recognised allergens |
| GET | `/plans?page=1&size=10` | Member's plans, newest planning weeks first |
| POST | `/plans` | `{assessmentId, weekStart, aiConsent: true}` |
| GET | `/plans/{id}` | Current plan, freshness, checks, evidence and delivery state |
| POST | `/plans/{id}/retry` | `{expectedVersion}`; only deferred generations with budget remaining |
| POST | `/plans/{id}/publish` | `{expectedVersion, confirmSources: true}` |
| GET | `/plans/{id}/package` | Current published package, freshly checked |
| POST | `/plans/{id}/replan` | Request shown below |

Example targeted request:

```json
{
  "requestId": "7d66f883-90b0-4b98-9015-bb00c85c8296",
  "expectedVersion": 1,
  "reason": "AVAILABILITY",
  "evidence": "The supplier cannot provide brown rice for the first breakfast.",
  "menuLineIds": ["d0-BREAKFAST"],
  "unavailableIngredientIds": ["rice"],
  "aiConsent": true,
  "planId": "optional; must match the plan in the URL",
  "attempt": 1
}
```

Reasons: `AVAILABILITY`, `PRICE`, `PACKAGE_SIZE`, `ELIGIBILITY`, `PREFERENCE` (the member marked a food as disliked in a shopping basket). Evidence is member-supplied and audited; it is not represented as independently verified supplier evidence. The prototype exposes this through the member-authenticated endpoint. It does not grant an unauthenticated Procurement Agent write access. Reusing a request ID with identical content is idempotent; different content returns a conflict. The optional `attempt` must equal the number of replan requests already recorded for the plan plus one, and is stored in the replan audit.

The server merges only requested meal lines. All other meals remain unchanged. Unavailable ingredients are prohibited on the lines to which their evidence applies, including after later revisions. Generation still revalidates the whole plan.

## Persistence, freshness and Agent 4

`nutrition_plan` stores the current versioned document and indexed ownership/assessment/week/state. `planning_outbox` stores immutable published packages keyed by `(plan_id, plan_version)`. New tables are in `schema.sql` and repeatable migration `004_nutrition_planning.sql`. No existing business data is rewritten. Fresh databases apply the current schema; existing UUID/Agent 2 schemas can run migration 004, or let startup schema initialisation create the missing tables.

A `PlanningPackage` contains: weekly meals, per-menu-line ingredient requirements, substitution groups, nutrient targets, health constraints, daily nutrition, USDA sources, assessment ID/revision, profile version and policy version. It remains traceable to its cleared assessment.

**Disliked foods.** `GET /api/v1/planning/disliked-foods` lists the foods a member does not want; `PUT` and `DELETE /api/v1/planning/disliked-foods/{ingredientId}` add or remove one (catalogue IDs only). They are added from the health profile page or a basket's **Don't like this food**. New plans add them to the plan's excluded ingredients, so they are not offered to the planner and are never market substitutes. They are a preference, not a health constraint, and are stored apart from the versioned profile (`member_food_dislike`), so a change never invalidates the current assessment or plan.

Profile changes, changed/withdrawn assessment clearance, policy changes or a completed planning week make a plan stale. Reads, publication, package retrieval and delivery recheck freshness. Previous publication versions are cancelled on replan. Historical JSON and published payloads are retained for audit.

Adding meals to an unreviewed partial assessment changes its revision and invalidates the old plan package. With fresh member consent, generating for the same assessment and planning week creates a new version of that plan, recalculated and validated from the updated evidence. History, ingredient exclusions and replan limits are retained.

Agent 4 is now implemented locally. With a blank `market-endpoint`, the planning worker hands published packages to MarketService. Members can also open Market matching to start immediately. See [Agent 4](MARKET_AGENT.md). When a trusted external endpoint is configured, the worker posts the package with `Idempotency-Key: <planId>:<version>` and optional Bearer `market-api-key`. It uses bounded retries, accepts 2xx acknowledgements, does not follow redirects, and shows terminal delivery failure after the budget is exhausted.

Delivery is **at least once**, not exactly once. A recipient must deduplicate the idempotency key, reject obsolete versions and revalidate the current published version through the authenticated package endpoint immediately before ordering. A profile change can occur after HTTP delivery; receipt is not permanent authorisation to order. Machine-to-machine read authentication for a future Agent 4 must be designed with that agent; the current read endpoint uses the owner's JWT.

## Verification

Backend tests use isolated H2 databases, mock nutrition/model providers and local HTTP receivers. They cover targets, hard constraints, member isolation, consent, bounded retries, partial replans, concurrency, stale results and outbox delivery. They do not spend API credits or mutate the configured MySQL database. Synthetic nutrition fixtures are test-only; live provider suitability and MySQL deployment need verification in the target environment.

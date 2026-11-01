# Agent 5: Procurement and Member Experience

Agent 5 implements UC-06 and PM-01 to PM-10 using Controller → Service → DAO, MyBatis and MySQL. Orders go to **simulated partner stores** (`SimulatedRetailerClient`). No real supermarket order API, payment or stock reservation exists yet; that choice is still open. The stores accept every order by default. Configuration can make a store reject an order or fulfil it partially, so the UC-06 A4 flows can be demonstrated.

## Member workflow (UC-06 screens)

1. Publish an Agent 3 meal plan and complete Agent 4 matching. The Agent 4 outbox delivers in process when its procurement endpoint is blank, or the member opens **Shopping baskets** (`/procurement.html`). There is one persistent basket per market result. With standing automatic-planning consent, the pipeline resumes stale or interrupted matching when no basket exists, within Agent 4's existing refresh limit; successful refreshed results return to the outbox for normal basket creation. It does not regenerate the plan, change meal quantities or bypass evidence freshness. Exhausted refreshes and failed handoffs are shown as paused states, with recovery links, instead of indefinite preparation.
2. **Screen 1, basket review** (`/basket.html`):
   - **Locked health constraints**: allergies, dietary restrictions, conditions, daily nutrition limits and expert decisions are read-only (PM-07).
   - **Basket items**, by product or by meal. By product is a compact list: one two-line row per product with name and brand, store, range tier, quantity × subtotal and an Edit button, so a switch between Economy, Balanced and Premium can be compared at a glance. A substitution marker shows "Originally X → now Y (same substitution group)" inside the row when a product was replaced or the planner's group allowed another ingredient (PM-03). Clicking a row expands everything else: category, SKU, package size, unit price, price per pack, needed and surplus amounts, update time, availability, promotion, pantry use, the constraint evidence with every check and what it was based on (PM-02), the meals covered, ingredients and allergens, and nutrition per 100 g. Nothing is removed; it is only folded. The order page and expert views keep the full product cards.
   - **Edit basket & compare options** opens one editor for products, budget, price-quality preference, fulfilment, exclusions and saved stores. Each product also has an Edit shortcut into this same editor.
     - **Don't like this product** removes that product and permits a compliant replacement for the same food. Later baskets keep it out as a learned SKU exclusion (PM-09).
     - **Don't like this food** remembers the product's food as disliked (an Agent 3 preference, not a health constraint) and stops it being bought as a substitute. If this plan has meals that use it, Agent 5 sends a `PREFERENCE` `ReplanRequest` for exactly those meal lines, after the member agrees to send the request to Qwen. The basket then waits for the revised plan, which the member publishes and Agent 4 matches again, so menu, product matching and basket stay consistent. Later plans leave the food out. A preference is not a supply failure: when the plan has no replans left, or is no longer the published version, the meals stay as they are and nothing is escalated to an expert. Disliked foods are listed, and can be removed, on the health profile page.
     - **Don't need** omits the ingredient from this purchase without adding a replacement. It does not rewrite the meal plan, assert food consumption or create pantry stock. The omitted ingredients are shown on the saved basket. Nutrition substitution checks include the omitted ingredient's original USDA plan portion; the basket's nutrition check is labelled CONTEXT rather than claiming complete shopping coverage. Allergy and product eligibility checks on bought items stay enforced.
     - **Update comparison** previews all six price-quality/fulfilment pairs against the same evidence, budget and edits. It does not save, order or call AI. Cards show product cost, supermarket fees, total, stores, package counts, recorded range tiers and whether the result matches Economy. The selected card's store and product breakdowns are expandable.
     - **Cancel** discards the draft. **Save and recalculate** applies every edit atomically, rebuilds the basket and clears checkout confirmation. If the catalogue evidence went stale after the basket was built (for example the 12-hour catalogue re-stamp), saving first re-checks prices and stock with Agent 4, as checkout does, and a version bump caused only by that invalidation does not reject the edit. Progress and errors appear beside the Save button.
   - **Budget summary** shows products, per-supermarket fees and totals, budget and remaining or over-budget amount. Unquoted delivery fees never appear as zero or as a complete total.
3. **Confirm and place order** starts **screen 2**. The server requests fresh evidence from Agent 4, reruns classification and every hard-constraint check, and rebuilds the basket (UC-06 step 7, PM-08). Each click carries an idempotency key, so a repeated request acts once.
4. If nothing material changed, the reviewed basket is submitted directly. Otherwise **screen 3** lists each change (old → new price, SKU replacements, stock, fees, total). **Confirm and submit** orders the refreshed basket; **Cancel** declines the changes and places no order (UC-06 failure condition). A line that becomes unbuyable blocks the basket and follows A1.
5. **Screen 4** (`/order.html`) shows:
   - the order number, each store's status, reference and pick-up or delivery slot, and accepted and rejected items;
   - the linked plan, market revision, selected SKUs, preference signals, and promotion and price-quality decisions.

   `/orders.html` lists every order.

The confirmation window defaults to five minutes. Before ordering, the server checks again: source revision, published plan, profile, assessment clearance, evidence freshness, product versions, policy version, daily targets, budget and every selected SKU. Checkout refreshes do not use Agent 4's manual refresh budget. Each basket allows `max-checkouts` (default 20) of them, and an expert's `RETURN_FOR_ADJUSTMENT` resets the count. Member-row locks serialise mutations. Model calls and store calls run outside those locks.

## Selection rules (PM-04 order)

Only Agent 4 candidates already marked eligible and COMPLIANT are considered, and Agent 5 reclassifies them against current evidence. UNKNOWN and NON_COMPLIANT products can never be enabled by preferences, exclusions or expert review. The search is deterministic and bounded by `search-width`; it does not use a model. It applies these rules in order:

1. **Safety and nutrition.** Per-requirement tolerances and the original meal plan's daily targets must hold, including original reference portions for ingredients omitted from this purchase. A partial basket is explicitly labelled as such.
2. **Budget and stated preferences.** Budget and quoted fees come first, then the lowest total; saved stores break equal-cost ties. The price-quality preference is:
   - **Economy** chooses the lowest total for the same remaining meal quantities; it does not add food to spend the budget. Promotion labels cannot increase its total.
   - **Balanced** buys higher recorded range tiers while the total stays within `balanced-margin` (default 15%) of the lowest-cost basket.
   - **Premium** buys the highest recorded tiers within the budget.

   Range tiers (`BASIC`, `STANDARD`, `PREMIUM`) come from the catalogue's `qualityTier` field and score 0, 1 and 2 per requirement. Products without a tier score 0. When no candidate has a tier, every mode behaves like Economy.
3. **Fulfilment.** Delivery uses one supermarket, comparing product cost plus its quoted delivery fee. Pick-up can combine supermarkets when comparable evidence is available and includes each collection fee. Unquoted delivery fees block ordering and total comparisons; ties prefer fewer stores. Home delivery needs the member's saved **delivery address** (`GET`/`PUT /api/v1/procurement/address`, table `member_delivery_address`), entered on the profile page or in the basket editor when Delivery is chosen. It is kept apart from the health profile, never sent to Qwen, experts or store reports, and is copied into the order (`deliveryAddress`) and each store order's delivery slot when the order is placed; later edits do not change placed orders. Each partner store's street address (`nutrimarket.procurement.store-addresses` in `application.yml`; the three synthetic stores are in Sydney: FreshMart Central, 200 George Street, Sydney NSW 2000; GreenLeaf Grocer, 45 King Street, Newtown NSW 2042; ValueBasket Express, 12 Church Street, Parramatta NSW 2150) is shown under each store in the basket summary and written into pick-up and delivery slots.
4. **Commercial interest.** Economy only uses promotion labels at equal total cost. Other preferences may use promotion labels to break near-ties without exceeding Balanced's spending cap or Premium's budget. A second bounded search may choose more promoted value within `promotion-tie-margin`, but only if the basket is no worse on budget, stores, fee quotes, store count and range tiers. The decision is recorded as `promotionDecision`.

Identical products are pooled across meals before package rounding; surplus is shown separately. Multi-store combinations are allowed only when Agent 4 reports comparable multi-store data (PM-05). Currencies are never mixed. Quality ratings, stock quantities and discounts are never invented.

**Exclusions (PM-07).** Members exclude by brand or SKU. A request is rejected with HTTP 422 `EXCLUSION_REJECTED` and the reason when:
- it names a hard constraint: an allergy, dietary restriction, health condition or daily-target nutrient such as "sodium" (a health red line);
- it matches no product in the basket's evidence.

Exclusions can only narrow the choice. An explicit exclusion that removes every compliant product blocks that meal and names the exclusion.

**Preference learning (PM-09).** A new basket learns soft defaults from the member's most recent accepted basket (budget, price-quality preference, fulfilment, saved stores, exclusions). Products marked **Don't like this product** in any of the last 20 baskets are added as learned SKU exclusions. Without one, it learns from the last saved preferences, and the budget stays zero. Learned exclusions are marked `learned` and never block a meal: if one would leave a meal without a product, it is skipped for that meal. Nothing learned becomes a hard constraint, and AI replanning consent is never learned.

**First draft (UC-05 step 5).** A new basket opens on a complete draft. If the learned defaults leave meals unfilled or over budget, every fulfilment mode and price-quality preference is evaluated on the same evidence; the member's own preference and mode win ties, then the lower total (for example, pick-up across stores when no single supermarket can deliver everything). A budget that was never set becomes that basket's cost rounded up to the next 5 and is labelled as suggested; a budget the member set is never raised. `defaultChoice` records why the draft differs from the defaults. Untouched blocked baskets created earlier get the same treatment once, when first opened.

**Budget too low.** No constraint is relaxed silently: an over-budget basket stays blocked, and `minimumTotal`/`minimumChoice` report the lowest complete total for the same meals (Economy, best fulfilment mode). The basket page shows it and can prefill it as the budget. Whether a budget below that minimum should lead to a ReplanRequest is still open.

**Editor and order bar.** The editor has two layers saved together: ① basket settings (budget, price-quality preference, fulfilment, exclusions and the option comparison), which rebuild the whole basket while health and allergy constraints stay locked; ② product choices (Keep / Don't like / Don't need), applied inside the rebuilt basket. The **Confirm and place order** button sits in a bar fixed to the viewport with the basket total.

## Exception flows

| Flow | Behaviour |
|---|---|
| A1 meal unbuyable | Complete, current evidence with no eligible product allows a bounded `ReplanRequest` to Agent 3 with a reason code (`AVAILABILITY` or `ELIGIBILITY`), market evidence, `planId` and `attempt`. It is sent automatically with the standing `autoReplan` consent (PM-06), otherwise after the member's explicit request. The basket shows "Replanning the affected meals…". When Agent 3's replan limit is reached, it escalates the plan to a Human Expert and the basket shows that escalation. Failed, stale or truncated searches never prove unavailability. |
| A2 controls changed | **Save and recalculate** applies all draft edits and rebuilds the basket. Hard constraints are unchanged. |
| A3 price or stock changed | The submission refresh rebuilds the basket. Material changes need re-confirmation on screen 3. |
| A4 rejected or partial order | The result is recorded per store and line, and the order cannot be submitted twice. **Recovery** resubmits only rejected lines to the same store, up to `retailer.max-attempts` per store order. **Re-optimisation** cancels the accepted store orders and rebuilds the whole basket with the rejected SKUs excluded. The replacements are marked, and the member submits again into the same order (`submission` + 1, earlier submissions in `history`). **Cancel** cancels every store order. |

No order is placed when safety evidence is UNKNOWN, replanning is exhausted, the member declines changes, or fulfilment cannot be recovered.

Members may request procurement review. Experts listed in `nutrimarket.health.expert-usernames` open `/procurement-expert.html` and either return a case for fresh validation or terminate it, with an audited rationale. Self-review is forbidden. No expert action orders a basket or waives constraints.

## Configuration and schema

Settings are under `nutrimarket.procurement` in `config/api-keys.yml`:

```yaml
procurement:
  policy-version: procurement-v4
  search-width: 128
  checkout-minutes: 5
  minimum-cohort: 5
  max-checkouts: 20
  promotion-tie-margin: 0.02
  checkout-evidence-age: 24h
  balanced-margin: 0.15
  store-fees:
    - {store: "Store A", currency: AUD, delivery: 8.00, pickup: 0}
  report-stores: {}
  retailer:
    max-attempts: 3
    simulated-outcomes: []
    #  - {store: "GreenLeaf Grocer", outcome: REJECT, reason: "Store system unavailable", first-attempt-only: true}
    #  - {store: "ValueBasket Express", outcome: PARTIAL, skus: [VB-TOFU-300], reason: "Out of stock at picking"}
```

`simulated-outcomes` drive the A4 demonstration:
- `REJECT` rejects a store's whole order.
- `PARTIAL` rejects only the listed SKUs.
- `first-attempt-only: true` makes the failure transient, so Recovery succeeds.

Bump `policy-version` when selection rules change; pending confirmations are then invalidated. No Agent 5 API key is needed.

The synthetic test catalogue records range tiers by brand: Value Basket is `BASIC`, Harbour Fresh is `STANDARD`, and Green Leaf Organics is `PREMIUM`. Operators can set the tier on `/catalogue.html`.

Startup `schema.sql` creates `shopping_basket` and `procurement_order`, and no new tables are needed. A basket keeps one order row, which holds store orders, submissions and audit events in its JSON document. Orders and baskets saved by earlier versions remain readable. Old preferences are read as Economy, and `LOCAL_CONFIRMED` orders stay local confirmations.

## Store reports

`/procurement-reports.html` is available only to usernames assigned one store in `report-stores`, and the server checks that assignment. The demo configuration assigns `expert` and `demo_expert` to `FreshMart Central`, and the expert sidebar links to **Store demand report**. Product cohorts with fewer than `minimum-cohort` (5) members are suppressed, so a small demo shows a suppressed report until five members have accepted orders for the same SKU. A report counts only lines the simulated stores accepted; cancelled orders and rejected lines are excluded. Store and SKU cohorts each need at least `minimum-cohort` distinct members. No member IDs or health data are exposed.

## HTTP API

All routes under `/api/v1/procurement` require JWT authentication. Member routes enforce ownership and return 404 for another member's IDs.

| Method | Route | Action |
|---|---|---|
| GET | `/access` | Expert and report capabilities, `SIMULATED_RETAILER` mode, `maxStoreAttempts` |
| POST | `/baskets` | Start or open a basket from `{resultId}` |
| GET | `/baskets?page=1&size=10` | Member baskets |
| GET | `/baskets/{id}` | Basket, evaluation, `materialChanges`, `learnedSignals` and `replanStatus` |
| PUT | `/baskets/{id}/preferences` | `{expectedVersion, settings}`; re-optimises the whole basket |
| POST | `/baskets/{id}/submit` | `{expectedVersion, requestId}`: refresh, revalidate and order directly when nothing material changed. Returns `{basket, order}`, where `order` is null when re-confirmation is needed |
| POST | `/baskets/{id}/checkout` | `{expectedVersion, requestId}`: refresh only |
| POST | `/baskets/{id}/confirm` | `{expectedVersion, requestId, confirmOrder:true}` re-confirms listed changes and orders (`confirmLocalOrder` is accepted as an alias) |
| POST | `/baskets/{id}/decline` | `{expectedVersion}` declines the changes; no order |
| POST | `/baskets/{id}/replan` | `{expectedVersion, requestId, aiConsent:true}` |
| POST | `/baskets/{id}/review-request` | `{expectedVersion}` |
| GET | `/orders?page=1&size=10` | Member orders |
| GET | `/orders/{id}` | Order with store orders, fulfilment slots, links and history |
| POST | `/orders/{id}/recover` | `{requestId}` resubmits rejected lines |
| POST | `/orders/{id}/reoptimise` | `{requestId}` cancels accepted store orders and reopens the basket without rejected SKUs |
| POST | `/orders/{id}/cancel` | Idempotent cancellation of every store order |
| GET | `/expert/queue` | Paged expert queue |
| GET | `/expert/baskets/{id}` | Authorised review detail |
| POST | `/expert/baskets/{id}/review` | `{expectedVersion, decision, rationale}` |
| GET | `/reports` | Assigned-store, cohort-suppressed accepted demand |

`settings` holds:
- `budget` and `currency`;
- `preference`: `ECONOMY`, `BALANCED` or `PREMIUM`;
- `fulfilment`: `PICKUP` or `DELIVERY`;
- `preferredStores` and `excludedProductIds`;
- `exclusions`: a list of `{type: BRAND|SKU, value, learned}`;
- the optional `autoReplan` flag.

Each evaluation line carries `checks` (`{constraint, result, evidence}`), and each allocation carries `plannedIngredientId` and `substitutionGroupId`.

Order statuses are `SUBMITTING`, `CONFIRMED`, `PARTIALLY_FULFILLED`, `REJECTED`, `REOPTIMISING` and `CANCELLED`. Store order statuses are `PENDING`, `ACCEPTED`, `PARTIAL`, `REJECTED` and `CANCELLED`.

## Unified editor and comparison API

`POST /api/v1/procurement/baskets/{id}/compare` takes the same `{expectedVersion, settings}` body as preference updates and returns six `BasketOption` quotes. Each includes available status, product total, fee total, complete total (null if a fee is unknown), per-store breakdown, selected SKUs/package quantities/range tiers, issues and bounded-search status. Preview does not change the basket version, settings, checkout state or audit. Save still checks the expected version and current source.

Settings may include `itemEdits: [{productId, choice: "DISLIKE" | "DISLIKE_FOOD" | "NOT_NEEDED"}]`. Ingredient scope is resolved by the server from the current or previously edited basket line (for `DISLIKE_FOOD`, the product's own food); any supplied ingredient list is ignored. `PUT /baskets/{id}/preferences` also takes `aiConsent`, required only when a newly disliked food appears in the plan's meals (`AI_CONSENT_REQUIRED` otherwise). Edits persist through checkout refresh and order recovery, while NOT_NEEDED applies only to this basket. Removing all ingredients leaves nothing to buy and cannot create an empty order.

Delivery selects one supermarket and includes its configured delivery fee. Pick-up may combine supermarkets when market evidence supports comparable stores and includes every configured collection fee. Missing delivery quotes block ordering. Personal travel costs are not estimated. Product ranges are catalogue evidence, not an inferred nutritional quality score. Current policy version is `procurement-v4`.

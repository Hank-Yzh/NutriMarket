# Agent 4: Market Intelligence

Agent 4 implements MI-01–MI-09 / UC-04 using Controller → Service → DAO. It receives the immutable published Agent 3 package, matches requirements to factual supermarket products, classifies their evidence and creates a versioned `ProcurementInputPackage` for Agent 5. It does not select the final SKU, optimise a basket or place an order.

## Local catalogue setup

The default source is the **operator-maintained local catalogue**, as selected for this project. No sample prices, availability or product data are inserted into the real database.

Edit the existing `nutrimarket.market` section in `config/api-keys.yml`:

```yaml
nutrimarket:
  market:
    source: LOCAL
    operator-usernames: [your_registered_operator_username]
    semantic-enabled: true
    model: qwen-plus
    model-timeout: 30s
    catalogue-endpoint: ""
    catalogue-api-key: "${CATALOGUE_API_KEY:}"
    procurement-endpoint: ""
    procurement-api-key: "${PROCUREMENT_AGENT_API_KEY:}"
```

Replace the example operator username with an actual registered account, then restart. The shipped allowlist is empty: ordinary members and the default `admin` account do not receive catalogue-editing privileges automatically. To intentionally use the existing admin account for catalogue maintenance, set `operator-usernames: [admin]`. This operator permission is separate from Agent 2/3's expert-review permission.

Qwen uses the existing vision integration's regional base URL and API key, with an independent text model setting. No extra Qwen credential is required. The model receives ingredient descriptions and supplied product IDs/names/brands/categories/preparation forms, not member identity, health profile, prices, stock, nutritional values or source-maintainer identity. It returns semantic confidence only. Missing keys or provider failure are explicit `MODEL_FAILURE` outcomes. An operator may explicitly set `semantic-enabled: false` to use verified catalogue ingredient mappings without a model; results identify that matching method.

`application.yml` configures a 24-hour maximum evidence age, three bounded refreshes per market result, up to 20 candidates per search group and a 0.85 confidence threshold. Increment `policy-version` when changing matching rules that should invalidate previous results. These are configurable project policies, not a guarantee that a retailer's stock is still available. Future order submission must request current market data and recheck the active result.

## Pages and workflow

- `/catalogue.html`: view product evidence. Allowlisted operators can add, edit and disable products. Every edit uses an expected version and creates an immutable history row. Source observation timestamps are entered explicitly; an edit does not silently assert that old facts were observed again.
- `/market.html`: choose a current published plan and run matching. The previous Your market results history list has been removed; no result-history refresh or pagination controls are displayed.
- `/market-result.html?id=<UUID>`: a read-only comparison page. It summarises how many ingredients are matched, covered by substitutes or not found; shows each supermarket's coverage; lists, per ingredient, every eligible product from every supermarket with pack size, packs needed, whole-package cost, a cost bar and its matching evidence (SKU, confidence, method, constraint status, source and timestamp); lists substitute offers for planned foods with no eligible product; and lists ingredients with no product and why. The only action is **Refresh**, which reloads, re-matches the whole week when the result is out of date or interrupted, and polls while matching runs. Agent 4 makes no choices here; Agent 5 decides the basket.

The existing Agent 3 delivery worker now hands published packages to the in-process Agent 4 when `nutrimarket.planning.market-endpoint` is blank. A manually started match also acknowledges the corresponding planning outbox. Repeated delivery of the same plan version returns the existing market result; it does not perform duplicate matching. A configured external Agent 4 endpoint retains the existing HTTP delivery behaviour.

Local products record source identifier/reference, SKU, name, brand, category, verified planning ingredient mapping, preparation, store, currency, package weight, price, promotion, stock state, observation time, ingredient composition, allergens, may-contain evidence, verified dietary claims and per-100-g nutrition. Missing price, package weight or nutrients remain unknown. Do not enter zero as a substitute for missing evidence. Nutrition is entered per 100 g as sold; energy may be entered in kcal or kJ (converted at 4.184 kJ/kcal), and g/mg/µg values are converted to the planner's units.

Agent 3 supplies retail shopping descriptions such as `Brown rice` and `Chicken breast`, rather than cooked meal or USDA names. Agent 4 also normalises descriptions when refreshing legacy results. Ingredient IDs remain unchanged, and the nutrition reference remains specific to the planned preparation. Prepared/cooked weights from Agent 3 cannot be compared with raw product weights without verified yield information. A RAW product may record a sourced cooked-yield factor (edible grams per gram as sold); Agent 4 then converts the required edible quantity to grams as sold for nutrient and package calculations. RAW products without a yield factor and UNKNOWN forms remain UNKNOWN; the system does not invent cooking yields. Products are mapped to the finite Agent 3 ingredient catalog. No retailer-specific scraping or undocumented supermarket API is assumed.

## Weekly price comparison

Every market result includes a derived `priceComparison` (shown first on `/market-result.html`). Requirements are grouped by ingredient across the whole week, and each candidate is priced by **actual purchase cost**: whole packages for the week's total quantity as sold (raw products via their yield), so 1.5 kg from 1 kg packs is two packs, with the surplus shown separately. Each option reports name and SKU, price and unit price per kg, package size, packages to buy, weekly cost, constraint status and dietary claims, availability, and price source with observation time. A product counts as a weekly option only if it is eligible for every meal that uses the ingredient. Supermarket totals add each store's cheapest eligible option per ingredient and list ingredients the store cannot supply rather than pricing them. The comparison is informational; users choose the final products to order in their shopping basket. The page shows matched ingredients in a compact table; candidate product disclosures are available only for unmatched ingredients.

## Synthetic test catalogue

No open dataset combines Australian supermarket prices, stock and store with complete ingredient, allergen and nutrition labels: retailer price data is only available through commercial scrapers or vendors, and Open Food Facts has labels but no prices or stock. For testing and demonstrations, `src/main/resources/test-catalogue.json` holds 46 **fictional** products for the 14 planning ingredients across three fictional stores (FreshMart Central, GreenLeaf Grocer, ValueBasket Express). Every product's `sourceReference` states that it is a synthetic fixture. Labels use kJ and are derived from the pinned USDA entries (raw products via a stated edible yield) within ±3%, so they pass planner tolerances. Deliberate edge cases: canned lentils and Greek-style yoghurt (outside tolerance), an out-of-stock egg pack, a spinach bunch without complete allergen evidence, and oats that may contain peanut, tree nuts and sesame.

Set `nutrimarket.market.test-catalogue: true` to load it at startup through the same validation and versioned history as operator edits. Because evidence older than `max-age` (24 h) is stale, products observed more than 12 hours earlier are re-stamped at startup, which creates new product versions and makes older market results stale until refreshed. Matching fees for these stores can be configured under `nutrimarket.procurement.store-fees`. Each fixture also records a fictional range tier (`qualityTier`): Value Basket is `BASIC`, Harbour Fresh is `STANDARD` and Green Leaf Organics is `PREMIUM`. Agent 5's Balanced and Premium preferences use these tiers. `qualityTier` is optional catalogue evidence that operators set on `/catalogue.html`; when it is missing, the product is treated as unrated and never assumed to be premium. Never enable the test catalogue with real retailer data.

Profiles with a condition outside the routine list keep every product UNKNOWN for autonomous procurement, even after expert plan approval, because such a condition cannot be checked against a SKU automatically. Routine conditions do not block products.

## Matching and constraints

The immutable input must have consistent identifiers, current plan/profile/assessment versions, all 21 original meals, consistent requirement quantities and matching substitution groups. Invalid packages are rejected before catalogue or model calls.

- Catalogue data remains authoritative. The model cannot supply a new product ID, alter facts or override safety checks. It scores only whether each product is the requested food: cooking state and varieties (red or brown lentils, Cavendish or Lady Finger bananas) match, while a different cut, part or product form (mince versus steak, thigh versus breast, juice versus whole fruit, flavoured or strained yoghurt) scores below the threshold and is LOW_CONFIDENCE. Preparation, yield, nutrition and allergens are checked by deterministic rules.
- Each candidate has `COMPLIANT`, `NON_COMPLIANT` or `UNKNOWN` hard-constraint status, semantic confidence, provenance and source time. Eligibility additionally requires fresh data, sufficient confidence, in-stock availability and complete price/package/store information.
- Declared allergens and may-contain statements are checked against profile exclusions. Incomplete ingredient/allergen evidence remains UNKNOWN. Recognised ingredient restrictions are enforced; verified product dietary claims are required. Uninterpreted free-text allergies, conditions outside the routine list or assessment instructions remain UNKNOWN for autonomous procurement.
- Product nutrient contributions at the required edible quantity must satisfy each original substitution tolerance. Agent 3's tolerances share each day's remaining target range, so products close to the planned USDA values pass while materially different ones are blocked. Agent 4 never widens these tolerances. A planning change belongs to the planner's replan workflow.
- All candidates are retained, including blocked or unknown ones. Outcomes include `MATCHED`, `UNMATCHED`, `UNAVAILABLE`, `LOW_CONFIDENCE`, `INCOMPLETE`, `STALE`, `CONSTRAINT_BLOCKED`, `SOURCE_FAILURE` and `MODEL_FAILURE`. Mixed candidate failure types produce an INCOMPLETE requirement summary with individual reasons preserved.
- Multi-store capability is true only when eligible evidence spans multiple stores in one currency. No exchange rate or shipping cost is fabricated.
- `packagesToCover` and `costToCover` are comparisons for one requirement, not a shopping basket. Agent 5 must aggregate shared ingredients, select products and handle surplus, budget, delivery and confirmation.

Local source facts are operator attestations backed by the entered evidence reference. The app does not independently certify a retail label or allergen statement. Qwen confidence is semantic similarity, not a safety certificate.

### Ingredient-list check (policy market-v3)

Matching is two steps. First, products are found by their catalogue ingredient mapping and scored on name similarity. Second, the product's ingredient list must confirm it really is that food: the list must mention the food, the first entry must be that food (only a listed carrier such as water for tofu and soy sauce may come first), and for single foods any stated share must be at least 90% (50% for canned lentils and chickpeas). A product named after a food but made mostly of something else, such as apple lollies, rice pudding, tomato sauce or chicken-flavour noodles, is constraint-blocked with the reason. The synthetic catalogue includes these decoys.

Allergies or dietary restrictions that name a catalog food (for example `apple` or `no apples`) exclude that food: planning never uses it, and any product whose ingredient list contains it is blocked. They no longer require an expert or a dietary label claim. Words shared by several foods (such as `milk`) stay unrecognised and go to expert review.

### Substitutes from the meal plan

When a plan is published, each substitution group lists up to three planner-approved substitutes (`substitutes`): culinary alternatives such as white rice or quinoa for brown rice, or sweet potato for potato. Each uses an energy-matched edible amount and is kept only if swapping it in alone keeps that day within every nutrition target and it passes the member's allergies, restrictions and earlier replan exclusions; its tolerances are computed for the swapped day. Agent 4 checks the planned food and every planner-approved substitute (name match, ingredient-list check, SKU allergens, dietary restrictions, the substitute's own amount and tolerances, and evidence completeness), even when the planned food is available, and keeps all candidates with their evidence in `marketIntelligenceResult`. Belonging to a substitution group never makes a product compliant by itself. `checkedSubstitutes` on each requirement result lists the substitutes actually checked; a missing value means the result predates this check, which the page shows as "not checked" rather than "none found". With no eligible candidate, the outcome still describes the planned food; products whose ingredient list shows they are not that food (salmon crackers for salmon) are left out of that summary, so out-of-stock salmon fillets read as `UNAVAILABLE`. Agent 4 selects nothing: Agent 5 uses substitute candidates only for requirements whose planned food has no usable product, buys them in their own amount, and the basket shows "Planned X → now Y". Store coverage counts the planned food. On `/market-result.html`, each ingredient card keeps its original product comparison (labelled "Same ingredient") and adds a collapsible **Planner-approved Substitutions** section only when the plan defines substitutes for that ingredient; it opens automatically when the planned food is unavailable and an eligible substitute exists. Plans published before this change have no substitutes and match as before.

The **Refresh** button re-matches stale, interrupted or incomplete results when relevant local catalogue evidence has changed. Repeated clicks against unchanged data wait without calling the model or consuming retries. Normal completed results can still be explicitly refreshed. Browser reloads only fetch the current state.

## Refresh, invalidation and recovery

Market results remain keyed by published plan/version. `refreshCount` is a lifetime audit total, not a lock or daily allowance. Old results that already reached three refreshes recover without database resets or a new assessment. No operator approval is required.

Local catalogue recovery uses a fingerprint of the plan's ingredients and approved substitutes, candidate product IDs/versions, freshness and matching policy. Unrelated catalogue edits do not trigger retries. Old timestamps are never renewed by matching. If only stale evidence is available, results wait for actual catalogue updates. An expired rejected alternative does not invalidate fresh eligible offers. On invalidation, fresh candidates remain visible, but the old procurement package is cancelled until revalidation completes. Expired or changed products cannot be used for checkout.

Technical failures use persisted backoff: 30 seconds, 2 minutes and 10 minutes after the first three consecutive failed runs. With the default `max-refreshes: 3`, later failures retry at 30-minute intervals rather than permanently locking the menu. Both manual and automatic requests respect this schedule. Successful matching clears the failure streak; changed relevant local evidence permits a new attempt immediately. Missing products, stock shortages and expired evidence are not technical failures. HTTP catalogues without a local change fingerprint are polled no more frequently than every five minutes after a nontechnical incomplete result.

The automatic pipeline resumes stale, interrupted and technically failed results under standing planning consent, including results that already have a basket. New evidence updates editable baskets without creating duplicates, preserves member settings, and invalidates prior checkout confirmation. Ordered or otherwise frozen baskets are not changed. Recovery never places an order.

Refresh requests retain request IDs, expected revisions, selected menu lines and optional basket/freshness context. Duplicate accepted requests are idempotent; stale revisions are rejected. Pre-order revalidation remains separate and uses Agent 5's existing checkout safeguards. Plan/profile/assessment changes still require a valid published plan; catalogue recovery cannot waive these checks.

## API

All routes require the existing JWT. Ownership is taken from JWT member ID. Other users' result IDs return 404. Catalogue writes additionally require an allowlisted market operator.

Base: `/api/v1/market`.

| Method | Route | Purpose |
|---|---|---|
| GET | `/access` | Current user's operator capability |
| GET | `/catalogue?page=1&size=20` | Catalogue with current versions |
| POST | `/catalogue` | Add `ProductInput`, `expectedVersion: 0` |
| PUT | `/catalogue/{productId}` | Update/disable product with current expected version |
| POST | `/results` | `{planId, planVersion}`; reads trusted published planning data |
| GET | `/results?page=1&size=10` | Current member's results |
| GET | `/results/{id}` | Result, freshness, candidates and delivery state |
| POST | `/results/{id}/refresh` | `{requestId, expectedRevision, menuLineIds, planId?, basketId?, requiredFreshAfter?}` |
| GET | `/results/{id}/package` | Current `ProcurementInputPackage` |

`ProductInput` is documented by the typed record in `model/market/MarketModels.java` and represented directly by the catalogue form. Nutrition uses USDA IDs (1008 energy, 1003 protein, 1004 fat, 1005 carbohydrate, 1079 fibre, 1093 sodium, 1087 calcium, 1089 iron) with `{amount, unit}` values. Requests require explicit `evidenceConfirmed: true`. A disabled product stays in history and disappears from searches.

The procurement package contains the original weekly meal plan and substitution groups **unchanged**, full original planning package, per-requirement market results, shared plan/assessment/profile identifiers, market revision and comparison capability. A completed result with typed failures can be forwarded for downstream recovery, but has `readyForProcurement: false` and must never authorise an automatic order.

With a blank `procurement-endpoint`, MarketDelivery now hands completed inputs to the in-process Agent 5 service. Delivery deduplicates baskets and preserves member preferences. A configured trusted external endpoint retains JSON/Bearer/idempotency-key delivery. See [Agent 5](PROCUREMENT_AGENT.md) for checkout freshness, local confirmation and recovery. Incomplete matches may be delivered for recovery but cannot become confirmed orders.

## Optional authorised HTTP catalogue

`source: HTTP` uses the configured complete `catalogue-endpoint` URL, with a POST body `{ingredientId, description, limit}` and optional Bearer key. It must return:

```json
{
  "source": "Authorised catalogue adapter",
  "truncated": false,
  "products": [
    {
      "productId": "provider-product-id",
      "version": 1,
      "updatedBy": "provider",
      "updatedAt": "2026-10-13T00:00:00Z",
      "facts": "Replace this example string with a complete ProductInput object"
    }
  ]
}
```

The envelope illustrates the shape; `facts` must be an actual validated object. Missing/malformed records cause explicit source failure, and duplicate/invented model product identifiers are rejected. Responses must respect the requested candidate limit and disclose truncation. This is a documented integration adapter, not a claim that any supermarket already supports this contract. Local catalogue editing remains available for local records; HTTP mode does not mix them into external search results.

## Verification

Tests use isolated H2, synthetic nutrition/catalogue fixtures and local HTTP servers. They cover full package handoff, unchanged meal/substitution contracts, member/operator permissions, incomplete/unsafe/stale evidence, bounded refreshes, optimistic updates, provider responses and internal Agent 3 delivery. Browser checks cover matching, evidence expansion, selected-line refresh and catalogue edit invalidation. Production catalogue records and actual provider keys are not used during these checks.

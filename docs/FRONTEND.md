# Frontend guide

Spring Boot serves all pages; no separate frontend server, Node installation or framework is required. Start the service and open `http://127.0.0.1:8080/`, rather than local HTML files. The interface uses plain HTML, JavaScript and shared CSS with a forest-green and cream palette, responsive navigation, status badges and nutrition cards. There are no external font or image dependencies.

## Pages and workflow

| URL | Purpose |
|---|---|
| `/login.html` | Username/password sign-in, errors and JWT storage in tab sessionStorage |
| `/register.html` | Username validation, password confirmation and redirect to sign-in |
| `/` | Current user, meal list, status filters, pagination, details, upload and sign-out |
| `/upload.html` | Meal time, optional photo and/or meal description, preview, consent and analysis; profile version is managed automatically |
| `/record.html?id=UUID` | Foods and portions first (read-only until **Edit foods**), then a food-label style nutrition panel (energy, protein, fat, saturated fat, carbohydrate, sugars, fibre, sodium; vitamins and minerals collapsed), then confirmation. Record IDs and timestamps sit in a collapsed **Record details** section |
| `/profile.html` | Versioned health goals, restrictions, preferences, timezone, AI consent and the resulting daily targets |

Any member page shows a first-login questionnaire (`questionnaire.js`) while no health profile exists: goals, dietary restrictions and routine conditions are tick boxes, allergies and other entries are free text, and both AI consents are optional. **Skip for now** hides it for the browser session; the profile page itself and expert pages never show it.
| `/health.html` | Assess any seven completed dates (defaults to yesterday and the preceding six days); missing-meal prompt offers adding records or assessing existing ones |
| `/assessment.html?id=UUID` | Comparisons, constraints, review decisions, optional AI explanation and a link to meal planning |
| `/expert.html` | Human Expert console for Agent 2: *Awaiting review* and *Completed* tabs with totals, evidence, decision guide and audited decisions. Reached from **Human expert sign-in** (`/login.html?role=expert`), which only admits configured experts. Assessment pages and cards show a *Human review* tag when an expert is or was involved |

1. Initialise or migrate the database, then sign in. The development account is `admin / elec5620`.
2. Select **Upload a meal**. Enter meal time in the device timezone, add a photo, a meal description or both, and consent to processing. Describe foods, approximate portions and cooking methods in English or Chinese. The frontend submits an ISO timestamp with timezone information.
3. Upload progress prevents duplicate submissions. Success opens the record page; HTTP 200 alone does not mean analysis completed. The returned record state controls the page.
4. Follow the three meal review steps: check foods and portions, review nutrition totals, then confirm the meal. Editing submits the complete list of 1–20 foods. Unsaved changes disable confirmation and mark totals as outdated; save and recalculate first. Existing food matches are retained for portion-only edits. Renaming a food starts a new lookup with conservative confidence. Clients cannot supply calculated nutrient values.
5. Validated records become read-only and show delivery status. Local Agent 2 receives them; follow the health assessment link to continue. Manual refresh reads delivery status without resending the record.

## State behaviour

- Awaiting confirmation: edit, confirm or abandon; the deadline is displayed. Unsaved edits block confirmation until saved or discarded.
- Awaiting retry: inspect the failure and retry. Recognised foods can be corrected. The backend reports the attempt limit.
- More meal details needed: create a new record with a clearer photo or a more detailed description; the old record can be abandoned.
- Validated: read-only results, delivery status and validated JSON.
- Abandoned or expired: historical, read-only records; create a new record to continue.

Confirmation and abandonment ask for confirmation. Food names, sources and errors are rendered as text, with only HTTP/HTTPS source links allowed. Public HTML and scripts do not grant data access: API requests still require JWT and ownership checks. The record page shows food names and portions directly, followed by the four main nutrient totals; other nutrients can be expanded. Data sources, failure/activity history, internal record metadata and raw validated JSON are not displayed. A current analysis failure may still provide an actionable status message. The assessment page links directly to meal planning without exposing the Agent 3 input JSON.

## Photos and recovery

The frontend accepts JPEG/PNG source photos up to 30 MB. It resizes large phone photos
to a longest side of 2048 pixels and prepares smaller usable photos to satisfy the
API's 224-pixel minimum; very narrow images retain their aspect ratio with padding.
Images smaller than 64 pixels on either side are rejected with the option to use a
meal description. The prepared file must fit the API's 10 MB and 20-million-pixel
limits. The backend also checks actual content and semantic quality. Photos are not
stored in browser storage. A pending description is retained in the current tab's
upload draft and restored on reload. Text-only meals require member confirmation.

Each upload gets a UUID. The current member last incomplete upload ID and fingerprint are kept in sessionStorage. Identical photo bytes, description, meal time and profile version reuse that ID on retry. A network failure provides a link to check whether the original upload arrived. The upload page loads the current health profile version on entry and again before submission; saved drafts never overwrite it. If the profile changed after an uncertain upload, the page checks for the original record before creating a new request. A version-conflict response resynchronises the version and lets the member resubmit. After reloading, reselect the same photo; check existing records before clearing the session and submitting again. Changed content creates a new request. A successful response clears pending upload metadata.

Once the backend removes a photo, results and history remain available without the original image. Missing API keys produce actual DEFERRED errors rather than fabricated nutrition results.

LAN HTTP may lack `crypto.subtle` or `crypto.randomUUID`. The upload module then uses local SHA-256 and `crypto.getRandomValues` to preserve upload fingerprint and UUID behaviour. Verify with `node --test src/test/frontend/upload-crypto.test.mjs`. Use HTTPS for deployed credentials, tokens and photos.

## Language and verification

Application-authored labels, help, validation messages and model prompts are English. Pages declare `lang="en"`; rendered dates and numbers use `en-AU`. Browser-owned file pickers, native date controls and validation bubbles may follow the browser or operating-system language. User-entered text, original historical records and cached AI explanations are not translated automatically.

Backend tests verify public page resources while keeping business APIs protected. Existing isolated browser checks cover editing, recalculation, service failures, recovery, confirmation, abandonment, logout protection, delivery, list filtering, profile changes, assessment invalidation, expert decisions and mobile layout. Test nutrition values are synthetic. No paid external model calls are part of these tests. The browser file-picker upload flow requires manual verification where local-file access is unavailable.

See [HEALTH_AGENT.md](HEALTH_AGENT.md) for Agent 2 coverage, permissions and configuration.

## Agent 3 meal planning

The sidebar links to `/planning.html`. Select a cleared assessment and a plan start date to request a seven-day menu with explicit Qwen consent. The date defaults to tomorrow in the health profile timezone and accepts any date from today through the next 12 weeks. `/plan.html?id=...` shows meals, daily nutrition targets, ingredient totals, expandable constraints/source evidence, publication controls, delivery status, targeted replans and audit history. Agent 2's assessment page links directly to meal planning when cleared. See [Agent 3](PLANNING_AGENT.md) for constraints and API details.

## Agent 4 market matching

Market matching remains available at `/market.html`, product evidence at `/market-result.html?id=...`, and the operator catalogue editor at `/catalogue.html`. The matching page contains the published-plan matching form; its historical Your market results list, list refresh button and pagination have been removed. The result header uses a short status and next-step message; versions, refresh counts and comparison capability are collapsed under Matching details. Matched ingredients use a compact weekly quantity, supermarket and package-price table. The unmatched section appears only when an ingredient has no product eligible for all required meals; candidate products are available only through closed disclosures in that section. Selected-meal refresh controls are also collapsed by default. Matching still opens the selected plan's result directly. Backend history, matching and delivery APIs remain available under `/api/v1/market`; see [MARKET_AGENT.md](MARKET_AGENT.md).

## Procurement pages

`procurement.html` opens with **This week's basket**: a four-step progress row (health assessment → meal plan → product matching → basket) whose steps link to their pages for viewing, the current status, and the products of the ready basket. It polls `GET /api/v1/procurement/pipeline` every 10 seconds while planning, matching, bounded matching recovery or basket creation is pending. Stale/interrupted matching has a distinct `MATCH_PAUSED` state with a reason and matching link; completed matches awaiting handoff use `BUILDING_BASKET`. Failed handoff uses `BASKET_PAUSED` and an explicit Build basket retry, which does not place an order. Below it, the page lists baskets. Above 1100 px, the basket items and budget/action columns each scroll within the viewport, including keyboard scrolling from their labelled regions. At tablet and mobile widths they return to normal single-column page scrolling. `basket.html` covers UC-06 screens 1 to 3: locked constraints, basket items with evidence, preferences, budget summary, the revalidation overlay and the material-change dialog. `orders.html` lists orders, and `order.html` (screen 4) shows store fulfilment with the recovery, re-optimisation and cancellation options. `pantry.html` (sidebar **Pantry**) lists shelf-stable leftovers still at home and everything bought in the last 7 days; the member can correct an amount or mark an item used up.

### Pantry

The pantry is rebuilt from order history on every read (`GET /api/v1/procurement/pantry`), so cancelled or rejected orders drop out without any reversal. For each accepted order line, the surplus (packages bought minus what the week's meals need) is kept only for shelf-stable staples: dry rice, oats, lentils, pasta, quinoa and rice noodles bought raw, plus olive oil, sesame oil and soy sauce. Fresh food and ready-to-eat pouches are not carried over. Stock is stored as weight as sold and converted to a ready-to-eat equivalent with the product's cooked-yield factor. When a basket is built or refreshed, Agent 5 takes that equivalent per ingredient before buying anything (oldest stock first when the order is placed), so a fully covered ingredient needs no package, store order or fee; nutrition checks still count the whole portion. `POST /api/v1/procurement/pantry/corrections` with `{key, remainingGrams}` records a correction (0 = used up); only these corrections are stored, in `member_pantry`. Two open baskets can both plan to use the same stock; whichever order comes second gets what is left. `procurement-expert.html` handles restricted reviews; `procurement-reports.html` displays assigned-store aggregate local demand. All screens use the shared stylesheet, English messages and JWT ownership checks.

Basket issues appear as highlighted cards under Needs attention. An exceeded budget shows the basket total, budget, amount over and a link to the budget input. Locked health constraints start collapsed and can be expanded to inspect their read-only values; refreshing the basket preserves the member's expanded/collapsed choice.

Basket product Edit buttons open a unified dialog for product removals, budget, price-quality and fulfilment. Changes are staged until Save and recalculate. The dialog compares Economy/Balanced/Premium with Delivery/Pick-up and shows real product, package and configured supermarket-fee differences; equivalent results are labelled explicitly. Don't like this product permits replacement and is remembered by later baskets; Don't need removes that ingredient from this purchase without replacement, leaving the meal plan unchanged. Don't like this food asks for AI consent when the plan uses that food, then opens the revised plan from Agent 3 for publishing. The health profile page lists disliked foods and lets the member add or remove them.

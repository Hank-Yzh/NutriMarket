# Demo scenarios

Demo accounts for the presentation and what each one demonstrates. All accounts use the password `nutrimarket-demo`. Prepare them with `DemoSeeder` (see README "Demo data") within 12 hours before the presentation.

`DemoSeeder` has two modes:

- **Reset (default):** deletes the demo accounts and rebuilds them, discarding rehearsal changes.
- **`--nutrimarket.demo.keep-existing=true`:** deletes nothing.
  - demo-data.sql only adds missing rows.
  - Members that already have scenario data are re-checked, not rebuilt.
  - Other accounts are never touched.
  - Scenario data that has gone stale (catalogue evidence older than 24 hours) can then only be rebuilt with the reset mode.

## How the scenarios stay honest

A username only selects input data. No code branches on a username or scenario code.

| Input | Where it lives |
|---|---|
| Accounts, demo_review's meal week | `src/main/resources/demo-data.sql` |
| Profiles (allergies, restrictions), two pre-validated meal plans, expected behaviour | `src/main/resources/demo-scenarios.json` |
| Market evidence: stock, allergen statements, may-contain labels | `src/main/resources/test-catalogue.json` (synthetic stores) |

Everything else is produced by the unmodified agents:

- Agent 2 assesses each copied meal week.
- Agent 3 validates the fixed plan, then publishes it and builds the SubstitutionGroups.
- Agent 4 classifies every candidate.
- Agent 5 builds the basket. For demo_replan it issues the ReplanRequest.

`DemoScenarios.verify` computes each PASS/FAIL from that output. It runs the same checks for the seeder log and for `DemoScenarioTests`.

The catalogue is shared by every account, so its demo conditions apply to everyone:

- **Seafood shortage:** salmon fillets and prawns are out of stock at all three stores this week.
- **VB-CHK-1000** (chicken) has no allergen statement.
- **GL-CHK-STRIP-500** (chicken) may contain sesame and peanut.

Any plan that uses salmon or prawns therefore gets a substitute or a replan, as it would in a real shortage.

## Accounts

| Account | Code | Data after DemoSeeder | Demonstrates (presentation step) |
|---|---|---|---|
| `demo_new` | – | Account only, no profile | 1. First-login questionnaire creates the profile |
| `demo_review` | – | Profile (type 2 diabetes, automatic planning on) and 20 meals for 5–11 Oct 2026; Sunday 11 Oct has no dinner; 6–8 Oct are small meals under 900 kcal a day | 2. "Some meals may be missing" prompt: add Sunday dinner as a text description (meal time 11 Oct, about 6:30 pm). 3. Assessment of week 5 Oct. 4. It needs expert review (energy far below target on 3 days); `demo_expert` clears it, then planning, matching and the basket run automatically (Qwen, a few minutes) |
| `demo_live` | – | The original demo account: the same 21 meals as demo_ready | Spare account for rehearsals; its assessment is cleared without an expert |
| `demo_expert` | – | Human Expert, report store FreshMart Central | 4. Assessment review queue. 12. Store demand report |
| `demo_ready` | DEMO-000 | Cleared assessment, published "chicken-week" plan for next week, market result, complete draft basket | 5. Meal plan. 6. Market matching (all 30 ingredients matched). 9. Basket. 10. Basket adjustments (budget, preference, pick-up/delivery, exclusions, item edits), then place the order |
| `demo_allergy` | DEMO-001 | Same plan as DEMO-000; allergies sesame and peanut | 7. Allergy safety |
| `demo_substitute` | DEMO-002 | "seafood-week" plan, no restrictions | 8. Out-of-stock food replaced by a planner-approved substitute |
| `demo_replan` | DEMO-003 | Same plan and market as DEMO-002; pescatarian with a soy allergy; basket blocked | 11. ReplanRequest to Agent 3 |
| `demo_shopper1`…`5` | SHOP-1…5 | DEMO-000's plan and a confirmed order (FreshMart Central preferred) each; pantry shows staples left over | 12. Store demand report needs at least 5 members per product before it shows rows |

The meal plans are in `demo-scenarios.json`. Both share the same breakfasts, lunches and three vegetarian dinners:

- **chicken-week:** chicken on the other four dinners.
- **seafood-week:** prawns on days 1 and 5, salmon on days 3 and 7.

Every day is near the 1800–2200 kcal, 70–130 g protein, 50–80 g fat and 200–300 g carbohydrate targets, so Agent 3 publishes the plans without adjusting portions.

## What each scenario shows

**DEMO-001 Allergy safety** (`demo_allergy`). On the Market matching page, open Chicken breast (also Rolled oats or Spinach):

- `VB-CHK-1000` (the cheapest chicken) is UNKNOWN: "Complete allergen and may-contain evidence is missing."
- `GL-CHK-STRIP-500` and `GL-OAT-500` are blocked: "Declared allergen or cross-contact: Sesame".

Agent 5's basket contains none of them.

For contrast, `demo_ready` has the same plan. There, `GL-CHK-STRIP-500` is eligible, while `VB-CHK-1000` is UNKNOWN for every member.

**DEMO-002 Intelligent substitution** (`demo_substitute`). Prawns and salmon show their own SKUs as out of stock.

- The "Planner-approved Substitutions" section opens automatically.
  - For prawns, salmon is out of stock, while chicken and tofu have eligible SKUs.
  - Salmon's substitutes are prawns, chicken and tofu.
- The basket buys an eligible substitute (tofu with the current prices) for those four dinners. Each basket item shows the substitution.

**DEMO-003 Replanning** (`demo_replan`). For a pescatarian with a soy allergy, Agent 3 only permits salmon and prawns as substitutes for each other, and both are out of stock.

1. Agent 4 records each line as UNAVAILABLE after checking that substitute.
2. Agent 5 blocks the basket on the four seafood dinners.
3. On the basket page, tick the AI consent and choose **Request replanning**. Agent 5 sends a ReplanRequest:
   - reason AVAILABILITY;
   - the four dinners;
   - unavailable ingredients prawns and salmon.
4. Agent 3 replaces only those dinners with Qwen.
5. Automatic planning publishes the new version, Agent 4 matches it and a new basket appears (a few minutes).

The plan page shows the replan history. Agent 3 allows 2 replans per plan. A third request would send the basket to Human Expert review instead (UC-06 A1). That path can be described rather than shown.

**Live AI.** The seeder runs product matching with the verified catalogue mapping, so it needs no Qwen calls and is repeatable. Live Qwen use:

- **Refresh** on any Market matching page re-runs Agent 4 with Qwen semantic scoring.
- demo_review's text meal (step 2), its plan after expert review (step 4) and DEMO-003's replan (step 11) also use Qwen.

## Test results

`./mvnw -o test -Dtest=DemoScenarioTests` runs the scenarios on H2. It uses the shipped fixtures and pinned USDA values (`src/test/resources/usda-reference.json`); only USDA and Qwen are replaced. Six tests pass:

- **Fixed plans:** both plans pass Agent 3 validation unchanged.
- **DEMO-000:** a complete basket.
- **DEMO-001:** the UNKNOWN and cross-contact SKUs are never selected, and the contrast with DEMO-000 holds.
- **DEMO-002:** a substitute is bought for all four seafood dinners.
- **DEMO-003:**
  - The ReplanRequest has reason AVAILABILITY, the four dinners and both seafood ingredients.
  - Agent 3's model input excludes prawns, salmon, tofu and chicken.
  - The revised plan matches completely and yields a complete basket.
- **Shoppers:** five orders make the FreshMart Central demand report visible.

The seeder was also run against a local MySQL 8.4 copy, and every scenario passed. On the same copy:

- `demo_review`'s coverage (then still on demo_live) showed 20 meals with 11 Oct incomplete.
- Its assessment was POTENTIAL_HIGH_RISK and appeared in `demo_expert`'s queue.
- `demo_new` returned PROFILE_REQUIRED.
- The FreshMart Central report showed 8 products.

# MVC and application layers

The application uses Spring MVC REST controllers with a static HTML/JavaScript view. Business and persistence code follow **Controller → Service → DAO → MySQL**. The top-level Java package is `com.nutrimarket.nutrimarket`.

```text
src/main/java/com/nutrimarket/nutrimarket/
├── NutriMarketApplication.java
├── controller/
│   ├── auth/AuthController.java
│   ├── dietary/DietaryController.java
│   ├── health/HealthController.java
│   ├── planning/PlanningController.java
│   ├── market/MarketController.java
│   ├── procurement/ProcurementController.java
│   └── advice/                    # HTTP exception translation
├── service/
│   ├── auth/                      # Registration, login and JWT issuance
│   ├── dietary/                   # Analysis, transitions and outbox delivery
│   ├── health/                    # Profiles, assessments, review and scheduling
│   ├── planning/                  # Planning, validation, revisions and delivery
│   ├── procurement/               # Basket selection, checkout, simulated store orders and reports
│   └── market/                    # Catalogue, matching, freshness and procurement handoff
├── dao/
│   ├── auth/UserMapper.java
│   ├── dietary/                   # IntakeRepository and record/outbox mappers
│   ├── health/HealthMapper.java
│   ├── planning/PlanningMapper.java
│   ├── procurement/ProcurementMapper.java
│   └── market/MarketMapper.java
├── model/                         # Auth, dietary, health and planning data contracts
├── integration/                   # Qwen, USDA and HTTP clients
├── validation/                    # Username and image validation
├── exception/                     # Application exceptions
└── config/                        # Spring, security and configuration properties
```

## Responsibilities

| Layer | Responsibility | Example |
|---|---|---|
| Controller | HTTP routes, request binding/validation, authenticated identity and response status | `controller.dietary.DietaryController` |
| Service | Business rules, ownership checks, transactions, orchestration and provider calls | `service.dietary.DietaryAnalysisService` |
| DAO | Database reads/writes and persistence mapping | `dao.dietary.IntakeRepository`, `dao.health.HealthMapper` |
| Model | Request/response DTOs, domain snapshots and persistence data records | `model.health.HealthModels` |
| View | English HTML, JavaScript and shared CSS served by Spring Boot | `src/main/resources/static/` |

MyBatis `@Mapper` interfaces are DAO components. Their names retain the `Mapper` suffix to identify MyBatis proxies. `IntakeRepository` is also in DAO: it maps stored JSON snapshots and coordinates record/outbox persistence through the mappers. It is not an extra business layer. Small services remain concrete Spring beans; no duplicate pass-through service interfaces or DAO wrappers are required.

Controllers depend on services and data contracts, not on mappers or repositories. Services call DAO and external integration adapters. DAO does not call controllers or services. The security filter obtains current-account availability through AuthService, so account lookup and availability validation remain in the service layer.

MyBatis XML remains at `src/main/resources/mapper/`, as configured by `mybatis.mapper-locations`. Namespaces now point to `dao.*`; result types point to relocated model classes or mapper nested row records. HealthMapper retains its SQL annotations inside DAO. Spring component and mapper scanning starts at the unchanged root application package.

## Request examples

- Login: `AuthController → AuthService → UserMapper → member_account`; JwtTokenService issues the token after password verification.
- Meal analysis: `DietaryController → DietaryAnalysisService → IntakeRepository → IntakeRecordMapper / IntakeOutboxMapper`. The service also calls Qwen/USDA adapters and manages the transaction.
- Health assessment: `HealthController → HealthService → HealthMapper / UserMapper`. HealthInbox imports validated dietary records; HealthEngine evaluates targets; HealthExplanation requests optional English AI text.

## Compatibility and checks

The refactor changes Java package locations, not REST URLs, JSON property names, database tables, SQL schema, JWT claims, configuration keys or frontend paths. Existing database records do not require migration: snapshots store data, not Java class names. External Java code importing old packages must update its imports.

Use a clean build after moving classes to remove obsolete compiled beans:

```bash
./mvnw clean verify
```

Backend integration tests cover Spring wiring, MyBatis XML/annotation mappings, authentication and all four agents. LayeredArchitectureTests checks package placement and prevents controllers from directly depending on DAO or integration classes. Tests use isolated H2 databases and local/mock providers. No actual database credentials or paid APIs are needed.

- Nutrition planning: `PlanningController → PlanningService → PlanningMapper`. PlanningEngine validates the Qwen proposal against IngredientCatalog, health constraints and USDA evidence. PlanningDelivery sends versioned published packages through an outbox. See [the planning contract](PLANNING_AGENT.md).

- Market matching: `MarketController → MarketService / CatalogService → MarketMapper`. MarketEngine checks immutable planning constraints against source facts. QwenMarketClient only scores supplied product descriptions. A blank Agent 3 market endpoint now routes through the in-process MarketService; MarketDelivery maintains the Agent 5 outbox.

- Procurement: `ProcurementController → ProcurementService / BasketEngine → ProcurementMapper`. Agent 4 delivers internally by default; Agent 5 checks current market and planning contracts before local confirmation. No retailer/payment client is configured.

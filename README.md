# sql-testing-demo

Case study for [CPT-SQL](https://github.com/Alizeci/CPT-SQL): an e-commerce application in Java (plain JDBC, PostgreSQL 17) with six annotated queries and the four CPT-SQL workflows wired into GitHub Actions. It is the application evaluated in the master's final project and in a companion article in preparation.

- To **adopt CPT-SQL** in your own project, use this repository as a template (sections below).
- To **check or replicate the evaluation**, go to [`evaluation/`](evaluation/README.md): scenario branches, pull requests, run IDs, logs and the prompts of the AI test.

## Queries and contracts

| `queryId` | SLA (p95) | Priority | Purpose |
|---|---|---|---|
| `searchProductsByCategory` | 300 ms | HIGH | Catalog search by category and price range, ranked by rating |
| `getProductDetail` | 50 ms | HIGH | Product lookup by primary key |
| `checkInventory` | 30 ms | HIGH | Available stock |
| `updateInventory` | 100 ms | HIGH | Stock decrement after a sale |
| `createOrder` | 200 ms | HIGH | Transactional insert of a new order |
| `salesDashboard` | 2,500 ms | MEDIUM | Revenue analytics by category |

The contracts are declared with `@SqlQuery` and `@Req` in `EcommerceQueryRegistry`.

## Components

| Class | Role |
|---|---|
| `EcommerceQueryRegistry` | Declares the six queries and their performance contracts (Phase 1) |
| `EcommerceJdbcRepository` | Executes each query with `CaptureContext`, so the driver-level interceptor links each execution to its `queryId` |
| `EcommerceSimulator` | Generates production-like traffic against the capture database (Phase 2) |
| `EcommerceSanitizationStrategy` | Redacts sensitive values in what the interceptor captures, before anything is stored |

## Requirements

- Java 17 and Docker
- CPT-SQL published to the local Maven repository (`./gradlew publishToMavenLocal` in CPT-SQL, tag `v1.0.0`)
- A PostgreSQL 17 database that plays the role of production, created with `src/main/resources/schema-ecommerce.sql` and populated with `src/main/resources/seed-ecommerce-400k.sql` (400,000 rows per table, with sales concentrated in a few products)
- For CI: a self-hosted GitHub Actions runner with Docker, Java 17 and CPT-SQL in its local Maven repository, and the repository secrets `DEMO_DB_URL`, `DEMO_DB_USER` and `DEMO_DB_PASSWORD` pointing to the capture database

## Run locally

```bash
git clone https://github.com/Alizeci/sql-testing-demo.git
cd sql-testing-demo

./gradlew compileJava      # Phase 1: generates queries.json
export DB_URL=jdbc:postgresql://localhost:5432/ecommerce_demo DB_USER=... DB_PASSWORD=...
./gradlew runSimulator     # Phase 2: capture and protected statistics (SIMULATION_SECS, default 180)
./gradlew bootRun          # Phases 3 and 4: mirror, benchmark and detection
```

CPT-SQL starts the PostgreSQL 17 mirror container by itself. Configuration lives in `src/main/resources/application.properties`. This project raises `loadtest.detector.baselineTolerancePct` to `0.50` because its runner varied by about ±9% between runs; the other thresholds keep the CPT-SQL defaults.

## CI workflows

Each workflow calls the reusable workflow of the same name in CPT-SQL, pinned to `v1.0.0`.

| Workflow | Trigger | What it does |
|---|---|---|
| `light-benchmark.yml` | Pull request to `main` | Gate: mirror with 400,000 rows per table, `light` profile |
| `update-baseline.yml` | Push to `main`, after a capture, or manual | Measures and commits `baseline.json` |
| `nightly-benchmark.yml` | Daily at 07:00 UTC, or manual (`rows-per-table` input) | 2,000,000 rows per table, `normal` profile, every query against its SLA |
| `capture-production-profile.yml` | Manual | Runs the simulator against the capture database and commits the protected `load-profile.json` (ε = 1.4, δ = 2 × 10⁻⁶ in total) |

`load-profile.json` and `baseline.json` are versioned as a pair. The baseline records which profile and volume it was measured with, and the gate skips relative criteria if they do not match.

## Project structure

    sql-testing-demo/
    ├── src/main/java/escuelaing/edu/co/demo/    # Registry, repository, simulator, sanitization
    ├── src/main/resources/
    │   ├── application.properties               # CPT-SQL configuration
    │   ├── schema-ecommerce.sql                 # DDL used by the capture database and the mirror
    │   └── seed-ecommerce-400k.sql              # Seed of the capture database
    ├── .github/workflows/                       # The four CI workflows
    ├── evaluation/                              # Evidence of the evaluation (see its README)
    ├── baseline.json                            # Approved baseline
    └── load-profile.json                        # Protected statistics from the last capture

## Versions

| Tag | Meaning |
|---|---|
| `tg-runs-2026-10` | `main` as used by every evaluation run. All scenario and AI branches start from this commit |
| `v1.0.0` | First release, with the `evaluation/` folder and workflows pinned to CPT-SQL `v1.0.0` |

## Citation

Izquierdo Castro, L. A. (2026). *Diseño y desarrollo de un sistema de pruebas de carga continua para consultas SQL durante el ciclo de vida del software* [Master's degree final project]. Escuela Colombiana de Ingeniería Julio Garavito.

## License

Copyright 2026 Laura Alejandra Izquierdo Castro and Escuela Colombiana de Ingeniería Julio Garavito.

[Apache License 2.0](LICENSE). See [`NOTICE`](NOTICE).

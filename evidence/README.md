# Evidence

Evidence behind Chapter 6 of the thesis and Section IV of the article. Every number reported there can be traced to a branch, a pull request and a GitHub Actions run listed here.

This folder contains this file, [`ai-test/`](ai-test/) with the prompts given to the AI assistant and the SQL it returned, and [`logs/`](logs/) with the downloaded log of every run cited below. To open a run on GitHub, use `https://github.com/Alizeci/sql-testing-demo/actions/runs/<run>` and pick the attempt in the attempt selector at the top right.

## Environment

| Item | Value |
|---|---|
| CPT-SQL | Tag `tg-runs-2026-10` (`aedf43f`), published to the runner's local Maven repository |
| This repository | Tag `tg-runs-2026-10` (`ea47260`). Every scenario and AI branch starts from it |
| Runner | Self-hosted, one Linux VM on a dedicated laptop. Mirror database in Docker with the `postgres:17` image, capture database on PostgreSQL 16.15 |
| Capture database | 400,000 rows per table from `seed-ecommerce-400k.sql`, sales concentrated in a few products. It plays the role of production |
| Capture | Run `37322833729` (600 s). Privacy budget ε = 1.4 (0.6 numeric, 0.4 categorical, 0.4 foreign keys), δ = 2 × 10⁻⁶, basic composition |
| Baseline | Run `37338822589`. `light` profile, 400,000 rows. `salesDashboard` 1,466 ms (plan cost 94,708), `searchProductsByCategory` 62 ms (plan cost 8,015) |
| Gate | 400,000 rows per table, 60 s measurement at 10 TPS, about 7 minutes per run |
| Nightly | 2,000,000 rows per table, 120 s measurement at 100 TPS |
| Detector | CPT-SQL defaults, except `baselineTolerancePct = 0.50` |

Every reported run compared against a `COMPARABLE` baseline (same load profile and volume).

## 1. Planted changes

Five changes on separate branches, three runs each on the same commit (*Re-run all jobs*). The pull requests stay open on purpose: their gate comments are part of the evidence.

| # | Scenario | Branch | PR | Run | Observed (three runs) | Verdict |
|---|---|---|---|---|---|---|
| 1 | Negative control: JavaDoc edit, no SQL change | `feature/test-cosmetic-change` | #23 | `37341052655`, attempts 1–3 | No findings | Pass 3/3 |
| 2 | Structural change: `salesDashboard` adds aggregations | `feature/test-analytics-slo-breach` | #19 | `37341067512`, attempts 1–3 | Plan cost +169% (254,395–255,111 vs. 94,708), p95 2,554–4,028 ms (SLA 2,500) | Block 3/3 (`PLAN_CHANGED`, `P95_EXCEEDED`) |
| 3 | Join explosion: `searchProductsByCategory` adds two `LEFT JOIN`s with `COUNT(DISTINCT)` | `feature/test-search-warning` | #22 | `37341075978`, attempts 1–3 | p95 16–23 s (SLA 300 ms). On the capture database the same query exceeded the 8 s timeout | Block 3/3 (`P95_EXCEEDED`, `PLAN_CHANGED`) |
| 4 | Moderate slowdown: `searchProductsByCategory` computes `MD5(name)` per row | `feature/test-baseline-exceeded-md5` | #36 | `37358227355`, attempts 1–3 | p95 from 62 to 94–97 ms, about 32% of the SLA | Pass with warning 3/3 (`BASELINE_EXCEEDED`) |
| 5 | Tighter contract: `salesDashboard` adds statistics and its SLA drops to 2,200 ms | `feature/test-slo-proximity_v2` | #34 | `37341094698`, attempts 1–3 | p95 at 88%, 108% and 99% of the SLA | Block 3/3 (`SLO_PROXIMITY` twice, `P95_EXCEEDED` once) |

Scenario 4 run `37341087297` belongs to the first calibration, with two nested `MD5` calls, which crossed the 80% zone in one run. It was replaced by a single `MD5` before the reported runs.

## 2. Nightly run on `main`

No code change. Run `37366633565`, attempts 1 and 2, 2,000,000 rows per table.

| Query | 400,000 rows (baseline) | 2,000,000 rows, attempt 1 | 2,000,000 rows, attempt 2 |
|---|---|---|---|
| `salesDashboard` (SLA 2,500 ms) | 1,466 ms | 5,039 ms (202%) | 6,796 ms (272%) |
| `searchProductsByCategory` (SLA 300 ms) | 62 ms | 304 ms (101%) | 410 ms (137%) |

The primary-key queries stayed flat.

## 3. SQL written by an AI assistant

See [`ai-test/`](ai-test/) for the prompts and the generated SQL.

**Protocol.** GitHub Copilot in VS Code, agent mode, every edit subject to manual approval, automatic model selection. Auto routed all three requests to `gpt-5.6-luna`, and the assistant read no project files. One new chat per request, first answer used unedited (only wrapped in a Java text block, same `?` parameters in the same order), one pull request per answer, three runs each, interleaved with four runs of the negative control on the same day.

| Request | Branch | PR | Run | Observed | Verdict |
|---|---|---|---|---|---|
| 1. Search shows units sold and inventory movements | `feature/ai-search-sales` | #39 | `37526569517`, attempts 2–4 | Two correlated subqueries. Plan cost +43–44%, p95 64–66 ms (about 22% of the SLA) | Pass with warning in 2 runs. 1 run blocked by the unchanged dashboard at 96% of its SLA (`SLO_PROXIMITY`) |
| 2. Dashboard adds distinct customers and best-selling product | `feature/ai-dashboard-customers` | #40 | `37528163200`, attempts 2–4 | CTE ranked with `ROW_NUMBER()`. Plan cost +1,280–1,287%, p95 4,417–4,524 ms (177–181% of the SLA) | Block 3/3 (`P95_EXCEEDED`, `PLAN_CHANGED`) |
| 3. Search filters rating ≥ 4.0 | `feature/ai-search-rating` | #41 | `37528213688`, attempts 2–4 | p95 63–66 ms, no findings | Pass 3/3 |
| Negative control | `feature/test-cosmetic-change` | #23 | `37341052655`, attempts 7–10 | Unchanged dashboard at 77%, 77%, 81% and 78% of its SLA | Pass in 3 runs. 1 run blocked (`SLO_PROXIMITY`, 81%) |

**Runner state.** Attempt 1 of each AI request ran with the runner's laptop on battery (72 samples per run instead of about 90, unchanged dashboard above 100% of its SLA) and is not reported. From attempt 2 on, the laptop was plugged in. Even so, the runner was about 25% slower than during the planted runs, which is why the negative control was repeated alongside the AI runs and every run is reported. The false blocks came from the unchanged dashboard, which ranged from 75% to 96% of its SLA, while the plan cost of the changed queries barely moved.

## 4. Logs

[`logs/`](logs/) holds the downloaded logs of every run listed above, named `<run>_<attempt>_<branch>.zip`. GitHub keeps step-level detail only for the last attempt of a run; earlier attempts keep their summary.

## Replicating

Follow the replication guide, *Guía de replicación del caso de estudio e-commerce*. Verdicts and plan costs should match. Absolute latencies depend on the runner's hardware.

# 07 — Risk-Model Calibration (PD/LGD)

> **Audience:** credit risk, model risk management, audit, engineering.
> **Scope:** how the IFRS 9 probability-of-default (PD) and loss-given-default (LGD) parameters
> feeding the provisioning engine are versioned, reviewed, replayed, and what their current
> limits are. Issue [#8364](https://github.com/open-bank-oss/open-bank-oss/issues/8364).

## 1. Where the parameters come from

Every ECL computation starts from an `EclInputs` produced by the service's
`RiskParameterSource` port. Today the only binding is
`ConservativeRiskParameterSource` (`infrastructure/adapter/NoOpLendingAdapters.kt`), which
supplies **flat, conservative placeholder values** — the same PD/LGD for every exposure:

| Parameter | Current value | Meaning |
|---|---|---|
| `pd12Month` | `DEFAULT_PD_12M` (0.02) | 12-month probability of default (Stage 1) |
| `pdLifetime` | `DEFAULT_PD_LIFETIME` (0.20) | lifetime PD (Stages 2/3) |
| `lgd` | `DEFAULT_LGD` (0.45) | unsecured loss given default, before collateral |

These are deliberately conservative placeholders chosen so the provisioning pipeline
(staging, delta posting, outbox events) can be built and tested end-to-end. **They are not
calibrated to any observed default history and must never be presented as such** (see §5).

## 2. Versioning — a parameter change is a reviewed event

Every parameter set carries a `modelVersion` string, bound in exactly one place:
`ConservativeRiskParameterSource.MODEL_VERSION` (today `noop-flat-v1`).

The convention, enforced by code review and the threat-model changelog:

1. **Any change to `DEFAULT_PD_12M` / `DEFAULT_PD_LIFETIME` / `DEFAULT_LGD` MUST ship in the
   same commit as a `MODEL_VERSION` bump.** The KDoc on the constant documents this; a diff
   that touches one without the other is a review blocker.
2. The version flows into `EclInputs.modelVersion` (validated non-blank), onto every
   `ProvisioningSnapshot`, and is **persisted on every `loan_provisioning` row**
   (`model_version` column, migration V10). An ECL figure can therefore always be traced back
   to the exact parameter set that produced it — the audit trail regulators ask for (EBA
   IRB-guide spirit: model identification per estimate).
3. At startup the source logs the bound model version (`@Startup fun logBoundModel()`), so a
   pod's log line answers "which model produced this period's provisions" without a DB query.

## 3. Calibration method (target process)

The calibration loop once internal default history exists:

1. **Data source.** Internal default/loss history from the loan book (default definition:
   > 90 DPD, consistent with `Ifrs9.assess` staging). Until sufficient history accumulates,
   proxy benchmarks are the ČNB's published banking-sector default and loss rates — clearly
   labelled as proxies.
2. **Frequency.** Quarterly review by the credit-risk function; ad-hoc review on a material
   portfolio change (new product, macro shock).
3. **Owner.** The credit-risk function owns the values; engineering owns the mechanics
   (version bump, migration, replay). Both sign the change — the same four-eyes principle as
   loan origination.
4. **Pre-ship quantification.** Every candidate parameter set is replayed against the
   synthetic portfolio BEFORE merge (§4). The replay report is attached to the PR that bumps
   `MODEL_VERSION`.

## 4. Simulation replay

`openbank-simulation`'s `LendingEclCalibrationScenario` is the replay harness:

- `syntheticPortfolio(seed)` builds a deterministic synthetic loan book spanning every stage
  bucket (clean / watch / Stage 2 / Stage 3) with a seeded collateralised subset, so the
  collateral-adjusted-LGD path is exercised.
- `replay(portfolio, current, candidate)` re-runs the book through the **real** `Ifrs9`
  domain math (staging, collateral-adjusted LGD, PD · LGD · EAD) under both parameter sets
  and returns a `CalibrationReport`: per-exposure deltas plus reconciling portfolio totals,
  each figure attributed to its model version.

The replay mirrors production `LendingService.snapshotFor`/`applyCollateral` semantics
exactly — it is a preview of what the next provisioning cycle would post under the candidate
parameters, computed without touching a database.

Run it via the scenario's unit tests or any Kotlin REPL/test harness in `openbank-simulation`.

## 5. Honest limits (what this is NOT)

- **Not regulatory capital.** The flat placeholders are not IRB-approved parameters; the
  provisioning numbers they produce are pipeline-correct, not risk-calibrated.
- **Flat across the book.** No rating grades, no segmentation by product/collateral/vintage —
  every exposure gets the same PD/LGD until a real source binds.
- **Stage 3 PD is the supplied lifetime PD**, not forced to 1.0 — `Ifrs9.assess` trusts the
  parameter set; a calibrated source must return PD ≈ 1 for defaulted exposures.
- **Collateral adjustment is first-pass** (ADR-0028 D1): reads last-declared market value and
  haircut, no real-time revaluation, no legal perfection check. See the KDoc on
  `LendingService.applyCollateral`.
- **No discounting to EIR inside `Ifrs9`** — callers pass an already-discounted EAD when
  material.

These limits are deliberately documented rather than silently absorbed: they are the
acceptance criteria a real calibration must retire.

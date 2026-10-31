# Changelog

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/incentive-service-v0.7.2...incentive-service-v0.8.0) (2026-10-18)


### Features

* **incentive:** enable runtime tracing and verify offer trace continuity ([#12902](https://github.com/JiRaska/open-bank-oss/issues/12902)) ([905baaa](https://github.com/JiRaska/open-bank-oss/commit/905baaa3f4f6ec981b2af4292aedf5f4584d6525))

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/incentive-service-v0.7.1...incentive-service-v0.7.2) (2026-10-14)


### Bug Fixes

* audit follow-ups 4-12 — live rules.yaml reads, timeout/pool gates, ADR staleness gate, coverage floors ([#12773](https://github.com/JiRaska/open-bank-oss/issues/12773)) ([09377e6](https://github.com/JiRaska/open-bank-oss/commit/09377e6fe51a06df94b5ad8040c5cc1478c3d416))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/incentive-service-v0.7.0...incentive-service-v0.7.1) (2026-10-14)


### Security

* **libs-runtime:** send OPA a verified service_account flag (phase 1 of 2) ([#12538](https://github.com/JiRaska/open-bank-oss/issues/12538)) ([3a8493a](https://github.com/JiRaska/open-bank-oss/commit/3a8493a7d8640b0baf252a879c1a908bd19587e2))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/incentive-service-v0.6.4...incentive-service-v0.7.0) (2026-10-09)


### Features

* **incentive:** purge delivered incentive_outbox rows like every other outbox ([#11916](https://github.com/JiRaska/open-bank-oss/issues/11916)) ([4c86c6f](https://github.com/JiRaska/open-bank-oss/commit/4c86c6f729c27fabcf5431d27ee5270116df06b2))

## [0.6.4](https://github.com/JiRaska/open-bank-oss/compare/incentive-service-v0.6.3...incentive-service-v0.6.4) (2026-10-04)


### Bug Fixes

* **docs:** keep every runnable service documentation current ([#11989](https://github.com/JiRaska/open-bank-oss/issues/11989)) ([88b6e08](https://github.com/JiRaska/open-bank-oss/commit/88b6e087386cd885badf22f75cc4694faa0f30bd))

## [0.6.3](https://github.com/JiRaska/open-bank-oss/compare/incentive-service-v0.6.2...incentive-service-v0.6.3) (2026-09-23)


### Security

* **card-issuance:** named machine callers for the RBAC-only reads ([#10486](https://github.com/JiRaska/open-bank-oss/issues/10486) batch 6) ([#10550](https://github.com/JiRaska/open-bank-oss/issues/10550)) ([3b97193](https://github.com/JiRaska/open-bank-oss/commit/3b9719319a1363979555ffb110e1517b14a8ee42))

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/incentive-service-v0.6.1...incentive-service-v0.6.2) (2026-09-13)


### Bug Fixes

* **incentive:** answer 404 for an offer that does not exist, before any conflict check ([#9795](https://github.com/JiRaska/open-bank-oss/issues/9795)) ([5ff4fe4](https://github.com/JiRaska/open-bank-oss/commit/5ff4fe4fc520b6ae733a7b999d556ed2e1034c24)), closes [#9794](https://github.com/JiRaska/open-bank-oss/issues/9794)
* **incentive:** guard incentive_outbox occurred_at plausibility at INSERT ([#9310](https://github.com/JiRaska/open-bank-oss/issues/9310)) ([f5b97f3](https://github.com/JiRaska/open-bank-oss/commit/f5b97f3f107f624d740433df231340ebb8864cb1))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/incentive-service-v0.6.0...incentive-service-v0.6.1) (2026-09-01)


### Bug Fixes

* **incentive:** reject a null array element with 400 instead of 500 ([#8006](https://github.com/JiRaska/open-bank-oss/issues/8006)) ([2377a2c](https://github.com/JiRaska/open-bank-oss/commit/2377a2cece50951415248e6f51baf00077292a58)), closes [#7867](https://github.com/JiRaska/open-bank-oss/issues/7867)

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/incentive-service-v0.5.0...incentive-service-v0.6.0) (2026-08-27)


### Features

* **customer-edge:** add campaign incentive claims ([#7281](https://github.com/JiRaska/open-bank-oss/issues/7281)) ([e8a1d6c](https://github.com/JiRaska/open-bank-oss/commit/e8a1d6c0cbb8b203666822650c0d0a9f95d26203))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/incentive-service-v0.4.0...incentive-service-v0.5.0) (2026-08-27)


### Features

* **incentive:** add attributed customer reservations ([#7276](https://github.com/JiRaska/open-bank-oss/issues/7276)) ([462df84](https://github.com/JiRaska/open-bank-oss/commit/462df84283dde74f0987da8f03a3d46267f9b3ce))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/incentive-service-v0.3.0...incentive-service-v0.4.0) (2026-08-27)


### Features

* **incentive:** expose published offer catalogue ([#7253](https://github.com/JiRaska/open-bank-oss/issues/7253)) ([31fa6ce](https://github.com/JiRaska/open-bank-oss/commit/31fa6ced4e3f8db0117ff5e7c06954fee988a263))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/incentive-service-v0.2.0...incentive-service-v0.3.0) (2026-08-27)


### Features

* **incentive:** publish governed lifecycle events ([#7250](https://github.com/JiRaska/open-bank-oss/issues/7250)) ([cd984df](https://github.com/JiRaska/open-bank-oss/commit/cd984df09be82a45d9e8ae9bb3cb309ae5efe017))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/incentive-service-v0.1.0...incentive-service-v0.2.0) (2026-08-27)


### Features

* **incentive:** deliver governed promo reservations ([#7232](https://github.com/JiRaska/open-bank-oss/issues/7232)) ([e156db2](https://github.com/JiRaska/open-bank-oss/commit/e156db259299cfb2f614c98a86956ca9ab26d063))

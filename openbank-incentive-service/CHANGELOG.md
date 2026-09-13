# Changelog

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

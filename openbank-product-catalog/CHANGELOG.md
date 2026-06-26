# Changelog

## [0.4.1](https://github.com/JiRaska/open-bank/compare/product-catalog-v0.4.0...product-catalog-v0.4.1) (2026-06-23)


### Bug Fixes

* **infra:** commit swift-service-db Pod Identity association for WAL backups (ADR-0104 D4) ([#1793](https://github.com/JiRaska/open-bank/issues/1793)) ([49fc6dd](https://github.com/JiRaska/open-bank/commit/49fc6ddf988952f6281b4689f8c7eee1670a03f9))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/product-catalog-v0.3.1...product-catalog-v0.4.0) (2026-06-22)


### Features

* **product-catalog:** ADR-0105 — resolve products by canonical UUID (unify with account-service) ([#1694](https://github.com/JiRaska/open-bank/issues/1694)) ([adacd43](https://github.com/JiRaska/open-bank/commit/adacd4342451f25d03fd3a4b9d7df08228bbc965)), closes [#1691](https://github.com/JiRaska/open-bank/issues/1691)


### Bug Fixes

* **product-catalog:** resolve canonical UUID by product id, not code (ADR-0105) ([#1721](https://github.com/JiRaska/open-bank/issues/1721)) ([bf3fefb](https://github.com/JiRaska/open-bank/commit/bf3fefbb1fd0839e4af01f510993ba8af94f3cb9))

## [0.3.1](https://github.com/JiRaska/open-bank/compare/product-catalog-v0.3.0...product-catalog-v0.3.1) (2026-06-15)


### Bug Fixes

* **agent,balance,product-catalog:** unblock main CI — capability rename sync + /q/metrics registries ([#751](https://github.com/JiRaska/open-bank/issues/751)) ([a561b91](https://github.com/JiRaska/open-bank/commit/a561b91ee2f06ed71b23086a3a62d7db00a8c7ff))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/product-catalog-v0.2.0...product-catalog-v0.3.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/product-catalog-v0.1.0...product-catalog-v0.2.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))

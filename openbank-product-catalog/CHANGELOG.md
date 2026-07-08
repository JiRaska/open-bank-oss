# Changelog

## [0.8.4](https://github.com/JiRaska/open-bank-oss/compare/product-catalog-v0.8.3...product-catalog-v0.8.4) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.8.3](https://github.com/JiRaska/open-bank-oss/compare/product-catalog-v0.8.2...product-catalog-v0.8.3) (2026-07-06)


### Bug Fixes

* **product-catalog:** close two GraalVM native gotchas; measure ADR-0083 T1 promotion gate ([#275](https://github.com/JiRaska/open-bank-oss/issues/275)) ([7a2761c](https://github.com/JiRaska/open-bank-oss/commit/7a2761c2dbd86bb49eaacc8f94a6f662d7c26cb9))

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/product-catalog-v0.8.1...product-catalog-v0.8.2) (2026-07-06)


### Bug Fixes

* **product-catalog,governance:** correct false ADR-0083 T1 native claim; add real native build ([#258](https://github.com/JiRaska/open-bank-oss/issues/258)) ([aac0eb4](https://github.com/JiRaska/open-bank-oss/commit/aac0eb4d8ff31a7a0d2774d3e50cc577eb6b2329)), closes [#230](https://github.com/JiRaska/open-bank-oss/issues/230) [#253](https://github.com/JiRaska/open-bank-oss/issues/253)

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/product-catalog-v0.8.0...product-catalog-v0.8.1) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))
* **product-catalog:** sanitize fee-waiver diagnostics before logging (CodeQL java/log-injection) ([#151](https://github.com/JiRaska/open-bank-oss/issues/151)) ([90ed38f](https://github.com/JiRaska/open-bank-oss/commit/90ed38f5bf887354fd8e033523366ea4b5e6db17))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/product-catalog-v0.7.0...product-catalog-v0.8.0) (2026-06-29)


### Features

* **product-catalog:** surface machine-executable waiver rule on the fee schedule (ADR-0138 phase 1b) ([#2650](https://github.com/JiRaska/open-bank-oss/issues/2650)) ([83bd622](https://github.com/JiRaska/open-bank-oss/commit/83bd622433860c6b1b712d78e27907fca6033044))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/product-catalog-v0.6.0...product-catalog-v0.7.0) (2026-06-29)


### Features

* **product-catalog:** persist catalogue in Postgres with durable canonical UUIDs (ADR-0105 P1) ([#2603](https://github.com/JiRaska/open-bank-oss/issues/2603)) ([3848c0a](https://github.com/JiRaska/open-bank-oss/commit/3848c0a806789a2b05c97616b6718f1f0825383b))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/product-catalog-v0.5.0...product-catalog-v0.6.0) (2026-06-29)


### Features

* **product-catalog:** configuration-driven fee waiver rule engine (ADR-0138) ([#2642](https://github.com/JiRaska/open-bank-oss/issues/2642)) ([ce9a571](https://github.com/JiRaska/open-bank-oss/commit/ce9a5710c3d554a41266a7e5f5c907c1f37e84c0))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **clearing-simulator,product-catalog:** resolve detekt MagicNumber and CyclomaticComplexMethod violations ([#2230](https://github.com/JiRaska/open-bank-oss/issues/2230)) ([68e6aea](https://github.com/JiRaska/open-bank-oss/commit/68e6aea3d32b6f1ade0c4304a9395031e3e9e7e4))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **product-catalog:** expand Fee and eligibilitySegments call sites to resolve ktlint violations ([#2259](https://github.com/JiRaska/open-bank-oss/issues/2259)) ([4b3c351](https://github.com/JiRaska/open-bank-oss/commit/4b3c3513194f2fdcf688c4dd523312045901cc5e))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/product-catalog-v0.4.1...product-catalog-v0.5.0) (2026-06-27)


### Features

* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank-oss/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank-oss/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))


### Bug Fixes

* **clearing-simulator,product-catalog:** resolve detekt MagicNumber and CyclomaticComplexMethod violations ([#2230](https://github.com/JiRaska/open-bank-oss/issues/2230)) ([0359472](https://github.com/JiRaska/open-bank-oss/commit/03594725eac2844caaaa33eebffaa620c86d5512))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **product-catalog:** expand Fee and eligibilitySegments call sites to resolve ktlint violations ([#2259](https://github.com/JiRaska/open-bank-oss/issues/2259)) ([d6a780a](https://github.com/JiRaska/open-bank-oss/commit/d6a780ad483b1c5b32d12bbd7c575254863c68b8))

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/product-catalog-v0.4.0...product-catalog-v0.4.1) (2026-06-23)


### Bug Fixes

* **infra:** commit swift-service-db Pod Identity association for WAL backups (ADR-0104 D4) ([#1793](https://github.com/JiRaska/open-bank-oss/issues/1793)) ([49fc6dd](https://github.com/JiRaska/open-bank-oss/commit/49fc6ddf988952f6281b4689f8c7eee1670a03f9))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/product-catalog-v0.3.1...product-catalog-v0.4.0) (2026-06-22)


### Features

* **product-catalog:** ADR-0105 — resolve products by canonical UUID (unify with account-service) ([#1694](https://github.com/JiRaska/open-bank-oss/issues/1694)) ([adacd43](https://github.com/JiRaska/open-bank-oss/commit/adacd4342451f25d03fd3a4b9d7df08228bbc965)), closes [#1691](https://github.com/JiRaska/open-bank-oss/issues/1691)


### Bug Fixes

* **product-catalog:** resolve canonical UUID by product id, not code (ADR-0105) ([#1721](https://github.com/JiRaska/open-bank-oss/issues/1721)) ([bf3fefb](https://github.com/JiRaska/open-bank-oss/commit/bf3fefbb1fd0839e4af01f510993ba8af94f3cb9))

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/product-catalog-v0.3.0...product-catalog-v0.3.1) (2026-06-15)


### Bug Fixes

* **agent,balance,product-catalog:** unblock main CI — capability rename sync + /q/metrics registries ([#751](https://github.com/JiRaska/open-bank-oss/issues/751)) ([a561b91](https://github.com/JiRaska/open-bank-oss/commit/a561b91ee2f06ed71b23086a3a62d7db00a8c7ff))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/product-catalog-v0.2.0...product-catalog-v0.3.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/product-catalog-v0.1.0...product-catalog-v0.2.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))

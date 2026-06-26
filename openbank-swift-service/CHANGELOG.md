# Changelog

## [0.6.0](https://github.com/JiRaska/open-bank/compare/swift-service-v0.5.0...swift-service-v0.6.0) (2026-06-25)


### Features

* **swift:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2095](https://github.com/JiRaska/open-bank/issues/2095)) ([192d157](https://github.com/JiRaska/open-bank/commit/192d1573c4b3e9bbeb7ad58f90554dddecf68dfe))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/swift-service-v0.4.0...swift-service-v0.5.0) (2026-06-25)


### Features

* **domestic-payment,swift,libs:** ADR-0104 D4 — SchemeGateway fan-out to domestic & swift rails ([331e7dd](https://github.com/JiRaska/open-bank/commit/331e7ddb148c021d951521f570cc39c75aec5a3c))
* **fraud,swift:** add Pact consumer contracts for transaction + SWIFT events (ADR-0092, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2024](https://github.com/JiRaska/open-bank/issues/2024)) ([2cb470b](https://github.com/JiRaska/open-bank/commit/2cb470bb18db463d9707ca85ed3913ef9c03712e))
* **swift-service:** trigger transaction-service settlement after scheme ACSC (ADR-0108) ([#1872](https://github.com/JiRaska/open-bank/issues/1872)) ([d3fcf2c](https://github.com/JiRaska/open-bank/commit/d3fcf2c18b8771f105481016f4ea241d01aea4ab))
* **swift,transaction:** add Pact provider verification for message contracts (ADR-0092) ([#2063](https://github.com/JiRaska/open-bank/issues/2063)) ([9d0ead6](https://github.com/JiRaska/open-bank/commit/9d0ead608fb576b78cd17f93da5c35232f328d64))
* **transaction,payment:** ADR-0103 D2 — stamp rail + instructionType at settlement (transaction-service + 4 rails) ([#1940](https://github.com/JiRaska/open-bank/issues/1940)) ([785ca02](https://github.com/JiRaska/open-bank/commit/785ca024d434c845dadade0190551fdd18da17a9))


### Bug Fixes

* **swift-service,domestic-payment,sepa-instant:** ADR-0104 D4 remaining — port extraction, repo fix, tests, threat models ([fbce147](https://github.com/JiRaska/open-bank/commit/fbce1475004a90d816aeadf5f049783ffc086e04))
* **swift-service:** add @Version optimistic lock to SwiftMessageEntity (issue [#1833](https://github.com/JiRaska/open-bank/issues/1833)) ([a023932](https://github.com/JiRaska/open-bank/commit/a023932abd351e5150a501826e3d05e4862c885e))
* **swift-service:** migrate value_date to date type and validate YYYYMMDD format ([#1857](https://github.com/JiRaska/open-bank/issues/1857)) ([e4549e3](https://github.com/JiRaska/open-bank/commit/e4549e33b675b91f451e1204409e5a767d4ea291))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/swift-service-v0.3.0...swift-service-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **swift:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#808](https://github.com/JiRaska/open-bank/issues/808)) ([d3e5a26](https://github.com/JiRaska/open-bank/commit/d3e5a2675e3a23a5e76f43bec54cbcc011940ddf))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/swift-service-v0.2.0...swift-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/swift-service-v0.1.0...swift-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))

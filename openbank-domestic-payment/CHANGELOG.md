# Changelog

## [0.8.0](https://github.com/JiRaska/open-bank/compare/domestic-payment-v0.7.0...domestic-payment-v0.8.0) (2026-06-25)


### Features

* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))

## [0.7.0](https://github.com/JiRaska/open-bank/compare/domestic-payment-v0.6.0...domestic-payment-v0.7.0) (2026-06-25)


### Features

* **domestic-payment,sepa-payment:** inject Clock into SettlementAdapter and Kafka publisher (ADR-0100) ([#2064](https://github.com/JiRaska/open-bank/issues/2064)) ([85d890e](https://github.com/JiRaska/open-bank/commit/85d890e8bcf0c1e5d79b76134834cb19bf90ee84)), closes [#1612](https://github.com/JiRaska/open-bank/issues/1612)


### Bug Fixes

* **domestic-payment:** use @Dependent scope for ClockProducer + fix test import ordering ([#2067](https://github.com/JiRaska/open-bank/issues/2067)) ([872fb1b](https://github.com/JiRaska/open-bank/commit/872fb1b45d11de161ed3e46d121769b07c62598a)), closes [#1612](https://github.com/JiRaska/open-bank/issues/1612)

## [0.6.0](https://github.com/JiRaska/open-bank/compare/domestic-payment-v0.5.0...domestic-payment-v0.6.0) (2026-06-25)


### Features

* **domestic-payment,customer-edge:** add INSTANT priority; forward priority from app body ([e683832](https://github.com/JiRaska/open-bank/commit/e683832c0f71a69531d2a8e53bbca94da22b2749))
* **domestic-payment,customer-edge:** add INSTANT priority; forward priority from app body ([9fddff9](https://github.com/JiRaska/open-bank/commit/9fddff995d15fe94b6db4ae9eb05732a99938cff))


### Bug Fixes

* **domestic-payment:** inject Clock into domain/application layers (ADR-0100, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2011](https://github.com/JiRaska/open-bank/issues/2011)) ([5df146d](https://github.com/JiRaska/open-bank/commit/5df146d91829fda1e2521c91f89f522f5b23e7a9))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/domestic-payment-v0.4.0...domestic-payment-v0.5.0) (2026-06-25)


### Features

* **domestic-payment,swift,libs:** ADR-0104 D4 — SchemeGateway fan-out to domestic & swift rails ([331e7dd](https://github.com/JiRaska/open-bank/commit/331e7ddb148c021d951521f570cc39c75aec5a3c))
* **domestic-payment:** add Temporal durable workflow for ČOBS payment orchestration (ADR-0101 P2) ([#1470](https://github.com/JiRaska/open-bank/issues/1470)) ([6372d29](https://github.com/JiRaska/open-bank/commit/6372d290155146456ded815c582d190e7e37eb0d))
* **domestic-payment:** trigger transaction-service settlement after scheme ACSC (ADR-0108) ([#1870](https://github.com/JiRaska/open-bank/issues/1870)) ([cecc93b](https://github.com/JiRaska/open-bank/commit/cecc93bf82638438fd7f1177b2a8dc65f381a8fb))
* **transaction,payment:** ADR-0103 D2 — stamp rail + instructionType at settlement (transaction-service + 4 rails) ([#1940](https://github.com/JiRaska/open-bank/issues/1940)) ([785ca02](https://github.com/JiRaska/open-bank/commit/785ca024d434c845dadade0190551fdd18da17a9))


### Bug Fixes

* **admin-ui:** governance data freshness — ADR-0082 renumber, MONEY_PATH fraud-service, STATIC_CANDIDATES ([f69ba3b](https://github.com/JiRaska/open-bank/commit/f69ba3baaac7ab91b25a0c6a4f7945574f0f53b5))
* **domestic-payment:** clear pre-existing detekt/ktlint/test drift ([#1696](https://github.com/JiRaska/open-bank/issues/1696)) ([1fd72fa](https://github.com/JiRaska/open-bank/commit/1fd72faca6648617ab06bdf11d1789f97fb6142d))
* **domestic-payment:** configure the OIDC client so service tokens are minted (ADR-0104 D3) ([#1784](https://github.com/JiRaska/open-bank/issues/1784)) ([f5a1cf0](https://github.com/JiRaska/open-bank/commit/f5a1cf09475559a895a72ae552dccfc16e8e6750))
* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank/commit/92dc2d636d857b526c8276e2647de440c540577b))
* **sdd:** add sdd_outbox_seq to V2 migration + fix outbox IT Vert.x context ([fceadf4](https://github.com/JiRaska/open-bank/commit/fceadf4a679eddc7cbde749839846cc83bf8d5d5)), closes [#1360](https://github.com/JiRaska/open-bank/issues/1360)
* **sepa-payment:** remove blank line before closing brace (ktlint) ([#1362](https://github.com/JiRaska/open-bank/issues/1362)) ([65a698d](https://github.com/JiRaska/open-bank/commit/65a698d6a92e2772b4d21406cf97bed983f8cc8f))
* **swift-service,domestic-payment,sepa-instant:** ADR-0104 D4 remaining — port extraction, repo fix, tests, threat models ([fbce147](https://github.com/JiRaska/open-bank/commit/fbce1475004a90d816aeadf5f049783ffc086e04))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/domestic-payment-v0.3.0...domestic-payment-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#814](https://github.com/JiRaska/open-bank/issues/814)) ([5710f35](https://github.com/JiRaska/open-bank/commit/5710f35c413b577883309789c963732f66927729))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **sepa-instant:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#685](https://github.com/JiRaska/open-bank/issues/685)) ([de3124e](https://github.com/JiRaska/open-bank/commit/de3124eb125755c33a35d21c8bdee3208b539c69))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/domestic-payment-v0.2.0...domestic-payment-v0.3.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/domestic-payment-v0.1.2...domestic-payment-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **payments:** remove dead incoming Kafka channels — no @Incoming consumer ([#377](https://github.com/JiRaska/open-bank/issues/377)) ([5fbdda7](https://github.com/JiRaska/open-bank/commit/5fbdda796d4201b9ef1f57d41c76a00b18a5216b))
* **payments:** use property expression in Kafka channel bootstrap.servers ([#373](https://github.com/JiRaska/open-bank/issues/373)) ([32507ee](https://github.com/JiRaska/open-bank/commit/32507eeda72bec17c92f85169d72759ed02f1c4a))

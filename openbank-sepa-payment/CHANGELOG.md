# Changelog

## [0.7.0](https://github.com/JiRaska/open-bank/compare/sepa-payment-v0.6.0...sepa-payment-v0.7.0) (2026-06-25)


### Features

* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))

## [0.6.0](https://github.com/JiRaska/open-bank/compare/sepa-payment-v0.5.1...sepa-payment-v0.6.0) (2026-06-25)


### Features

* **domestic-payment,sepa-payment:** inject Clock into SettlementAdapter and Kafka publisher (ADR-0100) ([#2064](https://github.com/JiRaska/open-bank/issues/2064)) ([85d890e](https://github.com/JiRaska/open-bank/commit/85d890e8bcf0c1e5d79b76134834cb19bf90ee84)), closes [#1612](https://github.com/JiRaska/open-bank/issues/1612)


### Bug Fixes

* **balance,sepa-payment,fraud:** @Dependent scope on ClockProducer + inject Clock into fraud persistence (ADR-0100) ([#2081](https://github.com/JiRaska/open-bank/issues/2081)) ([fc1a129](https://github.com/JiRaska/open-bank/commit/fc1a129cbfee4b5db41dbf4334f3dbe9d5e621c8))

## [0.5.1](https://github.com/JiRaska/open-bank/compare/sepa-payment-v0.5.0...sepa-payment-v0.5.1) (2026-06-25)


### Bug Fixes

* **sepa-payment:** inject Clock into domain/application layers (ADR-0100, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2009](https://github.com/JiRaska/open-bank/issues/2009)) ([c2f7a0c](https://github.com/JiRaska/open-bank/commit/c2f7a0c5904437df6d12b20b8c8a9538d1e29144))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/sepa-payment-v0.4.0...sepa-payment-v0.5.0) (2026-06-25)


### Features

* **sepa-payment:** handle inbound pacs.004 returns — PROCESSING→RETURNED + ledger reversal (ADR-0109) ([#1931](https://github.com/JiRaska/open-bank/issues/1931)) ([7b80be7](https://github.com/JiRaska/open-bank/commit/7b80be71ef0405b3d6ce482c1ef16e1bdf46259c))
* **sepa-payment:** SEPA SCT pilot — submit real pacs.008 to the scheme gateway (ADR-0104 D3) ([#1722](https://github.com/JiRaska/open-bank/issues/1722)) ([a8ae6b9](https://github.com/JiRaska/open-bank/commit/a8ae6b93a0c0b1a60c10edb4a9ae0b4a4abf85d4))
* **sepa-payment:** submit real pacs.008 to the scheme gateway (ADR-0104 D3) ([#1723](https://github.com/JiRaska/open-bank/issues/1723)) ([855ca3d](https://github.com/JiRaska/open-bank/commit/855ca3d973a284f24c0ef6a3996a3c98b7d880c2))
* **sepa-payment:** Temporal durable workflow — ADR-0101 P1 ([#1449](https://github.com/JiRaska/open-bank/issues/1449)) ([f718066](https://github.com/JiRaska/open-bank/commit/f718066c0a90ae62c6f9e36554917a198b1832fb))
* **sepa-payment:** trigger transaction-service settlement after scheme ACSC (ADR-0108) ([#1869](https://github.com/JiRaska/open-bank/issues/1869)) ([a3d4925](https://github.com/JiRaska/open-bank/commit/a3d492560b01259643f7be0543186a82a2011f07))
* **transaction,payment:** ADR-0103 D2 — stamp rail + instructionType at settlement (transaction-service + 4 rails) ([#1940](https://github.com/JiRaska/open-bank/issues/1940)) ([785ca02](https://github.com/JiRaska/open-bank/commit/785ca024d434c845dadade0190551fdd18da17a9))


### Bug Fixes

* **admin-ui:** governance data freshness — ADR-0082 renumber, MONEY_PATH fraud-service, STATIC_CANDIDATES ([f69ba3b](https://github.com/JiRaska/open-bank/commit/f69ba3baaac7ab91b25a0c6a4f7945574f0f53b5))
* **gitops:** correct transform processor OTTL syntax — rum-gateway CrashLoopBackOff ([#1935](https://github.com/JiRaska/open-bank/issues/1935)) ([10fecc2](https://github.com/JiRaska/open-bank/commit/10fecc2ff6d8e6353ec14c4a9e1a0b344294f687))
* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank/commit/92dc2d636d857b526c8276e2647de440c540577b))
* **sdd:** add sdd_outbox_seq to V2 migration + fix outbox IT Vert.x context ([fceadf4](https://github.com/JiRaska/open-bank/commit/fceadf4a679eddc7cbde749839846cc83bf8d5d5)), closes [#1360](https://github.com/JiRaska/open-bank/issues/1360)
* **sepa-payment:** attach OIDC bearer explicitly on scheme submission (ADR-0104 BUG [#3](https://github.com/JiRaska/open-bank/issues/3)) ([#1779](https://github.com/JiRaska/open-bank/issues/1779)) ([4bd05e6](https://github.com/JiRaska/open-bank/commit/4bd05e661499cc1e7c1ddf3cb4819cb127aa014f))
* **sepa-payment:** configure the OIDC client so service tokens are minted (ADR-0104 D3 Bug [#3](https://github.com/JiRaska/open-bank/issues/3)) ([#1782](https://github.com/JiRaska/open-bank/issues/1782)) ([b0177d4](https://github.com/JiRaska/open-bank/commit/b0177d46bebfa69f858d901306b997b5d3a74041))
* **sepa-payment:** fail closed on an unparseable pacs.002 (ADR-0104 D3) ([#1735](https://github.com/JiRaska/open-bank/issues/1735)) ([50331a0](https://github.com/JiRaska/open-bank/commit/50331a0de412f7f14cc31c15a0bc4194cf84511d))
* **sepa-payment:** remove blank line before closing brace (ktlint) ([#1362](https://github.com/JiRaska/open-bank/issues/1362)) ([65a698d](https://github.com/JiRaska/open-bank/commit/65a698d6a92e2772b4d21406cf97bed983f8cc8f))
* **sepa-payment:** run Temporal screening activities on a Vert.x context (ADR-0104 D3) ([#1738](https://github.com/JiRaska/open-bank/issues/1738)) ([d4f787e](https://github.com/JiRaska/open-bank/commit/d4f787eacacf8444c5b52d67ee8e740bee6eb361))
* **sepa-payment:** submit pacs.008 to the scheme from the Temporal workflow (ADR-0104 D3) ([#1774](https://github.com/JiRaska/open-bank/issues/1774)) ([049cb95](https://github.com/JiRaska/open-bank/commit/049cb95a9284bfa2476a1bbad1117f6ff3bafffc))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/sepa-payment-v0.3.0...sepa-payment-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **observability:** add DomainMetrics façade and wire into sepa-payment (ADR-0077 Phase 2) ([#677](https://github.com/JiRaska/open-bank/issues/677)) ([7c09a12](https://github.com/JiRaska/open-bank/commit/7c09a1216d7e04d84f461279ae141d9198a63ad1))
* **sepa-payment:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#812](https://github.com/JiRaska/open-bank/issues/812)) ([0957f5d](https://github.com/JiRaska/open-bank/commit/0957f5d89dfb1a024de5a63f364b1f8849602d92))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/sepa-payment-v0.2.0...sepa-payment-v0.3.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/sepa-payment-v0.1.2...sepa-payment-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **payments:** remove dead incoming Kafka channels — no @Incoming consumer ([#377](https://github.com/JiRaska/open-bank/issues/377)) ([5fbdda7](https://github.com/JiRaska/open-bank/commit/5fbdda796d4201b9ef1f57d41c76a00b18a5216b))
* **payments:** use property expression in Kafka channel bootstrap.servers ([#373](https://github.com/JiRaska/open-bank/issues/373)) ([32507ee](https://github.com/JiRaska/open-bank/commit/32507eeda72bec17c92f85169d72759ed02f1c4a))

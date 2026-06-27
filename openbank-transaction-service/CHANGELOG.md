# Changelog

## [1.10.0](https://github.com/JiRaska/open-bank/compare/transaction-service-v1.9.0...transaction-service-v1.10.0) (2026-06-27)


### Features

* **observability:** RUM gateway cardinality budget, attribute audit, HPA (ADR-0088 O1-O3) ([#2208](https://github.com/JiRaska/open-bank/issues/2208)) ([b7f0849](https://github.com/JiRaska/open-bank/commit/b7f08494cf923f66dbd910e92522fb9453394de7))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [1.9.0](https://github.com/JiRaska/open-bank/compare/transaction-service-v1.8.0...transaction-service-v1.9.0) (2026-06-25)


### Features

* **transaction:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2093](https://github.com/JiRaska/open-bank/issues/2093)) ([cf97810](https://github.com/JiRaska/open-bank/commit/cf9781054aa2d15a78a2da6d789988737b3c8da6))

## [1.8.0](https://github.com/JiRaska/open-bank/compare/transaction-service-v1.7.0...transaction-service-v1.8.0) (2026-06-25)


### Features

* **swift,transaction:** add Pact provider verification for message contracts (ADR-0092) ([#2063](https://github.com/JiRaska/open-bank/issues/2063)) ([9d0ead6](https://github.com/JiRaska/open-bank/commit/9d0ead608fb576b78cd17f93da5c35232f328d64))

## [1.7.0](https://github.com/JiRaska/open-bank/compare/transaction-service-v1.6.0...transaction-service-v1.7.0) (2026-06-25)


### Features

* **infra:** ADR-0099 Tier 2 — OIDC client secret + JWT key rotator CronJob ([#2016](https://github.com/JiRaska/open-bank/issues/2016)) ([83ae8fa](https://github.com/JiRaska/open-bank/commit/83ae8fa8e0779ba96da8aff2dad641eed9bc2c8d))
* **transaction:** consume payment.scheme-accepted to settle rail payments (ADR-0108) ([#1999](https://github.com/JiRaska/open-bank/issues/1999)) ([3e76da5](https://github.com/JiRaska/open-bank/commit/3e76da5897ca9db463ebcde4f8b9b04d7d2327d2))
* **transaction:** drop deprecated channel column (ADR-0103 D4) ([#2028](https://github.com/JiRaska/open-bank/issues/2028)) ([dfa479f](https://github.com/JiRaska/open-bank/commit/dfa479fc50993f68c31ef173e4247882a447e59a))
* **transaction:** stamp PaymentRail + InstructionType on transactions and events (ADR-0103 D2) ([#1995](https://github.com/JiRaska/open-bank/issues/1995)) ([6f748d7](https://github.com/JiRaska/open-bank/commit/6f748d79b917f35850e79af1e9aa67973dd2615b))


### Bug Fixes

* **transaction:** address money-path pre-merge review findings (ADR-0108) ([#2001](https://github.com/JiRaska/open-bank/issues/2001)) ([050cf13](https://github.com/JiRaska/open-bank/commit/050cf130933bf2c4703cc89d8824022b71b6645d))
* **transaction:** inject Clock into domain/application layers (ADR-0100, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2012](https://github.com/JiRaska/open-bank/issues/2012)) ([48f0a47](https://github.com/JiRaska/open-bank/commit/48f0a47aaf95cd317c464f1cb2776128dcc82361))

## [1.6.0](https://github.com/JiRaska/open-bank/compare/transaction-service-v1.5.0...transaction-service-v1.6.0) (2026-06-25)


### Features

* **pockets:** convert pocket balance to primary currency (ADR-0107) ([#1797](https://github.com/JiRaska/open-bank/issues/1797)) ([c3df994](https://github.com/JiRaska/open-bank/commit/c3df99430d6976555b052fefd78186f3b55de795))
* **transaction,payment:** ADR-0103 D2 — stamp rail + instructionType at settlement (transaction-service + 4 rails) ([#1940](https://github.com/JiRaska/open-bank/issues/1940)) ([785ca02](https://github.com/JiRaska/open-bank/commit/785ca024d434c845dadade0190551fdd18da17a9))
* **transaction:** add @Authorize resource-gate on REST endpoints (ADR-0034 D3) ([#1287](https://github.com/JiRaska/open-bank/issues/1287)) ([d3e4d14](https://github.com/JiRaska/open-bank/commit/d3e4d140a41db7a3a916d811648a6511c0f3dd79))
* **transaction:** add POST /{id}/reverse endpoint for R-transaction returns (ADR-0109) ([#1930](https://github.com/JiRaska/open-bank/issues/1930)) ([f1e0a4a](https://github.com/JiRaska/open-bank/commit/f1e0a4ad67d85f7056a9fc0801813606ce297253))
* **transaction:** ADR-0103 D1 — payment-rail + instruction-type vocabulary & persistence ([#1672](https://github.com/JiRaska/open-bank/issues/1672)) ([70d26b7](https://github.com/JiRaska/open-bank/commit/70d26b78453df25298cb3e4229c46bb50222c2b0))
* **transaction:** ADR-0103 D4 — backfill rail/instructionType, MCC wire, deprecate channel ([#1944](https://github.com/JiRaska/open-bank/issues/1944)) ([21d6754](https://github.com/JiRaska/open-bank/commit/21d67546aad6b5f58702419bf70c09a439c70af4))


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **gitops:** raise HolmesGPT relay LLM timeout 180s→300s ([#1918](https://github.com/JiRaska/open-bank/issues/1918)) ([5a96e40](https://github.com/JiRaska/open-bank/commit/5a96e405e96de760c2e40379ffc6637c445976c7))
* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank/commit/92dc2d636d857b526c8276e2647de440c540577b))
* **transaction:** sync openapi.yaml info.version to lockstep 1.5.0 ([#1380](https://github.com/JiRaska/open-bank/issues/1380)) ([9ac52ec](https://github.com/JiRaska/open-bank/commit/9ac52ec2b6d0e5f5f422a69edf4e902fcbb784bb))

## [1.5.0](https://github.com/JiRaska/open-bank/compare/transaction-service-v1.4.0...transaction-service-v1.5.0) (2026-06-15)


### Features

* **security:** customer payment non-repudiation — SCA settlement gate, identity threading, audit hash chain (ADR-0086) ([#900](https://github.com/JiRaska/open-bank/issues/900)) ([fcc1e52](https://github.com/JiRaska/open-bank/commit/fcc1e52b247b0eb61b9ee8d5332f110984a6fb33))

## [1.4.0](https://github.com/JiRaska/open-bank/compare/transaction-service-v1.3.0...transaction-service-v1.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **transaction:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#813](https://github.com/JiRaska/open-bank/issues/813)) ([965be94](https://github.com/JiRaska/open-bank/commit/965be94c7d61d521371200c3d5d5032cd9777c6a))

## [1.3.0](https://github.com/JiRaska/open-bank/compare/transaction-service-v1.2.0...transaction-service-v1.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **transaction:** credit beneficiary balance + wire ledger/balance URLs ([#554](https://github.com/JiRaska/open-bank/issues/554)) ([9a83156](https://github.com/JiRaska/open-bank/commit/9a8315687355f10cbd04f4e210f9c1c723347c12))

## [1.2.0](https://github.com/JiRaska/open-bank/compare/transaction-service-v1.1.0...transaction-service-v1.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **transaction:** drop generic Exception mapper, defer to libs (ADR-0049 D4) ([#336](https://github.com/JiRaska/open-bank/issues/336)) ([7e8eac3](https://github.com/JiRaska/open-bank/commit/7e8eac356cf2405cf9f81c4c82fcf695624cd6dd))

# Changelog

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.9.0...settlement-service-v0.9.1) (2026-09-02)


### Bug Fixes

* **settlement:** make the ledger compensation reachable and truthful ([#6410](https://github.com/JiRaska/open-bank-oss/issues/6410)) ([#6481](https://github.com/JiRaska/open-bank-oss/issues/6481)) ([a312b17](https://github.com/JiRaska/open-bank-oss/commit/a312b17dec0a1ba1fae219d62c13c5b7a920a8c0))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.8.0...settlement-service-v0.9.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.7.3...settlement-service-v0.8.0) (2026-08-22)


### Features

* **settlement:** emit domain metrics and alert on them ([#5705](https://github.com/JiRaska/open-bank-oss/issues/5705)) ([#5723](https://github.com/JiRaska/open-bank-oss/issues/5723)) ([c84cdd9](https://github.com/JiRaska/open-bank-oss/commit/c84cdd965c4a1dde8e129fd446c27cba22615eac))

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.7.2...settlement-service-v0.7.3) (2026-08-22)


### Bug Fixes

* **settlement:** remove the ledger compensation that could never run ([#6484](https://github.com/JiRaska/open-bank-oss/issues/6484)) ([2da87cf](https://github.com/JiRaska/open-bank-oss/commit/2da87cf6f35ef52572629e6f081b330cb8f74fd3)), closes [#6410](https://github.com/JiRaska/open-bank-oss/issues/6410)

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.7.1...settlement-service-v0.7.2) (2026-08-22)


### Bug Fixes

* **settlement:** stop rejecting a settlement whose compensation failed ([#6369](https://github.com/JiRaska/open-bank-oss/issues/6369)) ([fc82f8b](https://github.com/JiRaska/open-bank-oss/commit/fc82f8b581ce43b5b1599cb082383cfd0d25eaad))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.7.0...settlement-service-v0.7.1) (2026-08-21)


### Bug Fixes

* **settlement:** publish stranded-age gauges for every non-terminal status ([#6284](https://github.com/JiRaska/open-bank-oss/issues/6284)) ([f581d11](https://github.com/JiRaska/open-bank-oss/commit/f581d118d4bb4bdff78c71eed434053eb3110a26)), closes [#6037](https://github.com/JiRaska/open-bank-oss/issues/6037)

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.6.1...settlement-service-v0.7.0) (2026-08-21)


### Features

* **settlement:** alert on settlements that stopped advancing ([#6036](https://github.com/JiRaska/open-bank-oss/issues/6036)) ([83d9584](https://github.com/JiRaska/open-bank-oss/commit/83d9584d8816cd9258c637b4b7e3cdc5caa64d83)), closes [#5705](https://github.com/JiRaska/open-bank-oss/issues/5705)

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.6.0...settlement-service-v0.6.1) (2026-08-21)


### Bug Fixes

* **settlement:** actually reverse money in the compensation path ([#6037](https://github.com/JiRaska/open-bank-oss/issues/6037)) ([#6048](https://github.com/JiRaska/open-bank-oss/issues/6048)) ([a0ab439](https://github.com/JiRaska/open-bank-oss/commit/a0ab439084f5e5cba39a6bd7b067f3f80698f51e))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.5.1...settlement-service-v0.6.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.5.0...settlement-service-v0.5.1) (2026-08-16)


### Bug Fixes

* **infra:** give six services an OIDC client they can actually mint from ([#4990](https://github.com/JiRaska/open-bank-oss/issues/4990)) ([f43f88c](https://github.com/JiRaska/open-bank-oss/commit/f43f88c815fd50c32ef797147c6cbc57f060cab0))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.4.10...settlement-service-v0.5.0) (2026-08-08)


### Features

* **ci:** check gitops workload env hostnames, and fix the four it finds ([#3974](https://github.com/JiRaska/open-bank-oss/issues/3974)) ([123633f](https://github.com/JiRaska/open-bank-oss/commit/123633fcdb7ce6bfa5b949bd1610196618e36108))

## [0.4.10](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.4.9...settlement-service-v0.4.10) (2026-08-02)


### Bug Fixes

* **infra:** give the five money-path services the JDBC datasource Flyway migrates ([#3192](https://github.com/JiRaska/open-bank-oss/issues/3192)) ([d9b31d5](https://github.com/JiRaska/open-bank-oss/commit/d9b31d5d2cccd169ec6ce7e8e971d5853ef952f1)), closes [#3080](https://github.com/JiRaska/open-bank-oss/issues/3080)

## [0.4.9](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.4.8...settlement-service-v0.4.9) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.4.8](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.4.7...settlement-service-v0.4.8) (2026-07-24)


### Bug Fixes

* **settlement:** Temporal-only orchestration, retire the legacy saga (ADR-0120 Phase 6, [#1917](https://github.com/JiRaska/open-bank-oss/issues/1917) — 1/3) ([#2066](https://github.com/JiRaska/open-bank-oss/issues/2066)) ([0a9cff7](https://github.com/JiRaska/open-bank-oss/commit/0a9cff79c02a116554e7381daed36382107f523a))

## [0.4.7](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.4.6...settlement-service-v0.4.7) (2026-07-18)


### Bug Fixes

* **settlement:** emit audit events on every settlement state transition ([#1637](https://github.com/JiRaska/open-bank-oss/issues/1637)) ([5bb7f41](https://github.com/JiRaska/open-bank-oss/commit/5bb7f410e8937f7d2dab16b40ea462a7d9e6db06)), closes [#1502](https://github.com/JiRaska/open-bank-oss/issues/1502)

## [0.4.6](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.4.5...settlement-service-v0.4.6) (2026-07-12)


### Bug Fixes

* **settlement:** missing createdBy bug + add ledger pact coverage ([#468](https://github.com/JiRaska/open-bank-oss/issues/468)) ([#848](https://github.com/JiRaska/open-bank-oss/issues/848)) ([eee4842](https://github.com/JiRaska/open-bank-oss/commit/eee48427bd69b55300109d6ed8c6812299d85afd))

## [0.4.5](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.4.4...settlement-service-v0.4.5) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.4.4](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.4.3...settlement-service-v0.4.4) (2026-07-07)


### Security

* **settlement:** enforce OPA authorization on settlement endpoints (ADR-0034 Phase 5) ([#409](https://github.com/JiRaska/open-bank-oss/issues/409)) ([747dfcf](https://github.com/JiRaska/open-bank-oss/commit/747dfcfc13ee94966f1e52e1af2e941e48246d4c)), closes [#266](https://github.com/JiRaska/open-bank-oss/issues/266) [#307](https://github.com/JiRaska/open-bank-oss/issues/307)

## [0.4.3](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.4.2...settlement-service-v0.4.3) (2026-07-02)


### Security

* **settlement:** sanitize idempotencyKey before logging (CodeQL java/log-injection) ([#149](https://github.com/JiRaska/open-bank-oss/issues/149)) ([1fa76fc](https://github.com/JiRaska/open-bank-oss/commit/1fa76fcd3a01e749bf0d35b034dd7187e33cfc82))

## [0.4.2](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.4.1...settlement-service-v0.4.2) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)
* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([c61fcaf](https://github.com/JiRaska/open-bank-oss/commit/c61fcafde76be08f716c710462be70752073aba1))

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.4.0...settlement-service-v0.4.1) (2026-06-28)


### Bug Fixes

* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([35c6a63](https://github.com/JiRaska/open-bank-oss/commit/35c6a6309e10c0a56558d93de5c494a89da72ceb))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.3.1...settlement-service-v0.4.0) (2026-06-27)


### Features

* **tpp-registry,statement,onboarding,agent,settlement,sdd:** inject Clock (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2138](https://github.com/JiRaska/open-bank-oss/issues/2138)) ([baa0d03](https://github.com/JiRaska/open-bank-oss/commit/baa0d03bcef7a1cd48cb7e115410ab625a26acde))

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.3.0...settlement-service-v0.3.1) (2026-06-25)


### Bug Fixes

* **settlement:** inject Clock into domain/application layers (ADR-0100, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2007](https://github.com/JiRaska/open-bank-oss/issues/2007)) ([03ce1b9](https://github.com/JiRaska/open-bank-oss/commit/03ce1b996b919fc540537a94c7c200f75f3aa1b1))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.2.0...settlement-service-v0.3.0) (2026-06-25)


### Features

* **settlement:** DB-backed repository + REST origination endpoint (ADR-0101 P3) ([#1840](https://github.com/JiRaska/open-bank-oss/issues/1840)) ([9e4cfd2](https://github.com/JiRaska/open-bank-oss/commit/9e4cfd20fd68baf36bf9fb26d6b21308a6e65788))


### Bug Fixes

* **settlement:** run Temporal activities on a Vert.x context (reactive Panache) ([#1855](https://github.com/JiRaska/open-bank-oss/issues/1855)) ([8dd371d](https://github.com/JiRaska/open-bank-oss/commit/8dd371dac7b03aca0890a1ceb19ca073dc3c911e))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/settlement-service-v0.1.0...settlement-service-v0.2.0) (2026-06-21)


### Features

* **settlement:** add Temporal durable workflow for settlement orchestration (ADR-0101 P3) ([#1471](https://github.com/JiRaska/open-bank-oss/issues/1471)) ([caa8c40](https://github.com/JiRaska/open-bank-oss/commit/caa8c402bf5fa2f3efc2cacfa552cab545520ff6))
* **settlement:** wire real balance/ledger adapters + OPA activity policy ([#1522](https://github.com/JiRaska/open-bank-oss/issues/1522)) ([605d6cf](https://github.com/JiRaska/open-bank-oss/commit/605d6cf81601696cd2b5b8fb2227dbbbd1f602d3))


### Bug Fixes

* **ci:** settlement governance.yaml + upload-artifact continue-on-error ([290b483](https://github.com/JiRaska/open-bank-oss/commit/290b4830e95a7d641936b6b41e2813e37ec13958))
* **settlement:** add reverseCredit compensation + fix all stub reversals (ADR-0101 P3) ([ea87e9b](https://github.com/JiRaska/open-bank-oss/commit/ea87e9b807882e77ce5a3dd80ab594b3169284b0))
* **settlement:** fix boot — TemporalConfig missing serverUrl/namespace, wrong port ([#1511](https://github.com/JiRaska/open-bank-oss/issues/1511)) ([19303fa](https://github.com/JiRaska/open-bank-oss/commit/19303fa0cf943bf885ffa7da06cd7afa6d28e156))
* **settlement:** fix management host binding and datasource username default ([89f7276](https://github.com/JiRaska/open-bank-oss/commit/89f72763c4f6ea558e49626421e9adf6715905d3))
* **settlement:** remove server-url/namespace from YAML — SRCFG00050 at startup ([#1523](https://github.com/JiRaska/open-bank-oss/issues/1523)) ([805b4c8](https://github.com/JiRaska/open-bank-oss/commit/805b4c8bfd60f040f99311a80686c3903736a539))
* **settlement:** wire real GL account UUIDs for ledger booking ([#1609](https://github.com/JiRaska/open-bank-oss/issues/1609)) ([d2eeb94](https://github.com/JiRaska/open-bank-oss/commit/d2eeb94a5a1d8240f0b6008eb370f0c0b99f3bc9))
* **statement:** skip NOT_VIABLE debris accounts in period-close instead of failing ([#862](https://github.com/JiRaska/open-bank-oss/issues/862)) ([#1554](https://github.com/JiRaska/open-bank-oss/issues/1554)) ([e316032](https://github.com/JiRaska/open-bank-oss/commit/e316032816ce7a25e689661552ab0e9591a5c237))

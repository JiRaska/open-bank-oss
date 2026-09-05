# Changelog

## [1.21.1](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.21.0...transaction-service-v1.21.1) (2026-09-03)


### Bug Fixes

* **transaction:** entity timestamp defaults EPOCH -&gt; Instant.now() ([#8463](https://github.com/JiRaska/open-bank-oss/issues/8463)) ([941a93b](https://github.com/JiRaska/open-bank-oss/commit/941a93bf0182c9a9d8ebe32df7d55788e1ca647e))

## [1.21.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.20.0...transaction-service-v1.21.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [1.20.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.19.2...transaction-service-v1.20.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [1.19.2](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.19.1...transaction-service-v1.19.2) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [1.19.1](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.19.0...transaction-service-v1.19.1) (2026-08-21)


### Bug Fixes

* **transaction:** publish the full TransactionType and TransactionStatus vocabularies ([#5982](https://github.com/JiRaska/open-bank-oss/issues/5982)) ([11baea4](https://github.com/JiRaska/open-bank-oss/commit/11baea4482c50d838f8c913d4ae466ccc198a53c))

## [1.19.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.18.1...transaction-service-v1.19.0) (2026-08-20)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))
* **transaction:** expose pending four-eyes approvals via approval inbox ([#5684](https://github.com/JiRaska/open-bank-oss/issues/5684)) ([909b017](https://github.com/JiRaska/open-bank-oss/commit/909b0171666646cce2abe4eaaf84175a57ea5e51))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)
* make TransactionSagaStuck able to fire, delete 3 alerts that never could ([#5787](https://github.com/JiRaska/open-bank-oss/issues/5787)) ([55c2cdd](https://github.com/JiRaska/open-bank-oss/commit/55c2cdd0a57fc0dfeebb824194aa315f37f26ae3))
* **transaction:** add sourceService to audit-consumed events ([#5329](https://github.com/JiRaska/open-bank-oss/issues/5329)) ([b83799b](https://github.com/JiRaska/open-bank-oss/commit/b83799b06ded82d524795e12d47cb6e6321c7a14)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)

## [1.18.1](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.18.0...transaction-service-v1.18.1) (2026-08-20)


### Bug Fixes

* make TransactionSagaStuck able to fire, delete 3 alerts that never could ([#5787](https://github.com/JiRaska/open-bank-oss/issues/5787)) ([55c2cdd](https://github.com/JiRaska/open-bank-oss/commit/55c2cdd0a57fc0dfeebb824194aa315f37f26ae3))

## [1.18.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.17.0...transaction-service-v1.18.0) (2026-08-19)


### Features

* **transaction:** expose pending four-eyes approvals via approval inbox ([#5684](https://github.com/JiRaska/open-bank-oss/issues/5684)) ([909b017](https://github.com/JiRaska/open-bank-oss/commit/909b0171666646cce2abe4eaaf84175a57ea5e51))

## [1.17.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.16.4...transaction-service-v1.17.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)
* **transaction:** add sourceService to audit-consumed events ([#5329](https://github.com/JiRaska/open-bank-oss/issues/5329)) ([b83799b](https://github.com/JiRaska/open-bank-oss/commit/b83799b06ded82d524795e12d47cb6e6321c7a14)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)

## [1.16.4](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.16.3...transaction-service-v1.16.4) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)
* **transaction:** a payee of ours settles same-day, whatever the rail says ([#5225](https://github.com/JiRaska/open-bank-oss/issues/5225)) ([aaef8b7](https://github.com/JiRaska/open-bank-oss/commit/aaef8b7d6719faf522d3e09c3c2f0f591810548e))

## [1.16.3](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.16.2...transaction-service-v1.16.3) (2026-08-15)


### Bug Fixes

* **transaction:** own-account transfers book and value same-day, always ([#4869](https://github.com/JiRaska/open-bank-oss/issues/4869)) ([79a88b9](https://github.com/JiRaska/open-bank-oss/commit/79a88b931827c447d08f64481f75a13c225521e4))

## [1.16.2](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.16.1...transaction-service-v1.16.2) (2026-08-09)


### Bug Fixes

* **transaction:** move the terminal status write inside the payment workflow ([#4306](https://github.com/JiRaska/open-bank-oss/issues/4306)) ([2946fa3](https://github.com/JiRaska/open-bank-oss/commit/2946fa3d971f6033c26974b975e24fe707b61493))

## [1.16.1](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.16.0...transaction-service-v1.16.1) (2026-08-08)


### Bug Fixes

* **sanctions:** make the scheduled-refresh de-duplication zone-consistent, and retire two accounting-clock exemptions ([#3889](https://github.com/JiRaska/open-bank-oss/issues/3889)) ([6b0ad07](https://github.com/JiRaska/open-bank-oss/commit/6b0ad078e1fed00599943752f5f3c61c8df55d46))

## [1.16.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.15.5...transaction-service-v1.16.0) (2026-08-07)


### Features

* **transaction:** resolve card merchants to a name and a shop location (D5) ([#4010](https://github.com/JiRaska/open-bank-oss/issues/4010)) ([78139e0](https://github.com/JiRaska/open-bank-oss/commit/78139e03d49298557ef3385436d7664ab23fd335))

## [1.15.5](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.15.4...transaction-service-v1.15.5) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [1.15.4](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.15.3...transaction-service-v1.15.4) (2026-08-02)


### Bug Fixes

* **infra:** give the five money-path services the JDBC datasource Flyway migrates ([#3192](https://github.com/JiRaska/open-bank-oss/issues/3192)) ([d9b31d5](https://github.com/JiRaska/open-bank-oss/commit/d9b31d5d2cccd169ec6ce7e8e971d5853ef952f1)), closes [#3080](https://github.com/JiRaska/open-bank-oss/issues/3080)

## [1.15.3](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.15.2...transaction-service-v1.15.3) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))

## [1.15.2](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.15.1...transaction-service-v1.15.2) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [1.15.1](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.15.0...transaction-service-v1.15.1) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [1.15.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.14.2...transaction-service-v1.15.0) (2026-07-19)


### Features

* **transaction:** add the ADR-0179 merge balance sweep ([#1789](https://github.com/JiRaska/open-bank-oss/issues/1789)) ([04069d0](https://github.com/JiRaska/open-bank-oss/commit/04069d034ba5dd9188ba49bab780548cc5ee44ed))

## [1.14.2](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.14.1...transaction-service-v1.14.2) (2026-07-17)


### Bug Fixes

* **transaction:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1495](https://github.com/JiRaska/open-bank-oss/issues/1495)) ([156d39a](https://github.com/JiRaska/open-bank-oss/commit/156d39a52b6c52668ccf961028d7ad048189b4dc))

## [1.14.1](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.14.0...transaction-service-v1.14.1) (2026-07-16)


### Bug Fixes

* **transaction-service:** add tests to clear the koverVerify 85% floor ([#1132](https://github.com/JiRaska/open-bank-oss/issues/1132)) ([3609e0b](https://github.com/JiRaska/open-bank-oss/commit/3609e0b3daf906d400fdb802c6eb307e32744693))

## [1.14.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.13.3...transaction-service-v1.14.0) (2026-07-14)


### Features

* **transaction:** wire four-eyes enforcement mechanism (ADR-0155) ([#931](https://github.com/JiRaska/open-bank-oss/issues/931)) ([7a8dd2e](https://github.com/JiRaska/open-bank-oss/commit/7a8dd2e8da2ddc64b558faa2e0474aa65a63033f))

## [1.13.3](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.13.2...transaction-service-v1.13.3) (2026-07-12)


### Bug Fixes

* **sepa-instant:** valueDate format bug + add transaction-service pact coverage ([#468](https://github.com/JiRaska/open-bank-oss/issues/468)) ([#840](https://github.com/JiRaska/open-bank-oss/issues/840)) ([96ab862](https://github.com/JiRaska/open-bank-oss/commit/96ab862846f734ce521c01d2b93ca8f6db0cbcf3))

## [1.13.2](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.13.1...transaction-service-v1.13.2) (2026-07-11)


### Bug Fixes

* **transaction,ledger:** per-currency cash-clearing GL accounts ([#747](https://github.com/JiRaska/open-bank-oss/issues/747)) ([#749](https://github.com/JiRaska/open-bank-oss/issues/749)) ([7e0f934](https://github.com/JiRaska/open-bank-oss/commit/7e0f9341b9d0f92f61665ff4cf981b9d181d73c6))

## [1.13.1](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.13.0...transaction-service-v1.13.1) (2026-07-09)


### Bug Fixes

* **transaction:** close auto.offset.reset gap in Kafka override ([#704](https://github.com/JiRaska/open-bank-oss/issues/704)) ([afcccc5](https://github.com/JiRaska/open-bank-oss/commit/afcccc5a16968ef76087930c77b7117dbd2cb788)), closes [#686](https://github.com/JiRaska/open-bank-oss/issues/686)

## [1.13.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.12.6...transaction-service-v1.13.0) (2026-07-09)


### Features

* **fraud:** add new-payee + high-amount REVIEW rule (v4) ([#635](https://github.com/JiRaska/open-bank-oss/issues/635)) ([ebc5dc1](https://github.com/JiRaska/open-bank-oss/commit/ebc5dc12d255a1dc46231b8c2a704b0839c556f1))

## [1.12.6](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.12.5...transaction-service-v1.12.6) (2026-07-08)


### Bug Fixes

* **transaction:** optimistic locking on state transitions + idempotent concurrent initiation ([#579](https://github.com/JiRaska/open-bank-oss/issues/579)) ([8185b5c](https://github.com/JiRaska/open-bank-oss/commit/8185b5cbf1b1e4e4a27c14eda8d711c5b96aed80)), closes [#465](https://github.com/JiRaska/open-bank-oss/issues/465)

## [1.12.5](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.12.4...transaction-service-v1.12.5) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [1.12.4](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.12.3...transaction-service-v1.12.4) (2026-07-07)


### Security

* **transaction:** enforce OPA authorization on transaction endpoints (ADR-0034 Phase 5) ([#414](https://github.com/JiRaska/open-bank-oss/issues/414)) ([b63d1c4](https://github.com/JiRaska/open-bank-oss/commit/b63d1c49562a1552c6fed27492655374370ebcf7)), closes [#266](https://github.com/JiRaska/open-bank-oss/issues/266)

## [1.12.3](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.12.2...transaction-service-v1.12.3) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [1.12.2](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.12.1...transaction-service-v1.12.2) (2026-06-30)


### Bug Fixes

* **transaction:** normalize booking amount to currency minor units at ingest ([#2735](https://github.com/JiRaska/open-bank-oss/issues/2735)) ([fe90019](https://github.com/JiRaska/open-bank-oss/commit/fe90019fbe7a82fe9c7c93de0012950ef1e88207))

## [1.12.1](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.12.0...transaction-service-v1.12.1) (2026-06-29)


### Bug Fixes

* **libs,account,consent,ledger,pid,transaction:** make DomainEvent.occurredAt explicit ([#2137](https://github.com/JiRaska/open-bank-oss/issues/2137)) ([#2662](https://github.com/JiRaska/open-bank-oss/issues/2662)) ([9e0c2ea](https://github.com/JiRaska/open-bank-oss/commit/9e0c2ea14a65aec227df333b83b0b7283b6c16a5))

## [1.12.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.11.0...transaction-service-v1.12.0) (2026-06-29)


### Features

* **observability:** RUM gateway cardinality budget, attribute audit, HPA (ADR-0088 O1-O3) ([#2208](https://github.com/JiRaska/open-bank-oss/issues/2208)) ([e65ce75](https://github.com/JiRaska/open-bank-oss/commit/e65ce75eb99121c49258f7b998d64e99a5e24dbe))
* **transaction:** Temporal payment orchestration scaffolding (ADR-0120 P1) ([#2378](https://github.com/JiRaska/open-bank-oss/issues/2378)) ([094f805](https://github.com/JiRaska/open-bank-oss/commit/094f80582e6ede7555f8644e50312f9ee4eede15))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)
* **transaction:** unblock release build — ktlint signature + unify Pact provider tests ([#2353](https://github.com/JiRaska/open-bank-oss/issues/2353)) ([60654c9](https://github.com/JiRaska/open-bank-oss/commit/60654c9048da3abe342cba615b14ecb3a3862655))


### Security

* **kafka:** mTLS + ACLs for payment.scheme-accepted, no global gate flip (ADR-0137) ([#2602](https://github.com/JiRaska/open-bank-oss/issues/2602)) ([b143022](https://github.com/JiRaska/open-bank-oss/commit/b143022f6ab76c4ff817ddbd4467fc578b8ee193))

## [1.11.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.10.1...transaction-service-v1.11.0) (2026-06-29)


### Features

* **transaction:** Temporal payment orchestration scaffolding (ADR-0120 P1) ([#2378](https://github.com/JiRaska/open-bank-oss/issues/2378)) ([cca0818](https://github.com/JiRaska/open-bank-oss/commit/cca0818936ec182fb9c5af709a3d240e4baeb147))

## [1.10.1](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.10.0...transaction-service-v1.10.1) (2026-06-28)


### Bug Fixes

* **transaction:** unblock release build — ktlint signature + unify Pact provider tests ([#2353](https://github.com/JiRaska/open-bank-oss/issues/2353)) ([d733b0c](https://github.com/JiRaska/open-bank-oss/commit/d733b0cfb4839d2b22e0db2f8d6339b31cf5af9e))

## [1.10.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.9.0...transaction-service-v1.10.0) (2026-06-27)


### Features

* **observability:** RUM gateway cardinality budget, attribute audit, HPA (ADR-0088 O1-O3) ([#2208](https://github.com/JiRaska/open-bank-oss/issues/2208)) ([b7f0849](https://github.com/JiRaska/open-bank-oss/commit/b7f08494cf923f66dbd910e92522fb9453394de7))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [1.9.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.8.0...transaction-service-v1.9.0) (2026-06-25)


### Features

* **transaction:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2093](https://github.com/JiRaska/open-bank-oss/issues/2093)) ([cf97810](https://github.com/JiRaska/open-bank-oss/commit/cf9781054aa2d15a78a2da6d789988737b3c8da6))

## [1.8.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.7.0...transaction-service-v1.8.0) (2026-06-25)


### Features

* **swift,transaction:** add Pact provider verification for message contracts (ADR-0092) ([#2063](https://github.com/JiRaska/open-bank-oss/issues/2063)) ([9d0ead6](https://github.com/JiRaska/open-bank-oss/commit/9d0ead608fb576b78cd17f93da5c35232f328d64))

## [1.7.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.6.0...transaction-service-v1.7.0) (2026-06-25)


### Features

* **infra:** ADR-0099 Tier 2 — OIDC client secret + JWT key rotator CronJob ([#2016](https://github.com/JiRaska/open-bank-oss/issues/2016)) ([83ae8fa](https://github.com/JiRaska/open-bank-oss/commit/83ae8fa8e0779ba96da8aff2dad641eed9bc2c8d))
* **transaction:** consume payment.scheme-accepted to settle rail payments (ADR-0108) ([#1999](https://github.com/JiRaska/open-bank-oss/issues/1999)) ([3e76da5](https://github.com/JiRaska/open-bank-oss/commit/3e76da5897ca9db463ebcde4f8b9b04d7d2327d2))
* **transaction:** drop deprecated channel column (ADR-0103 D4) ([#2028](https://github.com/JiRaska/open-bank-oss/issues/2028)) ([dfa479f](https://github.com/JiRaska/open-bank-oss/commit/dfa479fc50993f68c31ef173e4247882a447e59a))
* **transaction:** stamp PaymentRail + InstructionType on transactions and events (ADR-0103 D2) ([#1995](https://github.com/JiRaska/open-bank-oss/issues/1995)) ([6f748d7](https://github.com/JiRaska/open-bank-oss/commit/6f748d79b917f35850e79af1e9aa67973dd2615b))


### Bug Fixes

* **transaction:** address money-path pre-merge review findings (ADR-0108) ([#2001](https://github.com/JiRaska/open-bank-oss/issues/2001)) ([050cf13](https://github.com/JiRaska/open-bank-oss/commit/050cf130933bf2c4703cc89d8824022b71b6645d))
* **transaction:** inject Clock into domain/application layers (ADR-0100, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2012](https://github.com/JiRaska/open-bank-oss/issues/2012)) ([48f0a47](https://github.com/JiRaska/open-bank-oss/commit/48f0a47aaf95cd317c464f1cb2776128dcc82361))

## [1.6.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.5.0...transaction-service-v1.6.0) (2026-06-25)


### Features

* **pockets:** convert pocket balance to primary currency (ADR-0107) ([#1797](https://github.com/JiRaska/open-bank-oss/issues/1797)) ([c3df994](https://github.com/JiRaska/open-bank-oss/commit/c3df99430d6976555b052fefd78186f3b55de795))
* **transaction,payment:** ADR-0103 D2 — stamp rail + instructionType at settlement (transaction-service + 4 rails) ([#1940](https://github.com/JiRaska/open-bank-oss/issues/1940)) ([785ca02](https://github.com/JiRaska/open-bank-oss/commit/785ca024d434c845dadade0190551fdd18da17a9))
* **transaction:** add @Authorize resource-gate on REST endpoints (ADR-0034 D3) ([#1287](https://github.com/JiRaska/open-bank-oss/issues/1287)) ([d3e4d14](https://github.com/JiRaska/open-bank-oss/commit/d3e4d140a41db7a3a916d811648a6511c0f3dd79))
* **transaction:** add POST /{id}/reverse endpoint for R-transaction returns (ADR-0109) ([#1930](https://github.com/JiRaska/open-bank-oss/issues/1930)) ([f1e0a4a](https://github.com/JiRaska/open-bank-oss/commit/f1e0a4ad67d85f7056a9fc0801813606ce297253))
* **transaction:** ADR-0103 D1 — payment-rail + instruction-type vocabulary & persistence ([#1672](https://github.com/JiRaska/open-bank-oss/issues/1672)) ([70d26b7](https://github.com/JiRaska/open-bank-oss/commit/70d26b78453df25298cb3e4229c46bb50222c2b0))
* **transaction:** ADR-0103 D4 — backfill rail/instructionType, MCC wire, deprecate channel ([#1944](https://github.com/JiRaska/open-bank-oss/issues/1944)) ([21d6754](https://github.com/JiRaska/open-bank-oss/commit/21d67546aad6b5f58702419bf70c09a439c70af4))


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank-oss/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **gitops:** raise HolmesGPT relay LLM timeout 180s→300s ([#1918](https://github.com/JiRaska/open-bank-oss/issues/1918)) ([5a96e40](https://github.com/JiRaska/open-bank-oss/commit/5a96e405e96de760c2e40379ffc6637c445976c7))
* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank-oss/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank-oss/commit/92dc2d636d857b526c8276e2647de440c540577b))
* **transaction:** sync openapi.yaml info.version to lockstep 1.5.0 ([#1380](https://github.com/JiRaska/open-bank-oss/issues/1380)) ([9ac52ec](https://github.com/JiRaska/open-bank-oss/commit/9ac52ec2b6d0e5f5f422a69edf4e902fcbb784bb))

## [1.5.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.4.0...transaction-service-v1.5.0) (2026-06-15)


### Features

* **security:** customer payment non-repudiation — SCA settlement gate, identity threading, audit hash chain (ADR-0086) ([#900](https://github.com/JiRaska/open-bank-oss/issues/900)) ([fcc1e52](https://github.com/JiRaska/open-bank-oss/commit/fcc1e52b247b0eb61b9ee8d5332f110984a6fb33))

## [1.4.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.3.0...transaction-service-v1.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **transaction:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#813](https://github.com/JiRaska/open-bank-oss/issues/813)) ([965be94](https://github.com/JiRaska/open-bank-oss/commit/965be94c7d61d521371200c3d5d5032cd9777c6a))

## [1.3.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.2.0...transaction-service-v1.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **transaction:** credit beneficiary balance + wire ledger/balance URLs ([#554](https://github.com/JiRaska/open-bank-oss/issues/554)) ([9a83156](https://github.com/JiRaska/open-bank-oss/commit/9a8315687355f10cbd04f4e210f9c1c723347c12))

## [1.2.0](https://github.com/JiRaska/open-bank-oss/compare/transaction-service-v1.1.0...transaction-service-v1.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **transaction:** drop generic Exception mapper, defer to libs (ADR-0049 D4) ([#336](https://github.com/JiRaska/open-bank-oss/issues/336)) ([7e8eac3](https://github.com/JiRaska/open-bank-oss/commit/7e8eac356cf2405cf9f81c4c82fcf695624cd6dd))

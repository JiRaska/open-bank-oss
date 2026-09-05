# Changelog

## [0.15.1](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.15.0...fx-service-v0.15.1) (2026-09-03)


### Bug Fixes

* **fx:** drop EPOCH instant defaults from events and rate entities ([#8456](https://github.com/JiRaska/open-bank-oss/issues/8456)) ([d8bd2c0](https://github.com/JiRaska/open-bank-oss/commit/d8bd2c0f141a21e5d555b2ed307139eabc55ee26))

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.14.1...fx-service-v0.15.0) (2026-08-31)


### Features

* **admin-ui:** unify the three-month FX rate trend ([#7736](https://github.com/JiRaska/open-bank-oss/issues/7736)) ([5e85aa6](https://github.com/JiRaska/open-bank-oss/commit/5e85aa60dab75048024186ff7072c0918cddaeea))

## [0.14.1](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.14.0...fx-service-v0.14.1) (2026-08-31)


### Bug Fixes

* **ci:** event-handler-swallow gate was blind to every read-model receiver ([#7600](https://github.com/JiRaska/open-bank-oss/issues/7600)) ([336c2f1](https://github.com/JiRaska/open-bank-oss/commit/336c2f1857060696f4b0945cc9ae0556cf3edbda)), closes [#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.13.0...fx-service-v0.14.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.12.1...fx-service-v0.13.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.12.1](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.12.0...fx-service-v0.12.1) (2026-08-22)


### Bug Fixes

* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.11.1...fx-service-v0.12.0) (2026-08-19)


### Features

* **fx:** expose pending four-eyes approvals via approval inbox ([#5695](https://github.com/JiRaska/open-bank-oss/issues/5695)) ([81c159c](https://github.com/JiRaska/open-bank-oss/commit/81c159ce49cb4671e03171a3e2ccec1068cb3929))

## [0.11.1](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.11.0...fx-service-v0.11.1) (2026-08-18)


### Bug Fixes

* **fx-service:** add sourceService to outbox events for audit attribution ([#5390](https://github.com/JiRaska/open-bank-oss/issues/5390)) ([842497b](https://github.com/JiRaska/open-bank-oss/commit/842497b9b6f4627ba0e8b2098d14849197d324d4))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.10.0...fx-service-v0.11.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.9.8...fx-service-v0.10.0) (2026-08-17)


### Features

* **fx:** distinguish feed fetch outcomes from job liveness ([#4943](https://github.com/JiRaska/open-bank-oss/issues/4943)) ([b48a609](https://github.com/JiRaska/open-bank-oss/commit/b48a60992ade8f6382a096564081c4b8593b90de))


### Bug Fixes

* **domestic-payment:** make a synthetic fraud verdict distinguishable from a real one ([#4221](https://github.com/JiRaska/open-bank-oss/issues/4221) layers 2+3) ([#4411](https://github.com/JiRaska/open-bank-oss/issues/4411)) ([6265ea8](https://github.com/JiRaska/open-bank-oss/commit/6265ea869275f6722b937860f5dcd03d3674d5d7))
* **fx:** derive feed-name agreement between CI probe and liveness gauge ([#5001](https://github.com/JiRaska/open-bank-oss/issues/5001)) ([83e1adc](https://github.com/JiRaska/open-bank-oss/commit/83e1adcf8690924ef2827442e2f2220640587b3b))
* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.9.8](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.9.7...fx-service-v0.9.8) (2026-08-13)


### Bug Fixes

* **ledger:** resolve the ČNB fixing by business day and let a corrected one supersede ([#4397](https://github.com/JiRaska/open-bank-oss/issues/4397)) ([e2c5d87](https://github.com/JiRaska/open-bank-oss/commit/e2c5d87e21e913d24ff6e1558eff8425f5abf4a7)), closes [#1302](https://github.com/JiRaska/open-bank-oss/issues/1302)

## [0.9.7](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.9.6...fx-service-v0.9.7) (2026-08-08)


### Bug Fixes

* **sanctions:** make the scheduled-refresh de-duplication zone-consistent, and retire two accounting-clock exemptions ([#3889](https://github.com/JiRaska/open-bank-oss/issues/3889)) ([6b0ad07](https://github.com/JiRaska/open-bank-oss/commit/6b0ad078e1fed00599943752f5f3c61c8df55d46))

## [0.9.6](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.9.5...fx-service-v0.9.6) (2026-08-07)


### Bug Fixes

* **fx:** a derived inverse rate no longer reuses the source rate's id ([#3741](https://github.com/JiRaska/open-bank-oss/issues/3741)) ([559256a](https://github.com/JiRaska/open-bank-oss/commit/559256aabbb7ddcb5e5b5d2331a731023817ad8b)), closes [#3374](https://github.com/JiRaska/open-bank-oss/issues/3374)

## [0.9.5](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.9.4...fx-service-v0.9.5) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.9.4](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.9.3...fx-service-v0.9.4) (2026-08-02)


### Bug Fixes

* **fx:** price a pair from its reverse quote instead of answering 404 ([#3189](https://github.com/JiRaska/open-bank-oss/issues/3189)) ([53b769f](https://github.com/JiRaska/open-bank-oss/commit/53b769f0351c550cb833268dcaa86b265ebbffbf)), closes [#2314](https://github.com/JiRaska/open-bank-oss/issues/2314)

## [0.9.3](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.9.2...fx-service-v0.9.3) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))
* **money-path:** a null JSON body returned 500 on 12 handlers ([#3038](https://github.com/JiRaska/open-bank-oss/issues/3038)) ([#3050](https://github.com/JiRaska/open-bank-oss/issues/3050)) ([7af4d19](https://github.com/JiRaska/open-bank-oss/commit/7af4d19aac4a0d75e221fbc64a1a24196e61ce8f))

## [0.9.2](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.9.1...fx-service-v0.9.2) (2026-07-31)


### Bug Fixes

* **fx:** point the ČNB fixing ingestion at the URL that actually serves it ([#2917](https://github.com/JiRaska/open-bank-oss/issues/2917)) ([9911418](https://github.com/JiRaska/open-bank-oss/commit/99114189056ac41a523d2d914fd80e4a0d917734))

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.9.0...fx-service-v0.9.1) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.8.2...fx-service-v0.9.0) (2026-07-26)


### Features

* **ledger:** register workflow-liveness gauges on the money-path schedulers ([#2488](https://github.com/JiRaska/open-bank-oss/issues/2488)) ([d0332b4](https://github.com/JiRaska/open-bank-oss/commit/d0332b4201aa965504c9f2494a5b8e1639c25ec5)), closes [#2239](https://github.com/JiRaska/open-bank-oss/issues/2239)

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.8.1...fx-service-v0.8.2) (2026-07-25)


### Bug Fixes

* **fx:** run the daily ČNB fixing ingestion on a Vert.x context ([#2187](https://github.com/JiRaska/open-bank-oss/issues/2187)) ([#2191](https://github.com/JiRaska/open-bank-oss/issues/2191)) ([398a043](https://github.com/JiRaska/open-bank-oss/commit/398a0433ce3475f771b88c9c2e1c6699672b6933))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.8.0...fx-service-v0.8.1) (2026-07-17)


### Bug Fixes

* **fx:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1538](https://github.com/JiRaska/open-bank-oss/issues/1538)) ([a05fc47](https://github.com/JiRaska/open-bank-oss/commit/a05fc479359709534a95b24a3b00e9869e487709))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.7.7...fx-service-v0.8.0) (2026-07-14)


### Features

* **governance:** four-eyes vocabulary for fx/consent/sanctions + sanctions OPA bootstrap ([#1006](https://github.com/JiRaska/open-bank-oss/issues/1006)) ([f5d589e](https://github.com/JiRaska/open-bank-oss/commit/f5d589e17d6bb8c1ed7abfa74f732d2b171fd3f9))


### Bug Fixes

* **fx:** wire FX conversion events through the real transactional outbox ([#1054](https://github.com/JiRaska/open-bank-oss/issues/1054)) ([3210b6a](https://github.com/JiRaska/open-bank-oss/commit/3210b6a46b07abf65b42e8d24a951a15c498f249)), closes [#1033](https://github.com/JiRaska/open-bank-oss/issues/1033) [#996](https://github.com/JiRaska/open-bank-oss/issues/996)
* **pact:** bridge reactive-Panache @State handlers onto a Vert.x context ([#1097](https://github.com/JiRaska/open-bank-oss/issues/1097)) ([b9b496a](https://github.com/JiRaska/open-bank-oss/commit/b9b496a6cfdfb529fed96c6ef8f6944215c81c5c))

## [0.7.7](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.7.6...fx-service-v0.7.7) (2026-07-12)


### Bug Fixes

* **fx:** grant the real M2M caller role in the Pact provider test, not a phantom one ([#862](https://github.com/JiRaska/open-bank-oss/issues/862)) ([e3a4c19](https://github.com/JiRaska/open-bank-oss/commit/e3a4c19dc13b4ffb34d75b55cc8124cb7e4b64be))

## [0.7.6](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.7.5...fx-service-v0.7.6) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.7.5](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.7.4...fx-service-v0.7.5) (2026-07-07)


### Security

* **fx:** enforce OPA authorization on FX endpoints (ADR-0034 Phase 5) ([#406](https://github.com/JiRaska/open-bank-oss/issues/406)) ([347b893](https://github.com/JiRaska/open-bank-oss/commit/347b8934ccfcc665cbe5796440664bdc4463b77b))

## [0.7.4](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.7.3...fx-service-v0.7.4) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.7.2...fx-service-v0.7.3) (2026-06-30)


### Security

* **fx:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2a) ([#2742](https://github.com/JiRaska/open-bank-oss/issues/2742)) ([bb1aacd](https://github.com/JiRaska/open-bank-oss/commit/bb1aacd1a446d8fe43edd8c5ed4a83540a758aa5))

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.7.1...fx-service-v0.7.2) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)
* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([c61fcaf](https://github.com/JiRaska/open-bank-oss/commit/c61fcafde76be08f716c710462be70752073aba1))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.7.0...fx-service-v0.7.1) (2026-06-28)


### Bug Fixes

* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([35c6a63](https://github.com/JiRaska/open-bank-oss/commit/35c6a6309e10c0a56558d93de5c494a89da72ceb))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.6.0...fx-service-v0.7.0) (2026-06-27)


### Features

* **fx:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2100](https://github.com/JiRaska/open-bank-oss/issues/2100)) ([c524c09](https://github.com/JiRaska/open-bank-oss/commit/c524c09dcedcf27c4749281fd868344ac99b7c86))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **sepa-payment,analytics,clearing-simulator,finrep,fx,customer-edge,security-scanner:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2174](https://github.com/JiRaska/open-bank-oss/issues/2174)) ([51a872e](https://github.com/JiRaska/open-bank-oss/commit/51a872ec0ce0b9f888226ca94ffcfb9f392174c2))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.5.0...fx-service-v0.6.0) (2026-06-25)


### Features

* **fx:** ADR-0101 P4 — Temporal durable workflow for FX conversions ([#1530](https://github.com/JiRaska/open-bank-oss/issues/1530)) ([abf148f](https://github.com/JiRaska/open-bank-oss/commit/abf148fe85fdcef45b1a7c7fba3b6da4c42cd53b))


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank-oss/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **fx-service:** configure the OIDC client so service tokens are minted (ADR-0104 D3) ([#1787](https://github.com/JiRaska/open-bank-oss/issues/1787)) ([2cb37ca](https://github.com/JiRaska/open-bank-oss/commit/2cb37cab689e1b54651e1be8f2cb28ef87c4fc9d))
* **fx-service:** wrap Temporal activities in VertxContextSupport (issue [#1739](https://github.com/JiRaska/open-bank-oss/issues/1739)) ([#1773](https://github.com/JiRaska/open-bank-oss/issues/1773)) ([602137f](https://github.com/JiRaska/open-bank-oss/commit/602137f8b165fcd59eb88c619eb8e0f819730806))
* **temporal:** remove non-existent @WorkflowImpl annotation from fx+statement ([#1538](https://github.com/JiRaska/open-bank-oss/issues/1538)) ([0d73e0d](https://github.com/JiRaska/open-bank-oss/commit/0d73e0d7548d2953536975b257e646de6272cff4))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.4.0...fx-service-v0.5.0) (2026-06-15)


### Features

* **fx:** GET /api/v1/fx/rates/{base}/{quote}/history endpoint ([f0ae112](https://github.com/JiRaska/open-bank-oss/commit/f0ae1126d6a1939f542931372adb1205e6d8a7c8))


### Bug Fixes

* explicit @Column(name = "bid_rate" | "ask_rate" | "applied_rate"). ([4808dcc](https://github.com/JiRaska/open-bank-oss/commit/4808dccc2cc8fbda025820e437f7f17b6bfb8916))
* **fx,customer-edge:** FX history prázdná + chybí ECB odchylka ([#1115](https://github.com/JiRaska/open-bank-oss/issues/1115)) ([2a5d872](https://github.com/JiRaska/open-bank-oss/commit/2a5d872c9e3f68be2afa6860ae7a3b363ac43908))
* **fx:** add camelCase→snake_case Hibernate naming strategy ([#1043](https://github.com/JiRaska/open-bank-oss/issues/1043)) ([869ac4b](https://github.com/JiRaska/open-bank-oss/commit/869ac4bac2d0676ab8987035f9d64a7624ffa84c))
* **fx:** map bid/ask/applied rate columns explicitly — GET /rates was 500 ([#1031](https://github.com/JiRaska/open-bank-oss/issues/1031)) ([4808dcc](https://github.com/JiRaska/open-bank-oss/commit/4808dccc2cc8fbda025820e437f7f17b6bfb8916))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.3.0...fx-service-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **fx-service:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#686](https://github.com/JiRaska/open-bank-oss/issues/686)) ([00fa201](https://github.com/JiRaska/open-bank-oss/commit/00fa201f9608e7e29272f05d6cce251ec5d8f54e))
* **fx:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#799](https://github.com/JiRaska/open-bank-oss/issues/799)) ([da92804](https://github.com/JiRaska/open-bank-oss/commit/da928049714c2bb99278b0e96db1578cf92a2aef))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.2.0...fx-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/fx-service-v0.1.0...fx-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))

# Changelog

## [0.18.4](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.18.3...audit-service-v0.18.4) (2026-09-03)


### Bug Fixes

* send occurredAt on the last four audit-consumed producers that omit it ([#8352](https://github.com/JiRaska/open-bank-oss/issues/8352)) ([#8503](https://github.com/JiRaska/open-bank-oss/issues/8503)) ([146fe87](https://github.com/JiRaska/open-bank-oss/commit/146fe87adaeca4e56fb8da285a57daaaf840cb1d))

## [0.18.3](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.18.2...audit-service-v0.18.3) (2026-09-02)


### Security

* **delegation:** publish durable spend reservation state ([#8247](https://github.com/JiRaska/open-bank-oss/issues/8247)) ([a017aab](https://github.com/JiRaska/open-bank-oss/commit/a017aab1111abaf702cc708f9e2a39cec56c9b1c))

## [0.18.2](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.18.1...audit-service-v0.18.2) (2026-08-22)


### Bug Fixes

* **audit:** read aggregateId from the producer envelope ([#6478](https://github.com/JiRaska/open-bank-oss/issues/6478)) ([564cbd3](https://github.com/JiRaska/open-bank-oss/commit/564cbd3aa00094479253fc20b1551fe079de8f23)), closes [#6318](https://github.com/JiRaska/open-bank-oss/issues/6318)
* **docs:** repair the 7 .mmd diagrams that do not parse ([#6496](https://github.com/JiRaska/open-bank-oss/issues/6496)) ([c1e6ad7](https://github.com/JiRaska/open-bank-oss/commit/c1e6ad7b14887db70ec3365747f2ed06d9ec02db))
* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [0.18.1](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.18.0...audit-service-v0.18.1) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [0.18.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.17.1...audit-service-v0.18.0) (2026-08-21)


### Features

* **agent:** durable AI audit provenance ([#6209](https://github.com/JiRaska/open-bank-oss/issues/6209)) ([8a862f3](https://github.com/JiRaska/open-bank-oss/commit/8a862f387594f934f91bf5befcbc966ccf40abad))

## [0.17.1](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.17.0...audit-service-v0.17.1) (2026-08-20)


### Bug Fixes

* **audit:** make anchor kms-key-id optional so the service boots ([#5944](https://github.com/JiRaska/open-bank-oss/issues/5944)) ([87b9c00](https://github.com/JiRaska/open-bank-oss/commit/87b9c00caec1ad7bff983ce953e5156f2509222a)), closes [#5844](https://github.com/JiRaska/open-bank-oss/issues/5844)

## [0.17.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.16.0...audit-service-v0.17.0) (2026-08-20)


### Features

* **audit:** add KMS-backed audit anchors ([#5844](https://github.com/JiRaska/open-bank-oss/issues/5844)) ([785df7a](https://github.com/JiRaska/open-bank-oss/commit/785df7a0807e05dc6eab828319abe82f3faa891f))

## [0.16.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.15.4...audit-service-v0.16.0) (2026-08-17)


### Features

* **audit:** subscribe to openbank.sca.events for SCA enrollment audit trail ([#5369](https://github.com/JiRaska/open-bank-oss/issues/5369)) ([9446fda](https://github.com/JiRaska/open-bank-oss/commit/9446fda2d4e2768bce5f3d1d54d1d63066cc20ef)), closes [#5337](https://github.com/JiRaska/open-bank-oss/issues/5337) [#5338](https://github.com/JiRaska/open-bank-oss/issues/5338)

## [0.15.4](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.15.3...audit-service-v0.15.4) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)
* **security-scanner:** delete the openbank.security.scan.event outbox, which never had a writer ([#4940](https://github.com/JiRaska/open-bank-oss/issues/4940)) ([9d1d095](https://github.com/JiRaska/open-bank-oss/commit/9d1d0954c418722adc1beb712956209d98eb6a0c))

## [0.15.3](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.15.2...audit-service-v0.15.3) (2026-08-16)


### Bug Fixes

* **audit:** follow merged_into at read time for a party's history ([#5110](https://github.com/JiRaska/open-bank-oss/issues/5110)) ([48df1cc](https://github.com/JiRaska/open-bank-oss/commit/48df1cc917ca6e66ce23fa43c71ee2d8623ca892)), closes [#1984](https://github.com/JiRaska/open-bank-oss/issues/1984)

## [0.15.2](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.15.1...audit-service-v0.15.2) (2026-08-16)


### Bug Fixes

* **audit:** publish a percentile histogram for the chain-verify timer ([#5062](https://github.com/JiRaska/open-bank-oss/issues/5062)) ([d35c4e3](https://github.com/JiRaska/open-bank-oss/commit/d35c4e394f46766d1bb3780eb986d344b0c03387)), closes [#5049](https://github.com/JiRaska/open-bank-oss/issues/5049)

## [0.15.1](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.15.0...audit-service-v0.15.1) (2026-08-16)


### Bug Fixes

* **audit:** record anchor capture liveness ([#5042](https://github.com/JiRaska/open-bank-oss/issues/5042)) ([27eece9](https://github.com/JiRaska/open-bank-oss/commit/27eece9998d7e4a1fc30556fa8776ac9d8d969bf))

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.14.6...audit-service-v0.15.0) (2026-08-14)


### Features

* **scheduler:** register workflow liveness on four retention and cleanup jobs (ADR-0237) ([#4739](https://github.com/JiRaska/open-bank-oss/issues/4739)) ([c2a2fa4](https://github.com/JiRaska/open-bank-oss/commit/c2a2fa4b788a172ef85c8babb439cecd10fbfe23)), closes [#3345](https://github.com/JiRaska/open-bank-oss/issues/3345)

## [0.14.6](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.14.5...audit-service-v0.14.6) (2026-08-13)


### Bug Fixes

* **audit:** namespace the shared channel field by source topic ([#4715](https://github.com/JiRaska/open-bank-oss/issues/4715)) ([9bcb31a](https://github.com/JiRaska/open-bank-oss/commit/9bcb31a2f37133683600c7e5c828ea13355b421e))

## [0.14.5](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.14.4...audit-service-v0.14.5) (2026-08-13)


### Bug Fixes

* **audit:** normalise aggregate_type at ingest, same defect as [#4553](https://github.com/JiRaska/open-bank-oss/issues/4553) ([#4661](https://github.com/JiRaska/open-bank-oss/issues/4661)) ([4616820](https://github.com/JiRaska/open-bank-oss/commit/4616820e5df765c7a808d5d42740782d3399c50b))
* **audit:** recover the producer and event type the transport already carried ([#4270](https://github.com/JiRaska/open-bank-oss/issues/4270)) ([6c57d67](https://github.com/JiRaska/open-bank-oss/commit/6c57d67443f8dc79dfe5146678cfefb1b9504651)), closes [#3994](https://github.com/JiRaska/open-bank-oss/issues/3994)
* **audit:** recover three unread actor spellings and count the actor gap ([#4693](https://github.com/JiRaska/open-bank-oss/issues/4693)) ([ae0fecf](https://github.com/JiRaska/open-bank-oss/commit/ae0fecfac9e13d632da05363b9f4b66ab74af95b)), closes [#3994](https://github.com/JiRaska/open-bank-oss/issues/3994)
* **audit:** stop an explicit JSON null becoming the actor "null" ([#4307](https://github.com/JiRaska/open-bank-oss/issues/4307)) ([2848bc5](https://github.com/JiRaska/open-bank-oss/commit/2848bc566f3f19c856f909b3509b0b2286138562)), closes [#3994](https://github.com/JiRaska/open-bank-oss/issues/3994)

## [0.14.4](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.14.3...audit-service-v0.14.4) (2026-08-13)


### Bug Fixes

* **audit:** recover three unread actor spellings and count the actor gap ([#4693](https://github.com/JiRaska/open-bank-oss/issues/4693)) ([ae0fecf](https://github.com/JiRaska/open-bank-oss/commit/ae0fecfac9e13d632da05363b9f4b66ab74af95b)), closes [#3994](https://github.com/JiRaska/open-bank-oss/issues/3994)

## [0.14.3](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.14.2...audit-service-v0.14.3) (2026-08-13)


### Bug Fixes

* **audit:** normalise aggregate_type at ingest, same defect as [#4553](https://github.com/JiRaska/open-bank-oss/issues/4553) ([#4661](https://github.com/JiRaska/open-bank-oss/issues/4661)) ([4616820](https://github.com/JiRaska/open-bank-oss/commit/4616820e5df765c7a808d5d42740782d3399c50b))

## [0.14.2](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.14.1...audit-service-v0.14.2) (2026-08-09)


### Bug Fixes

* **audit:** stop an explicit JSON null becoming the actor "null" ([#4307](https://github.com/JiRaska/open-bank-oss/issues/4307)) ([2848bc5](https://github.com/JiRaska/open-bank-oss/commit/2848bc566f3f19c856f909b3509b0b2286138562)), closes [#3994](https://github.com/JiRaska/open-bank-oss/issues/3994)

## [0.14.1](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.14.0...audit-service-v0.14.1) (2026-08-09)


### Bug Fixes

* **audit:** recover the producer and event type the transport already carried ([#4270](https://github.com/JiRaska/open-bank-oss/issues/4270)) ([6c57d67](https://github.com/JiRaska/open-bank-oss/commit/6c57d67443f8dc79dfe5146678cfefb1b9504651)), closes [#3994](https://github.com/JiRaska/open-bank-oss/issues/3994)

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.13.4...audit-service-v0.14.0) (2026-08-07)


### Features

* let a delegate pay from a shared account, and audit it as delegated ([#3633](https://github.com/JiRaska/open-bank-oss/issues/3633)) ([568686b](https://github.com/JiRaska/open-bank-oss/commit/568686bfc3ba15e824252f3502b0fddc856c7d37))

## [0.13.4](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.13.3...audit-service-v0.13.4) (2026-08-06)


### Bug Fixes

* **audit:** record whether occurred_at is event time or ingest time ([#3907](https://github.com/JiRaska/open-bank-oss/issues/3907)) ([b72f57e](https://github.com/JiRaska/open-bank-oss/commit/b72f57eaa7c067bd9c906433ca8fcdfe1fa348fe)), closes [#3883](https://github.com/JiRaska/open-bank-oss/issues/3883)

## [0.13.3](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.13.2...audit-service-v0.13.3) (2026-08-05)


### Bug Fixes

* **audit:** let the chain walk cross the rows [#3586](https://github.com/JiRaska/open-bank-oss/issues/3586) could not fix ([#3649](https://github.com/JiRaska/open-bank-oss/issues/3649)) ([ba72389](https://github.com/JiRaska/open-bank-oss/commit/ba723892e11fba1c4eb46366178aa2bb9d644e69)), closes [#3505](https://github.com/JiRaska/open-bank-oss/issues/3505)

## [0.13.2](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.13.1...audit-service-v0.13.2) (2026-08-03)


### Bug Fixes

* **audit:** hash the timestamp the database actually stores ([#3586](https://github.com/JiRaska/open-bank-oss/issues/3586)) ([ca5bdd7](https://github.com/JiRaska/open-bank-oss/commit/ca5bdd76403a29d0f832dcbaa73277f0968b56eb)), closes [#3505](https://github.com/JiRaska/open-bank-oss/issues/3505)

## [0.13.1](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.13.0...audit-service-v0.13.1) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.12.0...audit-service-v0.13.0) (2026-08-01)


### Features

* **audit:** make a broken hash chain loud instead of silent ([#3049](https://github.com/JiRaska/open-bank-oss/issues/3049)) ([692578c](https://github.com/JiRaska/open-bank-oss/commit/692578c0e4108f88f93cd79d304d0f34f3aad24c))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.11.0...audit-service-v0.12.0) (2026-07-31)


### Features

* **lending:** credit evidence emission into the audit chain (ADR-0214) ([2759397](https://github.com/JiRaska/open-bank-oss/commit/2759397b629f42f9893f2d44724d488cfd34456a))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.10.0...audit-service-v0.11.0) (2026-07-31)


### Features

* **edge+security:** claimed-HTTPS app OAuth, wire-aligned OpenAPI, customer access log, VDP link ([#2814](https://github.com/JiRaska/open-bank-oss/issues/2814)) ([48fd47e](https://github.com/JiRaska/open-bank-oss/commit/48fd47e6f9b1db8bf5b43d38766adbc969aec30d))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.9.6...audit-service-v0.10.0) (2026-07-31)


### Features

* **audit:** cross-channel audit correlation — channel/act_chain/session_id + by-actor query (ADR-0226) ([#2756](https://github.com/JiRaska/open-bank-oss/issues/2756)) ([31c7abb](https://github.com/JiRaska/open-bank-oss/commit/31c7abbc2f6ae3a611ba0642326b571dc05ab21e))


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.9.6](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.9.5...audit-service-v0.9.6) (2026-07-17)


### Bug Fixes

* **audit:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1492](https://github.com/JiRaska/open-bank-oss/issues/1492)) ([4b6dc35](https://github.com/JiRaska/open-bank-oss/commit/4b6dc35dae4e7aa3c0d268dbe3a538441f62a28e))

## [0.9.5](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.9.4...audit-service-v0.9.5) (2026-07-14)


### Bug Fixes

* **audit,governance:** [#996](https://github.com/JiRaska/open-bank-oss/issues/996) round 3 + graduate event-consumer-liveness to enforced ([#1082](https://github.com/JiRaska/open-bank-oss/issues/1082)) ([f4bd8b8](https://github.com/JiRaska/open-bank-oss/commit/f4bd8b810a4e116d8315e8bd6c7ca699f253cc4f))

## [0.9.4](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.9.3...audit-service-v0.9.4) (2026-07-14)


### Bug Fixes

* **audit:** consume sepa.instant.events, correcting issue [#1034](https://github.com/JiRaska/open-bank-oss/issues/1034)'s premise ([#1050](https://github.com/JiRaska/open-bank-oss/issues/1050)) ([53f0dae](https://github.com/JiRaska/open-bank-oss/commit/53f0dae5b6b2bbf8b95ba0ab36861a3d5229c162))

## [0.9.3](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.9.2...audit-service-v0.9.3) (2026-07-13)


### Bug Fixes

* **audit:** consume clearing.batch.event + security.ict/scan.event ([#1007](https://github.com/JiRaska/open-bank-oss/issues/1007)) ([b2f307b](https://github.com/JiRaska/open-bank-oss/commit/b2f307b76f526fa84879ec249c749498b2d1a428))

## [0.9.2](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.9.1...audit-service-v0.9.2) (2026-07-09)


### Bug Fixes

* **audit-service:** fix silent Kafka group.id/auto.offset.reset config bug ([#691](https://github.com/JiRaska/open-bank-oss/issues/691)) ([8c48fa1](https://github.com/JiRaska/open-bank-oss/commit/8c48fa1b62f6ae06d5e01ad02c1f28096507d66f))

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.9.0...audit-service-v0.9.1) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.8.2...audit-service-v0.9.0) (2026-07-07)


### Features

* **gdpr:** add kyc/card export coverage and disabled-by-default session-log retention ([#356](https://github.com/JiRaska/open-bank-oss/issues/356)) ([d627e0a](https://github.com/JiRaska/open-bank-oss/commit/d627e0a0d9c7514f65b53f8d253c2ae5394e5386))

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.8.1...audit-service-v0.8.2) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.8.0...audit-service-v0.8.1) (2026-06-30)


### Security

* **audit:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2749](https://github.com/JiRaska/open-bank-oss/issues/2749)) ([ddba9c1](https://github.com/JiRaska/open-bank-oss/commit/ddba9c17ff22624b17e4b28e60837b29352ad384))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.7.0...audit-service-v0.8.0) (2026-06-29)


### Features

* **audit:** externally-signed tamper-evidence anchors over the audit hash chain (ADR-0031 D5) ([#2383](https://github.com/JiRaska/open-bank-oss/issues/2383)) ([464892a](https://github.com/JiRaska/open-bank-oss/commit/464892ae72ca0468cd0173d73e15209627918ed0))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.6.2...audit-service-v0.7.0) (2026-06-28)


### Features

* **audit:** externally-signed tamper-evidence anchors over the audit hash chain (ADR-0031 D5) ([#2383](https://github.com/JiRaska/open-bank-oss/issues/2383)) ([29a4427](https://github.com/JiRaska/open-bank-oss/commit/29a4427b3694ffc2cfb3e5c325ef62fa3e522b0c))

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.6.1...audit-service-v0.6.2) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.6.0...audit-service-v0.6.1) (2026-06-25)


### Bug Fixes

* **sepa-instant,balance,audit,security-scanner,copilot,customer-edge,sca:** inject Clock via CDI (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2145](https://github.com/JiRaska/open-bank-oss/issues/2145)) ([d680007](https://github.com/JiRaska/open-bank-oss/commit/d68000775625cc423c95d8a27db29ff25a708f9f))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.5.0...audit-service-v0.6.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.4.0...audit-service-v0.5.0) (2026-06-15)


### Features

* **security:** customer payment non-repudiation — SCA settlement gate, identity threading, audit hash chain (ADR-0086) ([#900](https://github.com/JiRaska/open-bank-oss/issues/900)) ([fcc1e52](https://github.com/JiRaska/open-bank-oss/commit/fcc1e52b247b0eb61b9ee8d5332f110984a6fb33))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.3.0...audit-service-v0.4.0) (2026-06-12)


### Features

* **audit:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#795](https://github.com/JiRaska/open-bank-oss/issues/795)) ([1d2cd18](https://github.com/JiRaska/open-bank-oss/commit/1d2cd18c4dfd3beb85df2e0731ef12e0f2895944))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.2.0...audit-service-v0.3.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/audit-service-v0.1.0...audit-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **admin-ui:** correct security-scanner specId in API docs page ([fe954e5](https://github.com/JiRaska/open-bank-oss/commit/fe954e51e799b399c8ebba50eeed6c784818864f))
* **audit:** align Kafka topic names + add missing KafkaTopic manifests ([#380](https://github.com/JiRaska/open-bank-oss/issues/380)) ([fe954e5](https://github.com/JiRaska/open-bank-oss/commit/fe954e51e799b399c8ebba50eeed6c784818864f))
* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))

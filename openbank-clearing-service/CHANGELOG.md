# Changelog

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.9.0...clearing-service-v0.9.1) (2026-09-03)


### Bug Fixes

* **clearing:** settle commits batch, items and outbox row in one transaction ([#8509](https://github.com/JiRaska/open-bank-oss/issues/8509)) ([#8621](https://github.com/JiRaska/open-bank-oss/issues/8621)) ([bae4b49](https://github.com/JiRaska/open-bank-oss/commit/bae4b498a37b78ab1f6ee38f49c8e967a6bd2547))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.8.1...clearing-service-v0.9.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.8.0...clearing-service-v0.8.1) (2026-08-21)


### Bug Fixes

* **transaction:** publish the full TransactionType and TransactionStatus vocabularies ([#5982](https://github.com/JiRaska/open-bank-oss/issues/5982)) ([11baea4](https://github.com/JiRaska/open-bank-oss/commit/11baea4482c50d838f8c913d4ae466ccc198a53c))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.7.10...clearing-service-v0.8.0) (2026-08-19)


### Features

* **clearing:** expose pending four-eyes approvals via approval inbox ([#5679](https://github.com/JiRaska/open-bank-oss/issues/5679)) ([#5693](https://github.com/JiRaska/open-bank-oss/issues/5693)) ([dc25d40](https://github.com/JiRaska/open-bank-oss/commit/dc25d40b3de7f3f0460800c05e50eb24347a17ad))

## [0.7.10](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.7.9...clearing-service-v0.7.10) (2026-08-18)


### Bug Fixes

* **clearing:** add sourceService for AuditConsumer attribution ([#5351](https://github.com/JiRaska/open-bank-oss/issues/5351)) ([0a6d67d](https://github.com/JiRaska/open-bank-oss/commit/0a6d67d0be73249cf4f81df430432dcc3c742cdb)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)
* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.7.9](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.7.8...clearing-service-v0.7.9) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.7.8](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.7.7...clearing-service-v0.7.8) (2026-08-10)


### Bug Fixes

* **lending:** stamp business event time on the money-path audit producers ([#4412](https://github.com/JiRaska/open-bank-oss/issues/4412)) ([6e43ccc](https://github.com/JiRaska/open-bank-oss/commit/6e43ccc78ac8cd4f4a8af63743f6a530056a7510)), closes [#3914](https://github.com/JiRaska/open-bank-oss/issues/3914)

## [0.7.7](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.7.6...clearing-service-v0.7.7) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.7.6](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.7.5...clearing-service-v0.7.6) (2026-08-01)


### Bug Fixes

* **api:** stop reporting client errors as 500 — unmapped DateTimeException and catch-all recovers ([#3038](https://github.com/JiRaska/open-bank-oss/issues/3038)) ([#3057](https://github.com/JiRaska/open-bank-oss/issues/3057)) ([fafe119](https://github.com/JiRaska/open-bank-oss/commit/fafe1194c56224c98de40be8f4e9dcba018c2f91))

## [0.7.5](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.7.4...clearing-service-v0.7.5) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))

## [0.7.4](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.7.3...clearing-service-v0.7.4) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.7.2...clearing-service-v0.7.3) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))
* **clearing:** drop the M2M grant from POST /submit — no such caller exists ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2532](https://github.com/JiRaska/open-bank-oss/issues/2532)) ([427000e](https://github.com/JiRaska/open-bank-oss/commit/427000e0c1e65c26c6b8f09f4c3cb9b24f280475))

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.7.1...clearing-service-v0.7.2) (2026-07-17)


### Bug Fixes

* **clearing:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1517](https://github.com/JiRaska/open-bank-oss/issues/1517)) ([8040527](https://github.com/JiRaska/open-bank-oss/commit/8040527db9dc7634ef8160242917c272c096f8e2))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.7.0...clearing-service-v0.7.1) (2026-07-14)


### Bug Fixes

* **audit:** consume clearing.batch.event + security.ict/scan.event ([#1007](https://github.com/JiRaska/open-bank-oss/issues/1007)) ([b2f307b](https://github.com/JiRaska/open-bank-oss/commit/b2f307b76f526fa84879ec249c749498b2d1a428))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.6.7...clearing-service-v0.7.0) (2026-07-08)


### Features

* **clearing:** wire four-eyes enforcement mechanism (ADR-0155) ([#558](https://github.com/JiRaska/open-bank-oss/issues/558)) ([508be54](https://github.com/JiRaska/open-bank-oss/commit/508be54dc5bf0c06e6f70c5c622261eaff4d5e69)), closes [#413](https://github.com/JiRaska/open-bank-oss/issues/413)

## [0.6.7](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.6.6...clearing-service-v0.6.7) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.6.6](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.6.5...clearing-service-v0.6.6) (2026-07-07)


### Security

* **clearing:** enforce OPA authorization on clearing endpoints (ADR-0034 Phase 5) ([#405](https://github.com/JiRaska/open-bank-oss/issues/405)) ([eafc348](https://github.com/JiRaska/open-bank-oss/commit/eafc3482cb94a03efa9705f829ec0608a560487c))

## [0.6.5](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.6.4...clearing-service-v0.6.5) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.6.4](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.6.3...clearing-service-v0.6.4) (2026-06-30)


### Security

* **clearing:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 1) ([#2732](https://github.com/JiRaska/open-bank-oss/issues/2732)) ([84b5d43](https://github.com/JiRaska/open-bank-oss/commit/84b5d432d5b36e94628f6bda250456ec7c5c7748))

## [0.6.3](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.6.2...clearing-service-v0.6.3) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **clearing:** fix 3 bugs in clearing cycle and add batch reconciliation ([#2438](https://github.com/JiRaska/open-bank-oss/issues/2438)) ([5baa1e8](https://github.com/JiRaska/open-bank-oss/commit/5baa1e8e43617b46a68693c635e4d4b14de84a88))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.6.1...clearing-service-v0.6.2) (2026-06-29)


### Bug Fixes

* **clearing:** fix 3 bugs in clearing cycle and add batch reconciliation ([#2438](https://github.com/JiRaska/open-bank-oss/issues/2438)) ([e0a388e](https://github.com/JiRaska/open-bank-oss/commit/e0a388e6445c42beacc050ba2b4594420176b58e))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.6.0...clearing-service-v0.6.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.5.0...clearing-service-v0.6.0) (2026-06-25)


### Features

* **clearing:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2094](https://github.com/JiRaska/open-bank-oss/issues/2094)) ([a238043](https://github.com/JiRaska/open-bank-oss/commit/a238043dda4e48738cab8f21a72aff770b08524b))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.4.2...clearing-service-v0.5.0) (2026-06-25)


### Features

* **clearing:** inject Clock into application layer (ADR-0100) ([#2061](https://github.com/JiRaska/open-bank-oss/issues/2061)) ([91693f1](https://github.com/JiRaska/open-bank-oss/commit/91693f10c6b40c5177a1265a6f194164070c5f94))
* **clearing:** remove OffsetDateTime.now() defaults from domain (ADR-0100) ([#2076](https://github.com/JiRaska/open-bank-oss/issues/2076)) ([74ce0da](https://github.com/JiRaska/open-bank-oss/commit/74ce0dab7b2fb85019de33233a6090945e2f4200)), closes [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)

## [0.4.2](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.4.1...clearing-service-v0.4.2) (2026-06-25)


### Bug Fixes

* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank-oss/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank-oss/commit/92dc2d636d857b526c8276e2647de440c540577b))

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.4.0...clearing-service-v0.4.1) (2026-06-15)


### Bug Fixes

* **clearing:** seed sentinel batch + first ApiIT (batch aggregation + RBAC) ([#783](https://github.com/JiRaska/open-bank-oss/issues/783)) ([38c4e6f](https://github.com/JiRaska/open-bank-oss/commit/38c4e6f5b59379dff5281ca9bfc3ccf0ab54aeab))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.3.0...clearing-service-v0.4.0) (2026-06-12)


### Features

* **clearing:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#796](https://github.com/JiRaska/open-bank-oss/issues/796)) ([e3dd9d9](https://github.com/JiRaska/open-bank-oss/commit/e3dd9d986b3b8911ca229d35640399091d883d05))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.2.0...clearing-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/clearing-service-v0.1.1...clearing-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))


### Security

* **libs:** harden shared config + DB constraints + logging (beta pentest) ([#342](https://github.com/JiRaska/open-bank-oss/issues/342)) ([e368296](https://github.com/JiRaska/open-bank-oss/commit/e3682965a4f7df3b7328e8a741e4809604706390))

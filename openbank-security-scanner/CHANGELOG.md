# Changelog

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.7.5...security-scanner-v0.8.0) (2026-08-20)


### Features

* **analytics:** fail closed without durable backfill ([#6050](https://github.com/JiRaska/open-bank-oss/issues/6050)) ([8fca000](https://github.com/JiRaska/open-bank-oss/commit/8fca000162af7d6f6c3ed0bcb4c9fcba5d8742d8))

## [0.7.5](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.7.4...security-scanner-v0.7.5) (2026-08-18)


### Bug Fixes

* **security-scanner:** apply Flyway out-of-order to fix V4/V5 boot collision ([#5630](https://github.com/JiRaska/open-bank-oss/issues/5630)) ([57a39a6](https://github.com/JiRaska/open-bank-oss/commit/57a39a64644f4e390a2dea6fa877467e60f9441f))

## [0.7.4](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.7.3...security-scanner-v0.7.4) (2026-08-17)


### Bug Fixes

* **security-scanner:** add sourceService to ICT incident events ([#5381](https://github.com/JiRaska/open-bank-oss/issues/5381)) ([cc78dbe](https://github.com/JiRaska/open-bank-oss/commit/cc78dbe7d972957068794cacdd8e7fe6c08cd1fb)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.7.2...security-scanner-v0.7.3) (2026-08-17)


### Bug Fixes

* **security-scanner:** delete the openbank.security.scan.event outbox, which never had a writer ([#4940](https://github.com/JiRaska/open-bank-oss/issues/4940)) ([9d1d095](https://github.com/JiRaska/open-bank-oss/commit/9d1d0954c418722adc1beb712956209d98eb6a0c))

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.7.1...security-scanner-v0.7.2) (2026-08-17)


### Bug Fixes

* **security-scanner:** wire outboxDispatched into SecurityOutboxDispatcher ([#5195](https://github.com/JiRaska/open-bank-oss/issues/5195)) ([779cc0f](https://github.com/JiRaska/open-bank-oss/commit/779cc0f2033ebcec35428f1401734e819efeca34))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.7.0...security-scanner-v0.7.1) (2026-08-16)


### Bug Fixes

* **security-scanner:** give the DORA ICT incident register a durable row ([#4939](https://github.com/JiRaska/open-bank-oss/issues/4939)) ([0bf9812](https://github.com/JiRaska/open-bank-oss/commit/0bf9812af427eef52ab07bcdd3527e2e8a5ee5b2))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.6.1...security-scanner-v0.7.0) (2026-08-08)


### Features

* **ci:** check gitops workload env hostnames, and fix the four it finds ([#3974](https://github.com/JiRaska/open-bank-oss/issues/3974)) ([123633f](https://github.com/JiRaska/open-bank-oss/commit/123633fcdb7ce6bfa5b949bd1610196618e36108))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.6.0...security-scanner-v0.6.1) (2026-08-06)


### Bug Fixes

* **security-scanner:** a publish the broker rejects must not be marked SENT ([#3646](https://github.com/JiRaska/open-bank-oss/issues/3646)) ([a4cc68b](https://github.com/JiRaska/open-bank-oss/commit/a4cc68b1e95a22486f830b804023e719148c06df))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.5.7...security-scanner-v0.6.0) (2026-08-05)


### Features

* **scheduler:** register workflow liveness for second non-money-path batch (ADR-0237) ([#3739](https://github.com/JiRaska/open-bank-oss/issues/3739)) ([735e8bd](https://github.com/JiRaska/open-bank-oss/commit/735e8bdc12fbf541464aeb4f15ce767cb7866e78))

## [0.5.7](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.5.6...security-scanner-v0.5.7) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.5.6](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.5.5...security-scanner-v0.5.6) (2026-08-02)


### Bug Fixes

* **security-scanner:** run Flyway at start — the schema was never created ([#3350](https://github.com/JiRaska/open-bank-oss/issues/3350)) ([0a64027](https://github.com/JiRaska/open-bank-oss/commit/0a64027a12b7bce58ebabc85717efce73423352c))

## [0.5.5](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.5.4...security-scanner-v0.5.5) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.5.4](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.5.3...security-scanner-v0.5.4) (2026-07-11)


### Security

* **security-scanner:** add missing RBAC and OIDC config ([#777](https://github.com/JiRaska/open-bank-oss/issues/777)) ([7f168bf](https://github.com/JiRaska/open-bank-oss/commit/7f168bf1b7ea010bcfffa123fb4d44ada47da029))

## [0.5.3](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.5.2...security-scanner-v0.5.3) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.5.2](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.5.1...security-scanner-v0.5.2) (2026-07-03)


### Bug Fixes

* **libs-domain:** carry interbank settlement date through to pacs.008 (IntrBkSttlmDt) ([#195](https://github.com/JiRaska/open-bank-oss/issues/195)) ([62eef5e](https://github.com/JiRaska/open-bank-oss/commit/62eef5ef21626a56099ccfe9ebc6f6e5387a85b6))
* **security:** pin openbank-security-scanner Dockerfile.prebuilt base image digest ([#192](https://github.com/JiRaska/open-bank-oss/issues/192)) ([2cf0481](https://github.com/JiRaska/open-bank-oss/commit/2cf0481545a232330965f86751d0b5943ce30cf9))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.5.0...security-scanner-v0.5.1) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.4.0...security-scanner-v0.5.0) (2026-06-29)


### Features

* **observability:** RUM gateway cardinality budget, attribute audit, HPA (ADR-0088 O1-O3) ([#2208](https://github.com/JiRaska/open-bank-oss/issues/2208)) ([e65ce75](https://github.com/JiRaska/open-bank-oss/commit/e65ce75eb99121c49258f7b998d64e99a5e24dbe))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.3.3...security-scanner-v0.4.0) (2026-06-27)


### Features

* **observability:** RUM gateway cardinality budget, attribute audit, HPA (ADR-0088 O1-O3) ([#2208](https://github.com/JiRaska/open-bank-oss/issues/2208)) ([b7f0849](https://github.com/JiRaska/open-bank-oss/commit/b7f08494cf923f66dbd910e92522fb9453394de7))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.3.3](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.3.2...security-scanner-v0.3.3) (2026-06-25)


### Bug Fixes

* **sepa-instant,balance,audit,security-scanner,copilot,customer-edge,sca:** inject Clock via CDI (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2145](https://github.com/JiRaska/open-bank-oss/issues/2145)) ([d680007](https://github.com/JiRaska/open-bank-oss/commit/d68000775625cc423c95d8a27db29ff25a708f9f))

## [0.3.2](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.3.1...security-scanner-v0.3.2) (2026-06-25)


### Bug Fixes

* **security-scanner:** correct stale target namespaces in scan config ([#1816](https://github.com/JiRaska/open-bank-oss/issues/1816)) ([6442ba7](https://github.com/JiRaska/open-bank-oss/commit/6442ba77d8a8b3fcfb861f04e65ac432bd72eaf3)), closes [#1811](https://github.com/JiRaska/open-bank-oss/issues/1811)

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.3.0...security-scanner-v0.3.1) (2026-06-15)


### Bug Fixes

* **dispute:** assign unique HTTP port 8135 (resolve collision with lending) ([#1045](https://github.com/JiRaska/open-bank-oss/issues/1045)) ([b219e93](https://github.com/JiRaska/open-bank-oss/commit/b219e93ac044ee2d7c4b8e352bbba17265c624dc))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.2.0...security-scanner-v0.3.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **security-scanner:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#810](https://github.com/JiRaska/open-bank-oss/issues/810)) ([0f7ffc9](https://github.com/JiRaska/open-bank-oss/commit/0f7ffc92487e7eedb6039a93419cf9ae6774b979))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/security-scanner-v0.1.1...security-scanner-v0.2.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **admin-ui:** quality dashboard — Pact contract tests, pitest mutation, composite score (ADR-0063) ([#360](https://github.com/JiRaska/open-bank-oss/issues/360)) ([00b25bc](https://github.com/JiRaska/open-bank-oss/commit/00b25bcc934fea8728bb4b404166cd21c273495b))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **security-scanner:** deploy security-scanner to GitOps + sync governance manifest ([#354](https://github.com/JiRaska/open-bank-oss/issues/354)) ([eca7198](https://github.com/JiRaska/open-bank-oss/commit/eca71982de5c16f4c7c827087f98b3af1f81cd97))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))

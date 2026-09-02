# Changelog

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.9.6...dispute-service-v0.10.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.9.6](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.9.5...dispute-service-v0.9.6) (2026-08-18)


### Bug Fixes

* **dispute:** add sourceService to audit-attribution outbox payloads ([#5344](https://github.com/JiRaska/open-bank-oss/issues/5344)) ([35ce80e](https://github.com/JiRaska/open-bank-oss/commit/35ce80efe7e5d8f4076cf89655887513ac90d704)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)

## [0.9.5](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.9.4...dispute-service-v0.9.5) (2026-08-18)


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.9.4](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.9.3...dispute-service-v0.9.4) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.9.3](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.9.2...dispute-service-v0.9.3) (2026-08-16)


### Bug Fixes

* **observability:** track gauge refresh liveness ([#5087](https://github.com/JiRaska/open-bank-oss/issues/5087)) ([86904fa](https://github.com/JiRaska/open-bank-oss/commit/86904faa8ae0fdfd7e085b4c4f175691ae07c865))

## [0.9.2](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.9.1...dispute-service-v0.9.2) (2026-08-09)


### Bug Fixes

* **customer-edge:** answer 400, not 500, for a missing required query parameter ([#4211](https://github.com/JiRaska/open-bank-oss/issues/4211)) ([4ddb6ef](https://github.com/JiRaska/open-bank-oss/commit/4ddb6efeb23864fe65a4f2624f8722e1fcae04fb)), closes [#3624](https://github.com/JiRaska/open-bank-oss/issues/3624)

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.9.0...dispute-service-v0.9.1) (2026-08-08)


### Bug Fixes

* send occurredAt on the four non-money-path domain-event producers ([#3926](https://github.com/JiRaska/open-bank-oss/issues/3926)) ([4a2080c](https://github.com/JiRaska/open-bank-oss/commit/4a2080c3a4de10b2a858b7111ac83d63c60114d1))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.8.5...dispute-service-v0.9.0) (2026-08-08)


### Features

* **dispute:** publish dispute.opened so an open dispute is visible outside this service ([#4087](https://github.com/JiRaska/open-bank-oss/issues/4087)) ([ef72727](https://github.com/JiRaska/open-bank-oss/commit/ef72727f32757bb13b104dcdfc664c2b614a30fb)), closes [#4070](https://github.com/JiRaska/open-bank-oss/issues/4070)

## [0.8.5](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.8.4...dispute-service-v0.8.5) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.8.4](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.8.3...dispute-service-v0.8.4) (2026-08-01)


### Bug Fixes

* **api:** stop reporting client errors as 500 — unmapped DateTimeException and catch-all recovers ([#3038](https://github.com/JiRaska/open-bank-oss/issues/3038)) ([#3057](https://github.com/JiRaska/open-bank-oss/issues/3057)) ([fafe119](https://github.com/JiRaska/open-bank-oss/commit/fafe1194c56224c98de40be8f4e9dcba018c2f91))

## [0.8.3](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.8.2...dispute-service-v0.8.3) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.8.1...dispute-service-v0.8.2) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.8.0...dispute-service-v0.8.1) (2026-07-17)


### Bug Fixes

* **dispute:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1493](https://github.com/JiRaska/open-bank-oss/issues/1493)) ([a889641](https://github.com/JiRaska/open-bank-oss/commit/a889641cc5b3bf430b4001bcfecbbe14bfaa3a41))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.7.6...dispute-service-v0.8.0) (2026-07-09)


### Features

* **dispute:** add tamper-evident evidence chain and remediation workflow ([#632](https://github.com/JiRaska/open-bank-oss/issues/632)) ([c457800](https://github.com/JiRaska/open-bank-oss/commit/c457800466e8ca579cb546e85653df603f40c281))

## [0.7.6](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.7.5...dispute-service-v0.7.6) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.7.5](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.7.4...dispute-service-v0.7.5) (2026-07-04)


### Bug Fixes

* three api-fuzz.yml boot failures (dispute, party, consent) ([#233](https://github.com/JiRaska/open-bank-oss/issues/233)) ([6534e12](https://github.com/JiRaska/open-bank-oss/commit/6534e12e5a90e62df1190d356f8058f8b476b84d))

## [0.7.4](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.7.3...dispute-service-v0.7.4) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.7.2...dispute-service-v0.7.3) (2026-06-30)


### Security

* **dispute:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2757](https://github.com/JiRaska/open-bank-oss/issues/2757)) ([6e39055](https://github.com/JiRaska/open-bank-oss/commit/6e39055070c7f1824ea2c00856fb7803f3196077))

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.7.1...dispute-service-v0.7.2) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.7.0...dispute-service-v0.7.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **interest,dispute,lending:** complete Clock injection (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2136](https://github.com/JiRaska/open-bank-oss/issues/2136)) ([41a2921](https://github.com/JiRaska/open-bank-oss/commit/41a2921b9b89cc06025cc71a4b428cb019fb499f))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.6.0...dispute-service-v0.7.0) (2026-06-25)


### Features

* **dispute:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2077](https://github.com/JiRaska/open-bank-oss/issues/2077)) ([53115f3](https://github.com/JiRaska/open-bank-oss/commit/53115f3e3b16596c9d60d4c707d9013976afcfdd)), closes [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.5.0...dispute-service-v0.6.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))


### Bug Fixes

* **customer-edge:** fix SCA challenge 400 — switch body from String to JsonNode ([213e577](https://github.com/JiRaska/open-bank-oss/commit/213e577b9fb53a83f2fe8a28d294c5380381c0bf))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.4.0...dispute-service-v0.5.0) (2026-06-15)


### Features

* **dispute:** complaint aggregate + statutory deadline clock (ADR-0085 §1-2) ([#989](https://github.com/JiRaska/open-bank-oss/issues/989)) ([66698a9](https://github.com/JiRaska/open-bank-oss/commit/66698a90995a294d189d639895b8c8e9c77ee946)), closes [#851](https://github.com/JiRaska/open-bank-oss/issues/851)
* **dispute:** complaint statutory-deadline metrics (ADR-0085 §2) ([#1100](https://github.com/JiRaska/open-bank-oss/issues/1100)) ([9906a9a](https://github.com/JiRaska/open-bank-oss/commit/9906a9a2b134725e0a4b04edc894295b8fb3a2f1)), closes [#851](https://github.com/JiRaska/open-bank-oss/issues/851)


### Bug Fixes

* **dispute:** assign unique HTTP port 8135 (resolve collision with lending) ([#1045](https://github.com/JiRaska/open-bank-oss/issues/1045)) ([b219e93](https://github.com/JiRaska/open-bank-oss/commit/b219e93ac044ee2d7c4b8e352bbba17265c624dc))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.3.0...dispute-service-v0.4.0) (2026-06-12)


### Features

* **dispute:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#798](https://github.com/JiRaska/open-bank-oss/issues/798)) ([aef5bc7](https://github.com/JiRaska/open-bank-oss/commit/aef5bc702c6ac7402b1f186b8f926d9c918bf387))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.2.0...dispute-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/dispute-service-v0.1.1...dispute-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))


### Security

* **libs:** harden shared config + DB constraints + logging (beta pentest) ([#342](https://github.com/JiRaska/open-bank-oss/issues/342)) ([e368296](https://github.com/JiRaska/open-bank-oss/commit/e3682965a4f7df3b7328e8a741e4809604706390))

# Changelog

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.9.0...onboarding-service-v0.10.0) (2026-09-07)


### Features

* **kyb:** legal-entity onboarding, representation mandates and profile switching (ADR-0284) ([#8863](https://github.com/JiRaska/open-bank-oss/issues/8863)) ([3766d3d](https://github.com/JiRaska/open-bank-oss/commit/3766d3de2281dbeb17e0b7a6a4e6c754988d1145))


### Bug Fixes

* **onboarding:** reject an unparseable ?stage= with 400 instead of returning every record ([#8710](https://github.com/JiRaska/open-bank-oss/issues/8710)) ([8e4cc9c](https://github.com/JiRaska/open-bank-oss/commit/8e4cc9c1b0aa5d31d4ec7a359397ceae8b0b2ce2)), closes [#8699](https://github.com/JiRaska/open-bank-oss/issues/8699)

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.8.4...onboarding-service-v0.9.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.8.4](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.8.3...onboarding-service-v0.8.4) (2026-08-23)


### Bug Fixes

* **onboarding:** SCA enrolments are seeded, not dropped, and device_count is derived ([#6617](https://github.com/JiRaska/open-bank-oss/issues/6617)) ([2c50318](https://github.com/JiRaska/open-bank-oss/commit/2c503183bafbff66fed399e43de8222470beb57e)), closes [#6248](https://github.com/JiRaska/open-bank-oss/issues/6248)

## [0.8.3](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.8.2...onboarding-service-v0.8.3) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.8.1...onboarding-service-v0.8.2) (2026-08-21)


### Bug Fixes

* **onboarding:** stop counting a dropped projection as a success ([#6258](https://github.com/JiRaska/open-bank-oss/issues/6258)) ([488eea6](https://github.com/JiRaska/open-bank-oss/commit/488eea6cfb85370f242421d2691e3835275b8645))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.8.0...onboarding-service-v0.8.1) (2026-08-20)


### Bug Fixes

* **campaign,onboarding:** stop swallowing transient consumer failures as an ack ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5757](https://github.com/JiRaska/open-bank-oss/issues/5757)) ([4f2d6e5](https://github.com/JiRaska/open-bank-oss/commit/4f2d6e5eb84bf3435c393d5c2fc0be79db20817e))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.7.3...onboarding-service-v0.8.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.7.2...onboarding-service-v0.7.3) (2026-08-16)


### Bug Fixes

* **observability:** track gauge refresh liveness ([#5087](https://github.com/JiRaska/open-bank-oss/issues/5087)) ([86904fa](https://github.com/JiRaska/open-bank-oss/commit/86904faa8ae0fdfd7e085b4c4f175691ae07c865))

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.7.1...onboarding-service-v0.7.2) (2026-08-16)


### Bug Fixes

* **infra:** give six services an OIDC client they can actually mint from ([#4990](https://github.com/JiRaska/open-bank-oss/issues/4990)) ([f43f88c](https://github.com/JiRaska/open-bank-oss/commit/f43f88c815fd50c32ef797147c6cbc57f060cab0))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.7.0...onboarding-service-v0.7.1) (2026-08-14)


### Bug Fixes

* **sca:** DEVICE_ENROLLED carries no eventType in the payload body, so onboarding has never projected one ([#4692](https://github.com/JiRaska/open-bank-oss/issues/4692)) ([e36e5a5](https://github.com/JiRaska/open-bank-oss/commit/e36e5a505ad1ab3352ab4b40ed53e5ceb5c22725))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.6.0...onboarding-service-v0.7.0) (2026-08-14)


### Features

* **scheduler:** register workflow liveness on four retention and cleanup jobs (ADR-0237) ([#4739](https://github.com/JiRaska/open-bank-oss/issues/4739)) ([c2a2fa4](https://github.com/JiRaska/open-bank-oss/commit/c2a2fa4b788a172ef85c8babb439cecd10fbfe23)), closes [#3345](https://github.com/JiRaska/open-bank-oss/issues/3345)

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.5.9...onboarding-service-v0.6.0) (2026-08-08)


### Features

* **ci:** check gitops workload env hostnames, and fix the four it finds ([#3974](https://github.com/JiRaska/open-bank-oss/issues/3974)) ([123633f](https://github.com/JiRaska/open-bank-oss/commit/123633fcdb7ce6bfa5b949bd1610196618e36108))

## [0.5.9](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.5.8...onboarding-service-v0.5.9) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.5.8](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.5.7...onboarding-service-v0.5.8) (2026-07-16)


### Bug Fixes

* **party-service:** restore @PactBroker on provider verification (unblocks auto-deploy) ([#1166](https://github.com/JiRaska/open-bank-oss/issues/1166)) ([f9f28e5](https://github.com/JiRaska/open-bank-oss/commit/f9f28e5c700d5e98df59416aba4ac669e62e47a3))

## [0.5.7](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.5.6...onboarding-service-v0.5.7) (2026-07-11)


### Bug Fixes

* **onboarding-service:** fix silent Kafka group.id/auto.offset.reset config bug ([#692](https://github.com/JiRaska/open-bank-oss/issues/692)) ([2a2ec51](https://github.com/JiRaska/open-bank-oss/commit/2a2ec512249af9d8a015b65b020a2a9e2356c4d2)), closes [#686](https://github.com/JiRaska/open-bank-oss/issues/686)

## [0.5.6](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.5.5...onboarding-service-v0.5.6) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.5.5](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.5.4...onboarding-service-v0.5.5) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.5.4](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.5.3...onboarding-service-v0.5.4) (2026-06-30)


### Security

* **onboarding:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2754](https://github.com/JiRaska/open-bank-oss/issues/2754)) ([852d2e2](https://github.com/JiRaska/open-bank-oss/commit/852d2e22c3d96ff5557c767c80a6f2aeae8ebf2c))

## [0.5.3](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.5.2...onboarding-service-v0.5.3) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **onboarding:** handle PARTY_ERASED to erase onboarding read-model (GDPR Art. 17) ([#2444](https://github.com/JiRaska/open-bank-oss/issues/2444)) ([da7cf77](https://github.com/JiRaska/open-bank-oss/commit/da7cf77eb2666cc545899ff6cfe1493bcbd6e86e))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.5.2](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.5.1...onboarding-service-v0.5.2) (2026-06-29)


### Bug Fixes

* **onboarding:** handle PARTY_ERASED to erase onboarding read-model (GDPR Art. 17) ([#2444](https://github.com/JiRaska/open-bank-oss/issues/2444)) ([406cf85](https://github.com/JiRaska/open-bank-oss/commit/406cf851fd0625f20ba90e20db451c97d15a25b1))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.5.0...onboarding-service-v0.5.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.4.1...onboarding-service-v0.5.0) (2026-06-25)


### Features

* **tpp-registry,statement,onboarding,agent,settlement,sdd:** inject Clock (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2138](https://github.com/JiRaska/open-bank-oss/issues/2138)) ([baa0d03](https://github.com/JiRaska/open-bank-oss/commit/baa0d03bcef7a1cd48cb7e115410ab625a26acde))

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.4.0...onboarding-service-v0.4.1) (2026-06-23)


### Bug Fixes

* **infra:** commit swift-service-db Pod Identity association for WAL backups (ADR-0104 D4) ([#1793](https://github.com/JiRaska/open-bank-oss/issues/1793)) ([49fc6dd](https://github.com/JiRaska/open-bank-oss/commit/49fc6ddf988952f6281b4689f8c7eee1670a03f9))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.3.0...onboarding-service-v0.4.0) (2026-06-21)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))
* **infra:** C8 observability sweep — PodMonitor namespaces + MeterRegistry on 4 services ([#1410](https://github.com/JiRaska/open-bank-oss/issues/1410)) ([9201493](https://github.com/JiRaska/open-bank-oss/commit/920149368ea630e0117a4a480684c16d0a5517e2))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.2.0...onboarding-service-v0.3.0) (2026-06-12)


### Features

* **account,party,onboarding:** sprint 2 — sanctions persistence, GDPR erasure, doc download, AbandonedCleaner fix ([#475](https://github.com/JiRaska/open-bank-oss/issues/475)) ([05b20d7](https://github.com/JiRaska/open-bank-oss/commit/05b20d764a6373d0ffd96ca84ab5a9a6ed54291f))
* **admin-ui,onboarding:** Sprint 3 — legalName in account form, onboarding REST security (ADR-0068) ([#477](https://github.com/JiRaska/open-bank-oss/issues/477)) ([251f2e6](https://github.com/JiRaska/open-bank-oss/commit/251f2e6796b32063bfcde0542fab281b71ec0faa))
* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **onboarding:** Dockerfile + GitOps deploy (ADR-0068) ([#478](https://github.com/JiRaska/open-bank-oss/issues/478)) ([1226fef](https://github.com/JiRaska/open-bank-oss/commit/1226feff23448e42cc82e21ee70ce631db9b8790))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **onboarding:** persist read-model rows — subscribe persist() Uni + create id sequence ([#528](https://github.com/JiRaska/open-bank-oss/issues/528)) ([45b7ec0](https://github.com/JiRaska/open-bank-oss/commit/45b7ec03f6ea3bf40c31a94b7e5ba427051ca0cc))
* **onboarding:** read kycCaseId from KYC events so the KYC funnel advances ([#531](https://github.com/JiRaska/open-bank-oss/issues/531)) ([a4a6333](https://github.com/JiRaska/open-bank-oss/commit/a4a633331582b2f27dd4b4372b095324c31da52e))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/onboarding-service-v0.1.0...onboarding-service-v0.2.0) (2026-06-06)


### Features

* **onboarding:** add onboarding-service read-model projection (ADR-0068 Gap 3) ([#421](https://github.com/JiRaska/open-bank-oss/issues/421)) ([3ab13b5](https://github.com/JiRaska/open-bank-oss/commit/3ab13b53fed371ac1aa278d4a4260b17988d0b80))


### Bug Fixes

* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))

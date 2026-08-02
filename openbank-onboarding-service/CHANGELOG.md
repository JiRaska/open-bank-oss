# Changelog

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

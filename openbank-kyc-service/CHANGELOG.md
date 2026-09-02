# Changelog

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.13.0...kyc-service-v0.14.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.12.0...kyc-service-v0.13.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.11.2...kyc-service-v0.12.0) (2026-08-22)


### Features

* **kyc:** detect parties with no KYC case via reconciler ([#5698](https://github.com/JiRaska/open-bank-oss/issues/5698)) ([#5748](https://github.com/JiRaska/open-bank-oss/issues/5748)) ([dcef87f](https://github.com/JiRaska/open-bank-oss/commit/dcef87f9ee7a5cb101adde1e262f5cd8dd62a4c0))


### Bug Fixes

* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [0.11.2](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.11.1...kyc-service-v0.11.2) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [0.11.1](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.11.0...kyc-service-v0.11.1) (2026-08-21)


### Bug Fixes

* **transaction:** publish the full TransactionType and TransactionStatus vocabularies ([#5982](https://github.com/JiRaska/open-bank-oss/issues/5982)) ([11baea4](https://github.com/JiRaska/open-bank-oss/commit/11baea4482c50d838f8c913d4ae466ccc198a53c))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.10.12...kyc-service-v0.11.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.10.12](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.10.11...kyc-service-v0.10.12) (2026-08-18)


### Bug Fixes

* **kyc:** add sourceService to KycEvents for audit attribution ([#5336](https://github.com/JiRaska/open-bank-oss/issues/5336)) ([3aa5e63](https://github.com/JiRaska/open-bank-oss/commit/3aa5e63af6335f04c2f773a9eae1a3429054910d)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)

## [0.10.11](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.10.10...kyc-service-v0.10.11) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.10.10](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.10.9...kyc-service-v0.10.10) (2026-08-13)


### Bug Fixes

* **kyc:** record retention workflow liveness ([#4627](https://github.com/JiRaska/open-bank-oss/issues/4627)) ([61dfabb](https://github.com/JiRaska/open-bank-oss/commit/61dfabb16f42d7c27245dd786de6dcb60899c489))

## [0.10.9](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.10.8...kyc-service-v0.10.9) (2026-08-10)


### Bug Fixes

* **audit:** give the unattributed producers a real actor, and a way to say nobody did it ([#4424](https://github.com/JiRaska/open-bank-oss/issues/4424)) ([0cadda3](https://github.com/JiRaska/open-bank-oss/commit/0cadda3725280119a86dba722efa692e1f783fc9))

## [0.10.8](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.10.7...kyc-service-v0.10.8) (2026-08-09)


### Bug Fixes

* **kyc:** write KYC case events to the outbox, retire the direct emitter ([#4378](https://github.com/JiRaska/open-bank-oss/issues/4378)) ([78be93d](https://github.com/JiRaska/open-bank-oss/commit/78be93d1d60ee909606acb531a434b5efe7a4c47)), closes [#4007](https://github.com/JiRaska/open-bank-oss/issues/4007)

## [0.10.7](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.10.6...kyc-service-v0.10.7) (2026-08-09)


### Bug Fixes

* **libs:** stamp ApiError.timestamp at construction instead of serving 1970 ([#3880](https://github.com/JiRaska/open-bank-oss/issues/3880)) ([b3e6672](https://github.com/JiRaska/open-bank-oss/commit/b3e6672c9e13470fc6353ad8a5483e4075875b1f))

## [0.10.6](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.10.5...kyc-service-v0.10.6) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.10.5](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.10.4...kyc-service-v0.10.5) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.10.4](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.10.3...kyc-service-v0.10.4) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.10.3](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.10.2...kyc-service-v0.10.3) (2026-07-17)


### Bug Fixes

* **kyc:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1494](https://github.com/JiRaska/open-bank-oss/issues/1494)) ([cd9fafb](https://github.com/JiRaska/open-bank-oss/commit/cd9fafb2249aa9473ee9c78fcc0b86f4241a93d4))

## [0.10.2](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.10.1...kyc-service-v0.10.2) (2026-07-16)


### Bug Fixes

* **party-service:** restore @PactBroker on provider verification (unblocks auto-deploy) ([#1166](https://github.com/JiRaska/open-bank-oss/issues/1166)) ([f9f28e5](https://github.com/JiRaska/open-bank-oss/commit/f9f28e5c700d5e98df59416aba4ac669e62e47a3))

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.10.0...kyc-service-v0.10.1) (2026-07-09)


### Bug Fixes

* **kyc-service:** fix silent Kafka group.id/auto.offset.reset config bug ([#694](https://github.com/JiRaska/open-bank-oss/issues/694)) ([7b9681f](https://github.com/JiRaska/open-bank-oss/commit/7b9681f5274d3dd0eca04db83b39e4cd198a8c4d)), closes [#686](https://github.com/JiRaska/open-bank-oss/issues/686)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.9.1...kyc-service-v0.10.0) (2026-07-09)


### Features

* **kyc:** add first-increment PEP screening via sanctions-service PEP_GLOBAL list ([#634](https://github.com/JiRaska/open-bank-oss/issues/634)) ([9a2a31b](https://github.com/JiRaska/open-bank-oss/commit/9a2a31bc8aef91209c184005018eb36bb241cac1)), closes [#626](https://github.com/JiRaska/open-bank-oss/issues/626)

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.9.0...kyc-service-v0.9.1) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.8.1...kyc-service-v0.9.0) (2026-07-07)


### Features

* **gdpr:** add kyc/card export coverage and disabled-by-default session-log retention ([#356](https://github.com/JiRaska/open-bank-oss/issues/356)) ([d627e0a](https://github.com/JiRaska/open-bank-oss/commit/d627e0a0d9c7514f65b53f8d253c2ae5394e5386))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.8.0...kyc-service-v0.8.1) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.7.1...kyc-service-v0.8.0) (2026-06-30)


### Features

* **kyc:** enforce maker-checker via ROLE_KYC_OPENER / ROLE_KYC_REVIEWER (ADR-0116) ([#13](https://github.com/JiRaska/open-bank-oss/issues/13)) ([6f24b9f](https://github.com/JiRaska/open-bank-oss/commit/6f24b9f8deb8da8b367052c3952666a16e13d496))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.7.0...kyc-service-v0.7.1) (2026-06-30)


### Security

* **kyc:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2751](https://github.com/JiRaska/open-bank-oss/issues/2751)) ([89a246d](https://github.com/JiRaska/open-bank-oss/commit/89a246d0218a0cae15c441270bcaf9f5d512ffcc))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.6.0...kyc-service-v0.7.0) (2026-06-29)


### Features

* **kyc:** GDPR Art.5 KYC case deletion after AML hold period (ADR-0118 §5) ([#2480](https://github.com/JiRaska/open-bank-oss/issues/2480)) ([abe6e17](https://github.com/JiRaska/open-bank-oss/commit/abe6e1704c36c97aa79c06bcfae03ee936a643df))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **kyc:** handle PARTY_ERASED event to anonymise PII (GDPR Art. 17) ([fba1ffc](https://github.com/JiRaska/open-bank-oss/commit/fba1ffc417ea649dcf68a1a69d32189e9d391a7e))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.5.2...kyc-service-v0.6.0) (2026-06-29)


### Features

* **kyc:** GDPR Art.5 KYC case deletion after AML hold period (ADR-0118 §5) ([#2480](https://github.com/JiRaska/open-bank-oss/issues/2480)) ([77d299f](https://github.com/JiRaska/open-bank-oss/commit/77d299f58c33083a2f288fbea881d30989d54291))

## [0.5.2](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.5.1...kyc-service-v0.5.2) (2026-06-28)


### Bug Fixes

* **kyc:** handle PARTY_ERASED event to anonymise PII (GDPR Art. 17) ([033753c](https://github.com/JiRaska/open-bank-oss/commit/033753c3d036faa36a3d05c95c4800c577857e5e))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.5.0...kyc-service-v0.5.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.4.1...kyc-service-v0.5.0) (2026-06-25)


### Features

* **kyc:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2133](https://github.com/JiRaska/open-bank-oss/issues/2133)) ([2c0e68c](https://github.com/JiRaska/open-bank-oss/commit/2c0e68cfcd1b68dd7162b31b362b295dea140307))

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.4.0...kyc-service-v0.4.1) (2026-06-23)


### Bug Fixes

* **infra:** commit swift-service-db Pod Identity association for WAL backups (ADR-0104 D4) ([#1793](https://github.com/JiRaska/open-bank-oss/issues/1793)) ([49fc6dd](https://github.com/JiRaska/open-bank-oss/commit/49fc6ddf988952f6281b4689f8c7eee1670a03f9))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.3.1...kyc-service-v0.4.0) (2026-06-21)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))
* **kyc:** approve/reject with SecurityContext identity and mandatory reason (ADR-0068) ([#1292](https://github.com/JiRaska/open-bank-oss/issues/1292)) ([12cd679](https://github.com/JiRaska/open-bank-oss/commit/12cd67970386db722555c2d7a8488392c0fad00c))
* **libs:** flip authz.enforce to true for non-money-path services (ADR-0034 D5) ([#1365](https://github.com/JiRaska/open-bank-oss/issues/1365)) ([6a4df3d](https://github.com/JiRaska/open-bank-oss/commit/6a4df3d763b026f66c683b161e1160d22a2a89e6))

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.3.0...kyc-service-v0.3.1) (2026-06-15)


### Bug Fixes

* **kyc:** 409 on duplicate active case + active-status lookup + reuse log ([#972](https://github.com/JiRaska/open-bank-oss/issues/972)) ([5846fee](https://github.com/JiRaska/open-bank-oss/commit/5846feeea6396e9229377d4fd7dbda087afae8c1)), closes [#536](https://github.com/JiRaska/open-bank-oss/issues/536)
* **kyc:** remove duplicate V5 Flyway migration (crashed startup) ([#1069](https://github.com/JiRaska/open-bank-oss/issues/1069)) ([c28f690](https://github.com/JiRaska/open-bank-oss/commit/c28f690416ba811f8a20ff1b93fb8d4a057d33d7))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.2.0...kyc-service-v0.3.0) (2026-06-12)


### Features

* **account,party,onboarding:** sprint 2 — sanctions persistence, GDPR erasure, doc download, AbandonedCleaner fix ([#475](https://github.com/JiRaska/open-bank-oss/issues/475)) ([05b20d7](https://github.com/JiRaska/open-bank-oss/commit/05b20d764a6373d0ffd96ca84ab5a9a6ed54291f))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **kyc:** KYC metrics + outbox backlog gauge (ADR-0077/0079) ([#792](https://github.com/JiRaska/open-bank-oss/issues/792)) ([1a4e119](https://github.com/JiRaska/open-bank-oss/commit/1a4e11987aa02a1aa4d568e0fc744ce1b059b494))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/kyc-service-v0.1.1...kyc-service-v0.2.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **kyc:** auto-open a KYC case on PARTY_CREATED ([#534](https://github.com/JiRaska/open-bank-oss/issues/534)) ([0935320](https://github.com/JiRaska/open-bank-oss/commit/09353204d58b819a64b388c3fb4f61ce9d3c9658))
* **kyc:** sandbox straight-through auto-approve on PARTY_CREATED (ADR-0073 ph3, re-do) ([#541](https://github.com/JiRaska/open-bank-oss/issues/541)) ([f8c5f1f](https://github.com/JiRaska/open-bank-oss/commit/f8c5f1fee24c982fedf4e39d12f526ec399761aa))
* **party,kyc:** add ?status= filter to list endpoints for onboarding cockpit ([#420](https://github.com/JiRaska/open-bank-oss/issues/420)) ([3c3cee8](https://github.com/JiRaska/open-bank-oss/commit/3c3cee8cec1ca4896f9e30a1da5ff1c180e2a05f))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))

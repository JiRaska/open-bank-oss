# Changelog

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.8.0...tpp-registry-service-v0.9.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.7.1...tpp-registry-service-v0.8.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.7.0...tpp-registry-service-v0.7.1) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.14...tpp-registry-service-v0.7.0) (2026-08-16)


### Features

* **libs-runtime:** wire outboxDispatched/outboxDead metrics into AbstractOutboxDispatcher ([#5071](https://github.com/JiRaska/open-bank-oss/issues/5071)) ([8da83b0](https://github.com/JiRaska/open-bank-oss/commit/8da83b073b07052316c56425290579ff162dcbff)), closes [#5049](https://github.com/JiRaska/open-bank-oss/issues/5049)

## [0.6.14](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.13...tpp-registry-service-v0.6.14) (2026-08-16)


### Bug Fixes

* **tpp-registry:** write TPP lifecycle events to the outbox nothing wrote to ([#4995](https://github.com/JiRaska/open-bank-oss/issues/4995)) ([2da2c6c](https://github.com/JiRaska/open-bank-oss/commit/2da2c6c2a73fec9fb29e7003e3b71b13063fb532)), closes [#4007](https://github.com/JiRaska/open-bank-oss/issues/4007)

## [0.6.13](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.12...tpp-registry-service-v0.6.13) (2026-08-09)


### Bug Fixes

* **jaxrs-params:** answer 400, not 500, for a missing required query/header parameter ([#4375](https://github.com/JiRaska/open-bank-oss/issues/4375)) ([32ab2a2](https://github.com/JiRaska/open-bank-oss/commit/32ab2a2dfe0d208ae5ba865758c774ec47a92d09)), closes [#4175](https://github.com/JiRaska/open-bank-oss/issues/4175)

## [0.6.12](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.11...tpp-registry-service-v0.6.12) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.6.11](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.10...tpp-registry-service-v0.6.11) (2026-08-02)


### Bug Fixes

* **ci:** forward the Pact Broker properties into the test JVM for the last three providers ([#3301](https://github.com/JiRaska/open-bank-oss/issues/3301)) ([cf00673](https://github.com/JiRaska/open-bank-oss/commit/cf0067340539a88f16d9455095735aa6211839d6))

## [0.6.10](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.9...tpp-registry-service-v0.6.10) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.6.9](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.8...tpp-registry-service-v0.6.9) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.6.8](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.7...tpp-registry-service-v0.6.8) (2026-07-25)


### Bug Fixes

* **tpp-registry:** map the domain id to a real UUID column, not the BIGSERIAL primary key ([#2405](https://github.com/JiRaska/open-bank-oss/issues/2405)) ([16e1412](https://github.com/JiRaska/open-bank-oss/commit/16e1412f10fd39e9e32199c23dd36bf9f866a54e)), closes [#2340](https://github.com/JiRaska/open-bank-oss/issues/2340)

## [0.6.7](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.6...tpp-registry-service-v0.6.7) (2026-07-17)


### Bug Fixes

* **tpp-registry:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1562](https://github.com/JiRaska/open-bank-oss/issues/1562)) ([fa41b67](https://github.com/JiRaska/open-bank-oss/commit/fa41b67c41f0a09ee8d1a31dbc6e38103b943208))

## [0.6.6](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.5...tpp-registry-service-v0.6.6) (2026-07-11)


### Security

* **standing-order,tpp-registry:** add missing RBAC to fully-open endpoints ([#758](https://github.com/JiRaska/open-bank-oss/issues/758)) ([5ed0f4c](https://github.com/JiRaska/open-bank-oss/commit/5ed0f4cba2d47e288a8000f48d122418e878318f))

## [0.6.5](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.4...tpp-registry-service-v0.6.5) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.6.4](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.3...tpp-registry-service-v0.6.4) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.6.3](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.2...tpp-registry-service-v0.6.3) (2026-06-30)


### Security

* **standing-order,tpp-registry:** Kafka mTLS code-side prep — SSL defaults + RBAC pre-registration (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2764](https://github.com/JiRaska/open-bank-oss/issues/2764)) ([96189bd](https://github.com/JiRaska/open-bank-oss/commit/96189bd19e44370bbfe6584d0a7bd6bec38dac10))

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.1...tpp-registry-service-v0.6.2) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.6.0...tpp-registry-service-v0.6.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.5.0...tpp-registry-service-v0.6.0) (2026-06-25)


### Features

* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank-oss/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank-oss/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))
* **tpp-registry,statement,onboarding,agent,settlement,sdd:** inject Clock (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2138](https://github.com/JiRaska/open-bank-oss/issues/2138)) ([baa0d03](https://github.com/JiRaska/open-bank-oss/commit/baa0d03bcef7a1cd48cb7e115410ab625a26acde))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.4.0...tpp-registry-service-v0.5.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.3.0...tpp-registry-service-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **tpp-registry:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#809](https://github.com/JiRaska/open-bank-oss/issues/809)) ([e77ff2f](https://github.com/JiRaska/open-bank-oss/commit/e77ff2fb920de0e36620ea5b9cef9667eef90706))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.2.0...tpp-registry-service-v0.3.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/tpp-registry-service-v0.1.0...tpp-registry-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **tpp-registry:** drop duplicate IllegalArgument mapper, defer to libs (ADR-0049 D4) ([#330](https://github.com/JiRaska/open-bank-oss/issues/330)) ([6e960a5](https://github.com/JiRaska/open-bank-oss/commit/6e960a5788436e092be73b7f92123aef4beee98d))

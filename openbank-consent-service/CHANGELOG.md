# Changelog

## [0.24.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.23.0...consent-service-v0.24.0) (2026-09-07)


### Features

* **notifications:** tell customers when a third party gains or loses account access ([#8491](https://github.com/JiRaska/open-bank-oss/issues/8491)) ([add3357](https://github.com/JiRaska/open-bank-oss/commit/add33579f036aae4ca9b09534d845d2a8dfa3229))


### Bug Fixes

* **consent:** reconcile the consent request/response schemas with the DTOs they describe ([#6017](https://github.com/JiRaska/open-bank-oss/issues/6017)) ([89f7b73](https://github.com/JiRaska/open-bank-oss/commit/89f7b73b6a2d9c447002ff505bd7c8ecf66e567b))

## [0.23.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.22.0...consent-service-v0.23.0) (2026-09-01)


### Features

* **consent:** expose pending approvals in unified inbox ([#7037](https://github.com/JiRaska/open-bank-oss/issues/7037)) ([77573dc](https://github.com/JiRaska/open-bank-oss/commit/77573dc07495e784dc325a656c47f067217606ba))

## [0.22.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.21.0...consent-service-v0.22.0) (2026-08-27)


### Features

* **lending:** ADR-0269 platform — quotes, credit profile, AI levels, consent surface, financial health, funnel ([#6235](https://github.com/JiRaska/open-bank-oss/issues/6235)) ([3b62a4a](https://github.com/JiRaska/open-bank-oss/commit/3b62a4a5d42a80d0726c8018ca1af58599fb371b))

## [0.21.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.20.0...consent-service-v0.21.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.20.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.19.1...consent-service-v0.20.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.19.1](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.19.0...consent-service-v0.19.1) (2026-08-22)


### Bug Fixes

* **consent:** supersede the consent a new grant replaces ([#6505](https://github.com/JiRaska/open-bank-oss/issues/6505)) ([2a499da](https://github.com/JiRaska/open-bank-oss/commit/2a499da386b08eaded7f5c462dbb5be325c4f996)), closes [#6487](https://github.com/JiRaska/open-bank-oss/issues/6487)

## [0.19.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.18.2...consent-service-v0.19.0) (2026-08-21)


### Features

* **lending:** ADR-0269 slice 0 — credit-offer consent and the distress suppression floor ([#6226](https://github.com/JiRaska/open-bank-oss/issues/6226)) ([bf87d31](https://github.com/JiRaska/open-bank-oss/commit/bf87d314745d72eae965a256e6f68f34e8bf01b2))

## [0.18.2](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.18.1...consent-service-v0.18.2) (2026-08-19)


### Bug Fixes

* **consent:** map SuppressionEntity to the columns V6 actually created ([#5711](https://github.com/JiRaska/open-bank-oss/issues/5711)) ([87473a5](https://github.com/JiRaska/open-bank-oss/commit/87473a5f2189093d819a1bd8b4808f288d192e33))

## [0.18.1](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.18.0...consent-service-v0.18.1) (2026-08-18)


### Bug Fixes

* **consent:** add sourceService to consent domain events for audit attribution ([#5376](https://github.com/JiRaska/open-bank-oss/issues/5376)) ([300a223](https://github.com/JiRaska/open-bank-oss/commit/300a223b69be937a8a55a9991ba55b5f8c8b75bc))

## [0.18.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.17.3...consent-service-v0.18.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.17.3](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.17.2...consent-service-v0.17.3) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.17.2](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.17.1...consent-service-v0.17.2) (2026-08-16)


### Bug Fixes

* **consent:** record expiration sweep liveness ([#5030](https://github.com/JiRaska/open-bank-oss/issues/5030)) ([a9a141b](https://github.com/JiRaska/open-bank-oss/commit/a9a141bea2d6860dff016f2c09ffad0b4b97ecb9))

## [0.17.1](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.17.0...consent-service-v0.17.1) (2026-08-09)


### Bug Fixes

* **libs:** stamp ApiError.timestamp at construction instead of serving 1970 ([#3880](https://github.com/JiRaska/open-bank-oss/issues/3880)) ([b3e6672](https://github.com/JiRaska/open-bank-oss/commit/b3e6672c9e13470fc6353ad8a5483e4075875b1f))

## [0.17.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.16.6...consent-service-v0.17.0) (2026-08-07)


### Features

* **consent:** ADR-0219 D3 suppression store — the granular do-not-contact ([#3689](https://github.com/JiRaska/open-bank-oss/issues/3689)) ([621eda0](https://github.com/JiRaska/open-bank-oss/commit/621eda0a758c59fd459330133f1cbaa8ff84fbca))

## [0.16.6](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.16.5...consent-service-v0.16.6) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.16.5](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.16.4...consent-service-v0.16.5) (2026-08-02)


### Bug Fixes

* **consent:** the expiration sweep runs on a Vert.x context, and the gate now checks the property ([#2976](https://github.com/JiRaska/open-bank-oss/issues/2976)) ([5f3c535](https://github.com/JiRaska/open-bank-oss/commit/5f3c535d44d728598d547dcdb499a6a1e595c626)), closes [#2913](https://github.com/JiRaska/open-bank-oss/issues/2913)

## [0.16.4](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.16.3...consent-service-v0.16.4) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))

## [0.16.3](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.16.2...consent-service-v0.16.3) (2026-07-31)


### Bug Fixes

* **consent:** carry scopes on ConsentRevoked so a revocation can be acted on ([#2923](https://github.com/JiRaska/open-bank-oss/issues/2923)) ([0690396](https://github.com/JiRaska/open-bank-oss/commit/069039649bcd8340f3760f98d21d20a68feacfb1))

## [0.16.2](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.16.1...consent-service-v0.16.2) (2026-07-31)


### Bug Fixes

* **consent:** bind consent.revoke's authz resource to granteeId so the M2M rule can fire ([#2916](https://github.com/JiRaska/open-bank-oss/issues/2916)) ([de3779f](https://github.com/JiRaska/open-bank-oss/commit/de3779f62e29eb5f6b805bbac6a11a80ae725fd7)), closes [#2911](https://github.com/JiRaska/open-bank-oss/issues/2911) [#2749](https://github.com/JiRaska/open-bank-oss/issues/2749)

## [0.16.1](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.16.0...consent-service-v0.16.1) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.16.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.15.1...consent-service-v0.16.0) (2026-07-26)


### Features

* **consent:** answer "does this party consent to this scope" without disclosing the consent ([#2659](https://github.com/JiRaska/open-bank-oss/issues/2659)) ([bfa6deb](https://github.com/JiRaska/open-bank-oss/commit/bfa6debd354dbcf42049f547c81c4d2106118c05))

## [0.15.1](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.15.0...consent-service-v0.15.1) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.14.0...consent-service-v0.15.0) (2026-07-26)


### Features

* **consent:** scope M2M consent.grant/consent.revoke to marketing grantee (ADR-0206 D2) ([#2469](https://github.com/JiRaska/open-bank-oss/issues/2469)) ([90f97e2](https://github.com/JiRaska/open-bank-oss/commit/90f97e272bf0b8eec18cf23d75c553e7950ee41e))

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.13.0...consent-service-v0.14.0) (2026-07-25)


### Features

* **consent:** add GDPR_ONLY_SCOPES auto-activation, no SCA challenge (ADR-0205 D1) ([#2423](https://github.com/JiRaska/open-bank-oss/issues/2423)) ([7dfdb77](https://github.com/JiRaska/open-bank-oss/commit/7dfdb77a9453a2a424d28c5fdc437a66655e14af))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.12.0...consent-service-v0.13.0) (2026-07-25)


### Features

* **consent:** add per-channel MARKETING_COMMS ConsentScope values (ADR-0198 D1) ([#2408](https://github.com/JiRaska/open-bank-oss/issues/2408)) ([dff4ff6](https://github.com/JiRaska/open-bank-oss/commit/dff4ff60582425eadc767219f91d875677d20f27))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.11.2...consent-service-v0.12.0) (2026-07-18)


### Features

* **consent:** /validate returns scopes, grantedAccounts, frequencyPerDay (ADR-0126 D2) ([#1609](https://github.com/JiRaska/open-bank-oss/issues/1609)) ([a4ffd2b](https://github.com/JiRaska/open-bank-oss/commit/a4ffd2bff993b3d14e694eec3c15f11ed1a9a53b)), closes [#1521](https://github.com/JiRaska/open-bank-oss/issues/1521)

## [0.11.2](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.11.1...consent-service-v0.11.2) (2026-07-18)


### Bug Fixes

* **consent:** atomic outbox for lifecycle events; merge over persist ([#1553](https://github.com/JiRaska/open-bank-oss/issues/1553)) ([8347a34](https://github.com/JiRaska/open-bank-oss/commit/8347a343e6e5dd906865105745a619a2aa0297bb))

## [0.11.1](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.11.0...consent-service-v0.11.1) (2026-07-17)


### Bug Fixes

* **consent:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1518](https://github.com/JiRaska/open-bank-oss/issues/1518)) ([b81644a](https://github.com/JiRaska/open-bank-oss/commit/b81644a2a1955df2dd04d02b80089e897c7cfdb4))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.10.7...consent-service-v0.11.0) (2026-07-14)


### Features

* **governance:** four-eyes vocabulary for fx/consent/sanctions + sanctions OPA bootstrap ([#1006](https://github.com/JiRaska/open-bank-oss/issues/1006)) ([f5d589e](https://github.com/JiRaska/open-bank-oss/commit/f5d589e17d6bb8c1ed7abfa74f732d2b171fd3f9))

## [0.10.7](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.10.6...consent-service-v0.10.7) (2026-07-11)


### Security

* **consent,sca:** pair @Authorize with @RolesAllowed on every endpoint ([#780](https://github.com/JiRaska/open-bank-oss/issues/780)) ([dfb425c](https://github.com/JiRaska/open-bank-oss/commit/dfb425cbd06ed8a6f27879719a57cce726475b41)), closes [#467](https://github.com/JiRaska/open-bank-oss/issues/467)

## [0.10.6](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.10.5...consent-service-v0.10.6) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.10.5](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.10.4...consent-service-v0.10.5) (2026-07-07)


### Security

* **consent:** enforce OPA authorization on all consent endpoints (ADR-0126 D5) ([#383](https://github.com/JiRaska/open-bank-oss/issues/383)) ([dce975e](https://github.com/JiRaska/open-bank-oss/commit/dce975e1bab86e43b9c0c864c49cb24298836c86))

## [0.10.4](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.10.3...consent-service-v0.10.4) (2026-07-04)


### Bug Fixes

* three api-fuzz.yml boot failures (dispute, party, consent) ([#233](https://github.com/JiRaska/open-bank-oss/issues/233)) ([6534e12](https://github.com/JiRaska/open-bank-oss/commit/6534e12e5a90e62df1190d356f8058f8b476b84d))

## [0.10.3](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.10.2...consent-service-v0.10.3) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.10.2](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.10.1...consent-service-v0.10.2) (2026-06-30)


### Security

* **consent:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2a) ([#2740](https://github.com/JiRaska/open-bank-oss/issues/2740)) ([0d0d1f4](https://github.com/JiRaska/open-bank-oss/commit/0d0d1f48f4285783bf4497ac8d288c3bb4447526))

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.10.0...consent-service-v0.10.1) (2026-06-29)


### Bug Fixes

* **libs,account,consent,ledger,pid,transaction:** make DomainEvent.occurredAt explicit ([#2137](https://github.com/JiRaska/open-bank-oss/issues/2137)) ([#2662](https://github.com/JiRaska/open-bank-oss/issues/2662)) ([9e0c2ea](https://github.com/JiRaska/open-bank-oss/commit/9e0c2ea14a65aec227df333b83b0b7283b6c16a5))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.9.0...consent-service-v0.10.0) (2026-06-29)


### Features

* **consent:** add boot smoke test and @Operation OpenAPI summaries ([#2628](https://github.com/JiRaska/open-bank-oss/issues/2628)) ([152e9c6](https://github.com/JiRaska/open-bank-oss/commit/152e9c6c917b78430c89dbab0638bdc2732b6370))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.8.1...consent-service-v0.9.0) (2026-06-29)


### Features

* **consent:** hourly expiration sweep + ADR-0126 unified consent lifecycle ([#2522](https://github.com/JiRaska/open-bank-oss/issues/2522)) ([e911ab0](https://github.com/JiRaska/open-bank-oss/commit/e911ab0d3e472ca6f55bdf9efb9c46775ae136db))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **consent:** expand wildcard imports and add trailing commas (ktlint) ([#2536](https://github.com/JiRaska/open-bank-oss/issues/2536)) ([528b1af](https://github.com/JiRaska/open-bank-oss/commit/528b1afbfd7b88ff708b1342924b7ad25fe5cd79))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.8.0...consent-service-v0.8.1) (2026-06-29)


### Bug Fixes

* **consent:** expand wildcard imports and add trailing commas (ktlint) ([#2536](https://github.com/JiRaska/open-bank-oss/issues/2536)) ([cc35531](https://github.com/JiRaska/open-bank-oss/commit/cc35531cecd262a835d4d0f58226b1d7bf356d6b))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.7.1...consent-service-v0.8.0) (2026-06-29)


### Features

* **consent:** hourly expiration sweep + ADR-0126 unified consent lifecycle ([#2522](https://github.com/JiRaska/open-bank-oss/issues/2522)) ([df1c514](https://github.com/JiRaska/open-bank-oss/commit/df1c5145c403a8f9a1e11641642d8e10b28c1ea8))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.7.0...consent-service-v0.7.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.6.0...consent-service-v0.7.0) (2026-06-25)


### Features

* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank-oss/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank-oss/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.5.1...consent-service-v0.6.0) (2026-06-25)


### Features

* **consent:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2078](https://github.com/JiRaska/open-bank-oss/issues/2078)) ([6b96506](https://github.com/JiRaska/open-bank-oss/commit/6b9650610b1115bdbda3e40fac8fcd9e4fa21175)), closes [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.5.0...consent-service-v0.5.1) (2026-06-25)


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank-oss/commit/4ea273195d038704acc6341f684c0f1cb039ce82))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.4.0...consent-service-v0.5.0) (2026-06-15)


### Features

* **consent:** add TELEMETRY_RUM consent scope (ADR-0088 D4b, RUM gateway O4) ([#1052](https://github.com/JiRaska/open-bank-oss/issues/1052)) ([1560978](https://github.com/JiRaska/open-bank-oss/commit/1560978437764dca1ff2b43c1be3bd4c4f733b96))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.3.0...consent-service-v0.4.0) (2026-06-12)


### Features

* **consent:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#797](https://github.com/JiRaska/open-bank-oss/issues/797)) ([7be2671](https://github.com/JiRaska/open-bank-oss/commit/7be26713a35f5d74bd2468ccb9a4c3a82e44329a))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.2.0...consent-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/consent-service-v0.1.1...consent-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))

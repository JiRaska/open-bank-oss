# Changelog

## [0.15.1](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.15.0...sca-service-v0.15.1) (2026-09-07)


### Bug Fixes

* **sca:** commit the enrolled device and its outbox row in one transaction ([#8683](https://github.com/JiRaska/open-bank-oss/issues/8683)) ([1a9445f](https://github.com/JiRaska/open-bank-oss/commit/1a9445ff1ba21d185e23ddaad759f6d47376f8fe)), closes [#8679](https://github.com/JiRaska/open-bank-oss/issues/8679)
* **sca:** refuse TOTP instead of minting a challenge nobody can satisfy ([#8567](https://github.com/JiRaska/open-bank-oss/issues/8567)) ([3095ae6](https://github.com/JiRaska/open-bank-oss/commit/3095ae652ec3ee2acf671aad05e6b2c30772aa9c))

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.14.1...sca-service-v0.15.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.14.1](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.14.0...sca-service-v0.14.1) (2026-08-21)


### Bug Fixes

* **transaction:** publish the full TransactionType and TransactionStatus vocabularies ([#5982](https://github.com/JiRaska/open-bank-oss/issues/5982)) ([11baea4](https://github.com/JiRaska/open-bank-oss/commit/11baea4482c50d838f8c913d4ae466ccc198a53c))

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.13.5...sca-service-v0.14.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)
* **sca:** add sourceService to DEVICE_ENROLLED for audit attribution ([#5337](https://github.com/JiRaska/open-bank-oss/issues/5337)) ([247639d](https://github.com/JiRaska/open-bank-oss/commit/247639db84d3e238948df1ebc322d11c2ad07881)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)

## [0.13.5](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.13.4...sca-service-v0.13.5) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.13.4](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.13.3...sca-service-v0.13.4) (2026-08-14)


### Bug Fixes

* **sca:** DEVICE_ENROLLED carries no eventType in the payload body, so onboarding has never projected one ([#4692](https://github.com/JiRaska/open-bank-oss/issues/4692)) ([e36e5a5](https://github.com/JiRaska/open-bank-oss/commit/e36e5a505ad1ab3352ab4b40ed53e5ceb5c22725))

## [0.13.3](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.13.2...sca-service-v0.13.3) (2026-08-09)


### Bug Fixes

* **libs:** stamp ApiError.timestamp at construction instead of serving 1970 ([#3880](https://github.com/JiRaska/open-bank-oss/issues/3880)) ([b3e6672](https://github.com/JiRaska/open-bank-oss/commit/b3e6672c9e13470fc6353ad8a5483e4075875b1f))

## [0.13.2](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.13.1...sca-service-v0.13.2) (2026-08-07)


### Bug Fixes

* **account:** the propose-only savings withdrawal approval could never succeed ([#3632](https://github.com/JiRaska/open-bank-oss/issues/3632)) ([4209407](https://github.com/JiRaska/open-bank-oss/commit/4209407e79ffd7a11f048ff5d7daa9fdc6fdfa30))

## [0.13.1](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.13.0...sca-service-v0.13.1) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.12.4...sca-service-v0.13.0) (2026-08-02)


### Features

* **delegation:** delegation-service — customer-to-party access grants (ADR-0232) ([#2971](https://github.com/JiRaska/open-bank-oss/issues/2971)) ([5ce707b](https://github.com/JiRaska/open-bank-oss/commit/5ce707b1c97babddda6b1b7a7df3050d988e2bdf))

## [0.12.4](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.12.3...sca-service-v0.12.4) (2026-08-02)


### Bug Fixes

* **infra:** give the five money-path services the JDBC datasource Flyway migrates ([#3192](https://github.com/JiRaska/open-bank-oss/issues/3192)) ([d9b31d5](https://github.com/JiRaska/open-bank-oss/commit/d9b31d5d2cccd169ec6ce7e8e971d5853ef952f1)), closes [#3080](https://github.com/JiRaska/open-bank-oss/issues/3080)

## [0.12.3](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.12.2...sca-service-v0.12.3) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.12.2](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.12.1...sca-service-v0.12.2) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.12.1](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.12.0...sca-service-v0.12.1) (2026-07-25)


### Security

* **sca:** remove SMS_OTP — its only implementation logs the OTP instead of delivering it ([#2379](https://github.com/JiRaska/open-bank-oss/issues/2379)) ([01a9900](https://github.com/JiRaska/open-bank-oss/commit/01a990044bda316be8039aa415a51112518b1083))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.11.0...sca-service-v0.12.0) (2026-07-25)


### Features

* **cards:** card lifecycle, synthetic PAN vault and SCA-gated card operations (ADR-0194) ([#2135](https://github.com/JiRaska/open-bank-oss/issues/2135)) ([991cd92](https://github.com/JiRaska/open-bank-oss/commit/991cd928a9ea8a267aeb5aa82c33ae5a32aa3887))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.10.0...sca-service-v0.11.0) (2026-07-24)


### Features

* **sca:** send a real approval push on challenge initiate ([#2026](https://github.com/JiRaska/open-bank-oss/issues/2026)) ([f53c527](https://github.com/JiRaska/open-bank-oss/commit/f53c52733722b684e838d317fc29981185dc0f20)), closes [#2025](https://github.com/JiRaska/open-bank-oss/issues/2025)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.9.1...sca-service-v0.10.0) (2026-07-23)


### Features

* **sca:** pending-approvals list for decoupled/push SCA ([#1969](https://github.com/JiRaska/open-bank-oss/issues/1969)) ([5684d76](https://github.com/JiRaska/open-bank-oss/commit/5684d7613bfb1ba6e8991f9b0a8b9b17ca24aa7d)), closes [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.9.0...sca-service-v0.9.1) (2026-07-17)


### Bug Fixes

* **sca:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1554](https://github.com/JiRaska/open-bank-oss/issues/1554)) ([f9e33ea](https://github.com/JiRaska/open-bank-oss/commit/f9e33ea7b81c5d931750695b6d97014b0c91cdb0))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.8.10...sca-service-v0.9.0) (2026-07-16)


### Features

* **sca:** bind SCA dynamic linking to document signing (ADR-0169 D2) ([#1140](https://github.com/JiRaska/open-bank-oss/issues/1140)) ([9b29570](https://github.com/JiRaska/open-bank-oss/commit/9b29570e5bd3d8f950b15e04cc7fcd4f0d0eef6b))

## [0.8.10](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.8.9...sca-service-v0.8.10) (2026-07-14)


### Bug Fixes

* **pact:** bridge reactive-Panache @State handlers onto a Vert.x context ([#1097](https://github.com/JiRaska/open-bank-oss/issues/1097)) ([b9b496a](https://github.com/JiRaska/open-bank-oss/commit/b9b496a6cfdfb529fed96c6ef8f6944215c81c5c))
* **sca:** publish DEVICE_ENROLLED to openbank.sca.events, not sca.challenge.event ([#1005](https://github.com/JiRaska/open-bank-oss/issues/1005)) ([41d83b4](https://github.com/JiRaska/open-bank-oss/commit/41d83b45745f6888046689f1ba02540d994a21ff)), closes [#996](https://github.com/JiRaska/open-bank-oss/issues/996)

## [0.8.9](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.8.8...sca-service-v0.8.9) (2026-07-11)


### Security

* **consent,sca:** pair @Authorize with @RolesAllowed on every endpoint ([#780](https://github.com/JiRaska/open-bank-oss/issues/780)) ([dfb425c](https://github.com/JiRaska/open-bank-oss/commit/dfb425cbd06ed8a6f27879719a57cce726475b41)), closes [#467](https://github.com/JiRaska/open-bank-oss/issues/467)

## [0.8.8](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.8.7...sca-service-v0.8.8) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.8.7](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.8.6...sca-service-v0.8.7) (2026-07-07)


### Security

* **sca:** enforce OPA authorization on all SCA endpoints (ADR-0034 Phase 5) ([#387](https://github.com/JiRaska/open-bank-oss/issues/387)) ([b446ad0](https://github.com/JiRaska/open-bank-oss/commit/b446ad0a9c8b1739ed422928bb2d3bbf2ab46c09))

## [0.8.6](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.8.5...sca-service-v0.8.6) (2026-07-03)


### Bug Fixes

* **libs-domain:** carry interbank settlement date through to pacs.008 (IntrBkSttlmDt) ([#195](https://github.com/JiRaska/open-bank-oss/issues/195)) ([62eef5e](https://github.com/JiRaska/open-bank-oss/commit/62eef5ef21626a56099ccfe9ebc6f6e5387a85b6))

## [0.8.5](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.8.4...sca-service-v0.8.5) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))
* **sca:** sanitize notification message before logging (CodeQL java/log-injection) ([#150](https://github.com/JiRaska/open-bank-oss/issues/150)) ([fc2f026](https://github.com/JiRaska/open-bank-oss/commit/fc2f02622497fc15403dacab542765ec19629290))

## [0.8.4](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.8.3...sca-service-v0.8.4) (2026-06-30)


### Security

* **sca:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2a) ([#2744](https://github.com/JiRaska/open-bank-oss/issues/2744)) ([b0e4de1](https://github.com/JiRaska/open-bank-oss/commit/b0e4de14e77874f8e87ce97bcebded152b85a52e))

## [0.8.3](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.8.2...sca-service-v0.8.3) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)
* **sca:** mint fresh challenge when idempotent one is stale/spent ([#2512](https://github.com/JiRaska/open-bank-oss/issues/2512)) ([1eecb1a](https://github.com/JiRaska/open-bank-oss/commit/1eecb1a594bab1295c22b1e142068737c6a88ee4))

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.8.1...sca-service-v0.8.2) (2026-06-29)


### Bug Fixes

* **sca:** mint fresh challenge when idempotent one is stale/spent ([#2512](https://github.com/JiRaska/open-bank-oss/issues/2512)) ([3838f2f](https://github.com/JiRaska/open-bank-oss/commit/3838f2f3a6fb259709735a70f25ab33ab6b474f5))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.8.0...sca-service-v0.8.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **sca:** make V5 migration idempotent (ADD COLUMN IF NOT EXISTS) ([#2198](https://github.com/JiRaska/open-bank-oss/issues/2198)) ([adfa571](https://github.com/JiRaska/open-bank-oss/commit/adfa571894790bcd437811de3f2d57f992165aa6))
* **sca:** use merge() instead of persist() in ScaChallengeRepository.save() ([#2215](https://github.com/JiRaska/open-bank-oss/issues/2215)) ([2fda5bc](https://github.com/JiRaska/open-bank-oss/commit/2fda5bc490d812739e6f3a528385dd29440c940c))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.7.0...sca-service-v0.8.0) (2026-06-25)


### Features

* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank-oss/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank-oss/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))


### Bug Fixes

* **sepa-instant,balance,audit,security-scanner,copilot,customer-edge,sca:** inject Clock via CDI (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2145](https://github.com/JiRaska/open-bank-oss/issues/2145)) ([d680007](https://github.com/JiRaska/open-bank-oss/commit/d68000775625cc423c95d8a27db29ff25a708f9f))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.6.1...sca-service-v0.7.0) (2026-06-25)


### Features

* **sca:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2096](https://github.com/JiRaska/open-bank-oss/issues/2096)) ([b0abc2a](https://github.com/JiRaska/open-bank-oss/commit/b0abc2a2e002bbb649f0c93e7e5592d4ebdd5d14)), closes [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.6.0...sca-service-v0.6.1) (2026-06-25)


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank-oss/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank-oss/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank-oss/commit/92dc2d636d857b526c8276e2647de440c540577b))
* **sca:** handle TOCTOU unique-constraint race in device enrollment ([#2023](https://github.com/JiRaska/open-bank-oss/issues/2023)) ([4748e9f](https://github.com/JiRaska/open-bank-oss/commit/4748e9f645a1e0a42a521afba5d23e9d546ed12c))
* **sca:** make device credential enrollment idempotent ([#1896](https://github.com/JiRaska/open-bank-oss/issues/1896)) ([bffbca4](https://github.com/JiRaska/open-bank-oss/commit/bffbca42446af108e680576d0ef15a6849c1bb11))
* **sca:** return 200 on same-party re-enroll, 409 on cross-party credential reuse (Closes [#1895](https://github.com/JiRaska/open-bank-oss/issues/1895)) ([#1919](https://github.com/JiRaska/open-bank-oss/issues/1919)) ([0f34ceb](https://github.com/JiRaska/open-bank-oss/commit/0f34ceb44c873f689f05bdbcceeb31f7e5c05a8a))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.5.0...sca-service-v0.6.0) (2026-06-15)


### Features

* **security:** customer payment non-repudiation — SCA settlement gate, identity threading, audit hash chain (ADR-0086) ([#900](https://github.com/JiRaska/open-bank-oss/issues/900)) ([fcc1e52](https://github.com/JiRaska/open-bank-oss/commit/fcc1e52b247b0eb61b9ee8d5332f110984a6fb33))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.4.0...sca-service-v0.5.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **sca:** SCA challenge metrics + outbox backlog gauge (ADR-0077/0079) ([#794](https://github.com/JiRaska/open-bank-oss/issues/794)) ([ede8fb4](https://github.com/JiRaska/open-bank-oss/commit/ede8fb4e3b24bcbd8dee0b7b13429b82b1b5156d))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/sca-service-v0.3.0...sca-service-v0.4.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))
* **sca:** decoupled device approval for push/biometric SCA (ADR-0021) ([#401](https://github.com/JiRaska/open-bank-oss/issues/401)) ([209b2d2](https://github.com/JiRaska/open-bank-oss/commit/209b2d2afc8cb36278616c6fd929fe515da110e4))
* **sca:** emit DEVICE_ENROLLED outbox event + list-devices endpoint ([ba84e77](https://github.com/JiRaska/open-bank-oss/commit/ba84e77f95286c4c3accc5811a544af4d9bef22f))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))

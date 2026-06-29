# Changelog

## [0.8.3](https://github.com/JiRaska/open-bank/compare/sca-service-v0.8.2...sca-service-v0.8.3) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank/issues/2342)
* **sca:** mint fresh challenge when idempotent one is stale/spent ([#2512](https://github.com/JiRaska/open-bank/issues/2512)) ([1eecb1a](https://github.com/JiRaska/open-bank/commit/1eecb1a594bab1295c22b1e142068737c6a88ee4))

## [0.8.2](https://github.com/JiRaska/open-bank/compare/sca-service-v0.8.1...sca-service-v0.8.2) (2026-06-29)


### Bug Fixes

* **sca:** mint fresh challenge when idempotent one is stale/spent ([#2512](https://github.com/JiRaska/open-bank/issues/2512)) ([3838f2f](https://github.com/JiRaska/open-bank/commit/3838f2f3a6fb259709735a70f25ab33ab6b474f5))

## [0.8.1](https://github.com/JiRaska/open-bank/compare/sca-service-v0.8.0...sca-service-v0.8.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **sca:** make V5 migration idempotent (ADD COLUMN IF NOT EXISTS) ([#2198](https://github.com/JiRaska/open-bank/issues/2198)) ([adfa571](https://github.com/JiRaska/open-bank/commit/adfa571894790bcd437811de3f2d57f992165aa6))
* **sca:** use merge() instead of persist() in ScaChallengeRepository.save() ([#2215](https://github.com/JiRaska/open-bank/issues/2215)) ([2fda5bc](https://github.com/JiRaska/open-bank/commit/2fda5bc490d812739e6f3a528385dd29440c940c))

## [0.8.0](https://github.com/JiRaska/open-bank/compare/sca-service-v0.7.0...sca-service-v0.8.0) (2026-06-25)


### Features

* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))


### Bug Fixes

* **sepa-instant,balance,audit,security-scanner,copilot,customer-edge,sca:** inject Clock via CDI (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2145](https://github.com/JiRaska/open-bank/issues/2145)) ([d680007](https://github.com/JiRaska/open-bank/commit/d68000775625cc423c95d8a27db29ff25a708f9f))

## [0.7.0](https://github.com/JiRaska/open-bank/compare/sca-service-v0.6.1...sca-service-v0.7.0) (2026-06-25)


### Features

* **sca:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2096](https://github.com/JiRaska/open-bank/issues/2096)) ([b0abc2a](https://github.com/JiRaska/open-bank/commit/b0abc2a2e002bbb649f0c93e7e5592d4ebdd5d14)), closes [#1612](https://github.com/JiRaska/open-bank/issues/1612)

## [0.6.1](https://github.com/JiRaska/open-bank/compare/sca-service-v0.6.0...sca-service-v0.6.1) (2026-06-25)


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank/commit/92dc2d636d857b526c8276e2647de440c540577b))
* **sca:** handle TOCTOU unique-constraint race in device enrollment ([#2023](https://github.com/JiRaska/open-bank/issues/2023)) ([4748e9f](https://github.com/JiRaska/open-bank/commit/4748e9f645a1e0a42a521afba5d23e9d546ed12c))
* **sca:** make device credential enrollment idempotent ([#1896](https://github.com/JiRaska/open-bank/issues/1896)) ([bffbca4](https://github.com/JiRaska/open-bank/commit/bffbca42446af108e680576d0ef15a6849c1bb11))
* **sca:** return 200 on same-party re-enroll, 409 on cross-party credential reuse (Closes [#1895](https://github.com/JiRaska/open-bank/issues/1895)) ([#1919](https://github.com/JiRaska/open-bank/issues/1919)) ([0f34ceb](https://github.com/JiRaska/open-bank/commit/0f34ceb44c873f689f05bdbcceeb31f7e5c05a8a))

## [0.6.0](https://github.com/JiRaska/open-bank/compare/sca-service-v0.5.0...sca-service-v0.6.0) (2026-06-15)


### Features

* **security:** customer payment non-repudiation — SCA settlement gate, identity threading, audit hash chain (ADR-0086) ([#900](https://github.com/JiRaska/open-bank/issues/900)) ([fcc1e52](https://github.com/JiRaska/open-bank/commit/fcc1e52b247b0eb61b9ee8d5332f110984a6fb33))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/sca-service-v0.4.0...sca-service-v0.5.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **sca:** SCA challenge metrics + outbox backlog gauge (ADR-0077/0079) ([#794](https://github.com/JiRaska/open-bank/issues/794)) ([ede8fb4](https://github.com/JiRaska/open-bank/commit/ede8fb4e3b24bcbd8dee0b7b13429b82b1b5156d))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/sca-service-v0.3.0...sca-service-v0.4.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank/commit/6e5f132ab1f0c3723104276d373307f76076d483))
* **sca:** decoupled device approval for push/biometric SCA (ADR-0021) ([#401](https://github.com/JiRaska/open-bank/issues/401)) ([209b2d2](https://github.com/JiRaska/open-bank/commit/209b2d2afc8cb36278616c6fd929fe515da110e4))
* **sca:** emit DEVICE_ENROLLED outbox event + list-devices endpoint ([ba84e77](https://github.com/JiRaska/open-bank/commit/ba84e77f95286c4c3accc5811a544af4d9bef22f))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))

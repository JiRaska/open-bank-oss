# Changelog

## [0.4.2](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.4.1...billing-service-v0.4.2) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.4.0...billing-service-v0.4.1) (2026-07-07)


### Security

* **billing:** enforce OPA authorization on billing endpoints (ADR-0034 Phase 5) ([#391](https://github.com/JiRaska/open-bank-oss/issues/391)) ([525be76](https://github.com/JiRaska/open-bank-oss/commit/525be76cd8fbbe136e2f6f72e676fd420988cc43)), closes [#266](https://github.com/JiRaska/open-bank-oss/issues/266)

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.3.0...billing-service-v0.4.0) (2026-07-03)


### Features

* **billing:** wire OPA authorization onto the fee-assessment endpoint ([#179](https://github.com/JiRaska/open-bank-oss/issues/179)) ([6879d9a](https://github.com/JiRaska/open-bank-oss/commit/6879d9a26e33d9aa4d2158f302c4db02828e431a))


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.2.0...billing-service-v0.3.0) (2026-06-30)


### Features

* **billing:** Dockerfile + GitOps manifests — sandbox deploy (ADR-0143 phase 2c) ([#2813](https://github.com/JiRaska/open-bank-oss/issues/2813)) ([8c00ef9](https://github.com/JiRaska/open-bank-oss/commit/8c00ef9c4e806ead755bc59c42080a9c870541a4))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.1.0...billing-service-v0.2.0) (2026-06-30)


### Features

* **billing-service:** ADR-0143 phase 2b — fee assessment skeleton (money-path) ([#2756](https://github.com/JiRaska/open-bank-oss/issues/2756)) ([1250001](https://github.com/JiRaska/open-bank-oss/commit/1250001d86628b7600d23416084eb41062cde813))
* **billing-service:** ADR-0143 phase 2c (read path) — live fee assessment, dry-run ([#2770](https://github.com/JiRaska/open-bank-oss/issues/2770)) ([958b302](https://github.com/JiRaska/open-bank-oss/commit/958b302f28ad9779136af7bb25c6e8d484081561))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

# Changelog

## [0.6.4](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.6.3...finrep-service-v0.6.4) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.6.3](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.6.2...finrep-service-v0.6.3) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.6.1...finrep-service-v0.6.2) (2026-07-25)


### Bug Fixes

* **finrep:** realm-issued role names + declare AUTHZ_ENFORCE on finrep/onboarding ([#2394](https://github.com/JiRaska/open-bank-oss/issues/2394)) ([#2403](https://github.com/JiRaska/open-bank-oss/issues/2403)) ([f5c3601](https://github.com/JiRaska/open-bank-oss/commit/f5c3601d0c725d0c548553e4173b58ca755bac3a))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.6.0...finrep-service-v0.6.1) (2026-07-25)


### Bug Fixes

* **finrep:** call the ledger trial-balance path that exists, pinned by a pact ([#2290](https://github.com/JiRaska/open-bank-oss/issues/2290)) ([29bdb9d](https://github.com/JiRaska/open-bank-oss/commit/29bdb9d0e318d1851003a093c878a64386c8bf70))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.5.0...finrep-service-v0.6.0) (2026-07-25)


### Features

* **finrep:** instrument template renders, input size, balance and data gaps ([#2279](https://github.com/JiRaska/open-bank-oss/issues/2279)) ([f10a138](https://github.com/JiRaska/open-bank-oss/commit/f10a138f017bb075990f7eb93a414da5682bad7d)), closes [#2255](https://github.com/JiRaska/open-bank-oss/issues/2255)

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.4.0...finrep-service-v0.5.0) (2026-07-08)


### Features

* **finrep:** add COREP C 01.00 own funds first increment ([#606](https://github.com/JiRaska/open-bank-oss/issues/606)) ([03feb29](https://github.com/JiRaska/open-bank-oss/commit/03feb29478c5f8d1ab0906b7649d235c9fb17f85)), closes [#605](https://github.com/JiRaska/open-bank-oss/issues/605)

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.3.3...finrep-service-v0.4.0) (2026-07-08)


### Features

* **finrep:** register ArgoCD Application so finrep-service actually deploys ([#547](https://github.com/JiRaska/open-bank-oss/issues/547)) ([b10d63c](https://github.com/JiRaska/open-bank-oss/commit/b10d63cfe1e1a8ba5117248622fdb8aedae12ec2)), closes [#530](https://github.com/JiRaska/open-bank-oss/issues/530)

## [0.3.3](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.3.2...finrep-service-v0.3.3) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.3.2](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.3.1...finrep-service-v0.3.2) (2026-07-06)


### Bug Fixes

* set real koverVerify floors for sanctions/finrep/psd2-service ([#288](https://github.com/JiRaska/open-bank-oss/issues/288)) ([b49c139](https://github.com/JiRaska/open-bank-oss/commit/b49c13968c67b34352512b6d10690bab772b2d67))

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.3.0...finrep-service-v0.3.1) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.2.0...finrep-service-v0.3.0) (2026-06-27)


### Features

* **admin-ui:** FinOps AI costs card + IAOps anomaly timeline (ADR-0112) ([#2183](https://github.com/JiRaska/open-bank-oss/issues/2183)) ([225cc57](https://github.com/JiRaska/open-bank-oss/commit/225cc574b6eabc543f00aedc7871b0981ac0dbfa))


### Bug Fixes

* **sepa-payment,analytics,clearing-simulator,finrep,fx,customer-edge,security-scanner:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2174](https://github.com/JiRaska/open-bank-oss/issues/2174)) ([51a872e](https://github.com/JiRaska/open-bank-oss/commit/51a872ec0ce0b9f888226ca94ffcfb9f392174c2))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.1.0...finrep-service-v0.2.0) (2026-06-25)


### Features

* **finrep:** openbank-finrep-service Phase 1 — FINREP F01.01+F02.00 (ADR-0097) ([#1954](https://github.com/JiRaska/open-bank-oss/issues/1954)) ([81300db](https://github.com/JiRaska/open-bank-oss/commit/81300dbd0e43f8ef750ee1b749f54320c7c99ce6))


### Bug Fixes

* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank-oss/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank-oss/commit/92dc2d636d857b526c8276e2647de440c540577b))

# Changelog

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

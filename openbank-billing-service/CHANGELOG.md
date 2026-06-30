# Changelog

## [0.3.0](https://github.com/JiRaska/open-bank/compare/billing-service-v0.2.0...billing-service-v0.3.0) (2026-06-30)


### Features

* **billing:** Dockerfile + GitOps manifests — sandbox deploy (ADR-0143 phase 2c) ([#2813](https://github.com/JiRaska/open-bank/issues/2813)) ([8c00ef9](https://github.com/JiRaska/open-bank/commit/8c00ef9c4e806ead755bc59c42080a9c870541a4))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/billing-service-v0.1.0...billing-service-v0.2.0) (2026-06-30)


### Features

* **billing-service:** ADR-0143 phase 2b — fee assessment skeleton (money-path) ([#2756](https://github.com/JiRaska/open-bank/issues/2756)) ([1250001](https://github.com/JiRaska/open-bank/commit/1250001d86628b7600d23416084eb41062cde813))
* **billing-service:** ADR-0143 phase 2c (read path) — live fee assessment, dry-run ([#2770](https://github.com/JiRaska/open-bank/issues/2770)) ([958b302](https://github.com/JiRaska/open-bank/commit/958b302f28ad9779136af7bb25c6e8d484081561))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank/issues/2342)

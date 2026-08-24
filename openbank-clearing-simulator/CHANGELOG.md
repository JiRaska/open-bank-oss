# Changelog

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/clearing-simulator-v0.5.0...clearing-simulator-v0.6.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/clearing-simulator-v0.4.7...clearing-simulator-v0.5.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))

## [0.4.7](https://github.com/JiRaska/open-bank-oss/compare/clearing-simulator-v0.4.6...clearing-simulator-v0.4.7) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.4.6](https://github.com/JiRaska/open-bank-oss/compare/clearing-simulator-v0.4.5...clearing-simulator-v0.4.6) (2026-08-02)


### Bug Fixes

* **ci:** forward the Pact Broker properties into the test JVM for the last three providers ([#3301](https://github.com/JiRaska/open-bank-oss/issues/3301)) ([cf00673](https://github.com/JiRaska/open-bank-oss/commit/cf0067340539a88f16d9455095735aa6211839d6))

## [0.4.5](https://github.com/JiRaska/open-bank-oss/compare/clearing-simulator-v0.4.4...clearing-simulator-v0.4.5) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.4.4](https://github.com/JiRaska/open-bank-oss/compare/clearing-simulator-v0.4.3...clearing-simulator-v0.4.4) (2026-07-12)


### Bug Fixes

* **swift:** fail-closed crash bug + add clearing-simulator pact coverage ([#468](https://github.com/JiRaska/open-bank-oss/issues/468)) ([#871](https://github.com/JiRaska/open-bank-oss/issues/871)) ([175c693](https://github.com/JiRaska/open-bank-oss/commit/175c69307a53da7eeac2639ee6ccff7fa5071a4e))

## [0.4.3](https://github.com/JiRaska/open-bank-oss/compare/clearing-simulator-v0.4.2...clearing-simulator-v0.4.3) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.4.2](https://github.com/JiRaska/open-bank-oss/compare/clearing-simulator-v0.4.1...clearing-simulator-v0.4.2) (2026-07-03)


### Bug Fixes

* **libs-domain:** carry interbank settlement date through to pacs.008 (IntrBkSttlmDt) ([#195](https://github.com/JiRaska/open-bank-oss/issues/195)) ([62eef5e](https://github.com/JiRaska/open-bank-oss/commit/62eef5ef21626a56099ccfe9ebc6f6e5387a85b6))

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/clearing-simulator-v0.4.0...clearing-simulator-v0.4.1) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/clearing-simulator-v0.3.0...clearing-simulator-v0.4.0) (2026-06-29)


### Features

* **sanctions:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2125](https://github.com/JiRaska/open-bank-oss/issues/2125)) ([9613742](https://github.com/JiRaska/open-bank-oss/commit/96137420563a0cfb732b1874eab07de65b7bc7cc))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **clearing-simulator,product-catalog:** resolve detekt MagicNumber and CyclomaticComplexMethod violations ([#2230](https://github.com/JiRaska/open-bank-oss/issues/2230)) ([68e6aea](https://github.com/JiRaska/open-bank-oss/commit/68e6aea3d32b6f1ade0c4304a9395031e3e9e7e4))
* **clearing-simulator:** switch ClearingSimulatorService to field Clock injection to fix CDI proxy NPE in ClearingSimulatorApiIT ([#2263](https://github.com/JiRaska/open-bank-oss/issues/2263)) ([556805d](https://github.com/JiRaska/open-bank-oss/commit/556805d1b3a04b339a4d51627b268a37dd20242a))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/clearing-simulator-v0.2.0...clearing-simulator-v0.3.0) (2026-06-27)


### Features

* **sanctions:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2125](https://github.com/JiRaska/open-bank-oss/issues/2125)) ([61da7c2](https://github.com/JiRaska/open-bank-oss/commit/61da7c24c384a4ec70674f95ba95f401a4817c18))


### Bug Fixes

* **clearing-simulator,product-catalog:** resolve detekt MagicNumber and CyclomaticComplexMethod violations ([#2230](https://github.com/JiRaska/open-bank-oss/issues/2230)) ([0359472](https://github.com/JiRaska/open-bank-oss/commit/03594725eac2844caaaa33eebffaa620c86d5512))
* **clearing-simulator:** switch ClearingSimulatorService to field Clock injection to fix CDI proxy NPE in ClearingSimulatorApiIT ([#2263](https://github.com/JiRaska/open-bank-oss/issues/2263)) ([abf7bc4](https://github.com/JiRaska/open-bank-oss/commit/abf7bc4e945e6eeba1cca4e8e5f9a7ee2bffbbd2))
* **sepa-payment,analytics,clearing-simulator,finrep,fx,customer-edge,security-scanner:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2174](https://github.com/JiRaska/open-bank-oss/issues/2174)) ([51a872e](https://github.com/JiRaska/open-bank-oss/commit/51a872ec0ce0b9f888226ca94ffcfb9f392174c2))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/clearing-simulator-v0.1.0...clearing-simulator-v0.2.0) (2026-06-25)


### Features

* **clearing-simulator:** add POST /clearing/returns — simulate pacs.004 R-transaction return (ADR-0109) ([#1933](https://github.com/JiRaska/open-bank-oss/issues/1933)) ([a9dd320](https://github.com/JiRaska/open-bank-oss/commit/a9dd32052eafaee72351a181958624faabbe191d))
* **clearing-simulator:** scheme/clearing simulator service (ADR-0104 D2) ([#1688](https://github.com/JiRaska/open-bank-oss/issues/1688)) ([5001800](https://github.com/JiRaska/open-bank-oss/commit/500180090c7aa99179c2077dfb5d2cd6c63099ae))


### Bug Fixes

* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank-oss/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank-oss/commit/92dc2d636d857b526c8276e2647de440c540577b))

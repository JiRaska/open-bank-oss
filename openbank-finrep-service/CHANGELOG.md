# Changelog

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.9.4...finrep-service-v0.10.0) (2026-08-26)


### Features

* **finrep:** add XBRL CSV preflight ([#7125](https://github.com/JiRaska/open-bank-oss/issues/7125)) ([5fa1c63](https://github.com/JiRaska/open-bank-oss/commit/5fa1c6351310a9d6094535522887d5cc41f9c3d6))

## [0.9.4](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.9.3...finrep-service-v0.9.4) (2026-08-26)


### Bug Fixes

* **finrep:** restore regulatory previews at runtime ([#7043](https://github.com/JiRaska/open-bank-oss/issues/7043)) ([d688dc4](https://github.com/JiRaska/open-bank-oss/commit/d688dc4c3596bf41cf17abf7b161c4f9b6904882))

## [0.9.3](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.9.2...finrep-service-v0.9.3) (2026-08-26)


### Bug Fixes

* **finrep:** expose official coverage gaps ([#7070](https://github.com/JiRaska/open-bank-oss/issues/7070)) ([4676ba0](https://github.com/JiRaska/open-bank-oss/commit/4676ba03068ecb6ac7004b9958f6802c6460cf94))

## [0.9.2](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.9.1...finrep-service-v0.9.2) (2026-08-26)


### Bug Fixes

* **finrep:** align previews with EBA 4.2 ([#7046](https://github.com/JiRaska/open-bank-oss/issues/7046)) ([72c7ff8](https://github.com/JiRaska/open-bank-oss/commit/72c7ff8a3429abe0ba7b79eda154afb3db60e97e))

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.9.0...finrep-service-v0.9.1) (2026-08-26)


### Bug Fixes

* **finrep:** select immutable reporting periods ([#7001](https://github.com/JiRaska/open-bank-oss/issues/7001)) ([9302207](https://github.com/JiRaska/open-bank-oss/commit/9302207dd94677762283f4a6d87c0fdac5ce0bef))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.8.2...finrep-service-v0.9.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.8.1...finrep-service-v0.8.2) (2026-08-21)


### Bug Fixes

* **finrep:** require the ledger's own balance verdict to agree ([#6163](https://github.com/JiRaska/open-bank-oss/issues/6163)) ([49f45e9](https://github.com/JiRaska/open-bank-oss/commit/49f45e93c586f8e40565066571d7909231cc6030)), closes [#6011](https://github.com/JiRaska/open-bank-oss/issues/6011)

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.8.0...finrep-service-v0.8.1) (2026-08-20)


### Bug Fixes

* **finrep:** compute isBalanced from an identity that can actually fail ([#6010](https://github.com/JiRaska/open-bank-oss/issues/6010)) ([d7cb756](https://github.com/JiRaska/open-bank-oss/commit/d7cb75630458bdfae6616183aad6fd27ee7dfc91))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.7.1...finrep-service-v0.8.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.7.0...finrep-service-v0.7.1) (2026-08-16)


### Bug Fixes

* **infra:** give six services an OIDC client they can actually mint from ([#4990](https://github.com/JiRaska/open-bank-oss/issues/4990)) ([f43f88c](https://github.com/JiRaska/open-bank-oss/commit/f43f88c815fd50c32ef797147c6cbc57f060cab0))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.6.5...finrep-service-v0.7.0) (2026-08-15)


### Features

* **ledger:** persist frozen trial balance evidence ([#4826](https://github.com/JiRaska/open-bank-oss/issues/4826)) ([22d8120](https://github.com/JiRaska/open-bank-oss/commit/22d812083c1c1f5d177e4718bdb1e95e12c6f06e))

## [0.6.5](https://github.com/JiRaska/open-bank-oss/compare/finrep-service-v0.6.4...finrep-service-v0.6.5) (2026-08-07)


### Bug Fixes

* **finrep:** open the management port the probes have always asked for ([#4030](https://github.com/JiRaska/open-bank-oss/issues/4030)) ([475e6ea](https://github.com/JiRaska/open-bank-oss/commit/475e6ea04f2adeb8ac50444f5fd38c55affe6fa7))

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

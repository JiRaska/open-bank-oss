# Changelog

## [0.9.4](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.9.3...anacredit-service-v0.9.4) (2026-08-22)


### Bug Fixes

* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [0.9.3](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.9.2...anacredit-service-v0.9.3) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [0.9.2](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.9.1...anacredit-service-v0.9.2) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.9.0...anacredit-service-v0.9.1) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.8.0...anacredit-service-v0.9.0) (2026-07-25)


### Features

* **anacredit:** instrument exposure intake, return shape and lending-event outcomes ([#2270](https://github.com/JiRaska/open-bank-oss/issues/2270)) ([81ebe48](https://github.com/JiRaska/open-bank-oss/commit/81ebe485ac2af0e5364092d2234f4747d2887fda)), closes [#2255](https://github.com/JiRaska/open-bank-oss/issues/2255)

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.7.1...anacredit-service-v0.8.0) (2026-07-13)


### Features

* **governance:** bootstrap OPA enforcement for anacredit/card-issuance/interest ([#938](https://github.com/JiRaska/open-bank-oss/issues/938)) ([#962](https://github.com/JiRaska/open-bank-oss/issues/962)) ([8a35e3a](https://github.com/JiRaska/open-bank-oss/commit/8a35e3adfd4202339209aa67237082475dc7018d))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.7.0...anacredit-service-v0.7.1) (2026-07-12)


### Bug Fixes

* **anacredit:** use Admin.listGroups instead of deprecated listConsumerGroups ([#866](https://github.com/JiRaska/open-bank-oss/issues/866)) ([c6a8c81](https://github.com/JiRaska/open-bank-oss/commit/c6a8c811d112e8f9c66f6a962d2bef520bed56df)), closes [#865](https://github.com/JiRaska/open-bank-oss/issues/865)

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.6.0...anacredit-service-v0.7.0) (2026-07-11)


### Features

* **anacredit:** add Dockerfile and register with auto-deploy fleet ([#724](https://github.com/JiRaska/open-bank-oss/issues/724)) ([7616836](https://github.com/JiRaska/open-bank-oss/commit/7616836954cc2f8e3c732312b3d399dadb2996fc)), closes [#601](https://github.com/JiRaska/open-bank-oss/issues/601)


### Bug Fixes

* **anacredit:** move group.id off dotted YAML keys onto env vars ([#701](https://github.com/JiRaska/open-bank-oss/issues/701)) ([0934f01](https://github.com/JiRaska/open-bank-oss/commit/0934f01137cc9a41e32194038827216a6c8ad3e7))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.5.0...anacredit-service-v0.6.0) (2026-07-09)


### Features

* **anacredit:** add real Postgres persistence for credit exposures ([#633](https://github.com/JiRaska/open-bank-oss/issues/633)) ([33695af](https://github.com/JiRaska/open-bank-oss/commit/33695af7b3847758d557e96aea4c24fba71d9cb3)), closes [#623](https://github.com/JiRaska/open-bank-oss/issues/623)


### Bug Fixes

* **anacredit:** resolve Flyway V1 migration version collision ([#674](https://github.com/JiRaska/open-bank-oss/issues/674)) ([c954486](https://github.com/JiRaska/open-bank-oss/commit/c95448676191f1ee0fb25eb7382dd6439cffbc31))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.4.3...anacredit-service-v0.5.0) (2026-07-09)


### Features

* **lending, anacredit:** loan.stage_changed event integration ([#642](https://github.com/JiRaska/open-bank-oss/issues/642)) ([d456578](https://github.com/JiRaska/open-bank-oss/commit/d456578a94dcad64ccf11ba36dc1d3886cc7cbc0))

## [0.4.3](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.4.2...anacredit-service-v0.4.3) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.4.2](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.4.1...anacredit-service-v0.4.2) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.4.0...anacredit-service-v0.4.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.3.1...anacredit-service-v0.4.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.3.0...anacredit-service-v0.3.1) (2026-06-15)


### Bug Fixes

* **anacredit:** assign unique HTTP port 8137 (resolve collision with onboarding) ([#1041](https://github.com/JiRaska/open-bank-oss/issues/1041)) ([fb88443](https://github.com/JiRaska/open-bank-oss/commit/fb88443b5f1c84e56ca4650265d2787e18de9287))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.2.0...anacredit-service-v0.3.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/anacredit-service-v0.1.1...anacredit-service-v0.2.0) (2026-06-08)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))

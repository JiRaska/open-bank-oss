# Changelog

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/finops-agent-v0.6.0...finops-agent-v0.6.1) (2026-08-27)


### Performance

* **flaky-test-hunter:** add read-path smoke coverage ([#7374](https://github.com/JiRaska/open-bank-oss/issues/7374)) ([b93d00d](https://github.com/JiRaska/open-bank-oss/commit/b93d00d3ab19f3705b87212ab0ce60cfd1119f6f))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/finops-agent-v0.5.0...finops-agent-v0.6.0) (2026-08-07)


### Features

* **scheduler:** register workflow liveness for five non-money-path jobs (ADR-0237) ([#3735](https://github.com/JiRaska/open-bank-oss/issues/3735)) ([90801b2](https://github.com/JiRaska/open-bank-oss/commit/90801b203c4eec229180321e869c926be3a99ee8))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/finops-agent-v0.4.0...finops-agent-v0.5.0) (2026-08-05)


### Features

* **finops-agent:** wire ADR-0148 prompt registry and LLM gateway (ADR-0112 P4) ([407c1b3](https://github.com/JiRaska/open-bank-oss/commit/407c1b3cd175b32c443077edbcfcbf4e28cc0248))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/finops-agent-v0.3.1...finops-agent-v0.4.0) (2026-08-02)


### Features

* **devops,finops:** run the daily analysis sweep on a schedule, not only when asked ([#3370](https://github.com/JiRaska/open-bank-oss/issues/3370)) ([a8c49ef](https://github.com/JiRaska/open-bank-oss/commit/a8c49ef392c021be8851d2e500c2af8a7643f8e9))

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/finops-agent-v0.3.0...finops-agent-v0.3.1) (2026-07-25)


### Bug Fixes

* **authz:** realm-issued role names across 9 services + enforce @RolesAllowed parity ([#2404](https://github.com/JiRaska/open-bank-oss/issues/2404)) ([#2418](https://github.com/JiRaska/open-bank-oss/issues/2418)) ([64a1f9b](https://github.com/JiRaska/open-bank-oss/commit/64a1f9be47bedbda5ffad876bb0394f404503821))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/finops-agent-v0.2.3...finops-agent-v0.3.0) (2026-07-23)


### Features

* **finops-agent:** durable Postgres cost-anomaly memory (ADR-0112/0148) ([#2013](https://github.com/JiRaska/open-bank-oss/issues/2013)) ([6549f97](https://github.com/JiRaska/open-bank-oss/commit/6549f97d75bda9d6f555da3ee7521e4fb5ec7edf))

## [0.2.3](https://github.com/JiRaska/open-bank-oss/compare/finops-agent-v0.2.2...finops-agent-v0.2.3) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.2.2](https://github.com/JiRaska/open-bank-oss/compare/finops-agent-v0.2.1...finops-agent-v0.2.2) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)
* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([c61fcaf](https://github.com/JiRaska/open-bank-oss/commit/c61fcafde76be08f716c710462be70752073aba1))

## [0.2.1](https://github.com/JiRaska/open-bank-oss/compare/finops-agent-v0.2.0...finops-agent-v0.2.1) (2026-06-28)


### Bug Fixes

* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([35c6a63](https://github.com/JiRaska/open-bank-oss/commit/35c6a6309e10c0a56558d93de5c494a89da72ceb))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/finops-agent-v0.1.0...finops-agent-v0.2.0) (2026-06-27)


### Features

* **finops-agent:** add openbank-finops-agent Temporal service (ADR-0112 P3) ([#2184](https://github.com/JiRaska/open-bank-oss/issues/2184)) ([e8c98cc](https://github.com/JiRaska/open-bank-oss/commit/e8c98ccbe7c1fd5ef2288134c6d95db38958bc40))

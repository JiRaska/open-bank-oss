# Changelog

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.5.0...engagement-service-v0.6.0) (2026-08-13)


### Features

* **campaign:** add measured holdout experiments ([#4471](https://github.com/JiRaska/open-bank-oss/issues/4471)) ([8756228](https://github.com/JiRaska/open-bank-oss/commit/8756228553b5daa828762cace3a84457d3a4b816))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.4.1...engagement-service-v0.5.0) (2026-08-09)


### Features

* **engagement:** expose a party's active adverse states as a read API ([#4302](https://github.com/JiRaska/open-bank-oss/issues/4302)) ([8617fa9](https://github.com/JiRaska/open-bank-oss/commit/8617fa9e5502c80accb1b5154acbedbef01a1e0a)), closes [#4265](https://github.com/JiRaska/open-bank-oss/issues/4265)

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.4.0...engagement-service-v0.4.1) (2026-08-09)


### Bug Fixes

* **engagement:** consume dispute.opened so an open dispute suppresses marketing ([#4297](https://github.com/JiRaska/open-bank-oss/issues/4297)) ([d0031f5](https://github.com/JiRaska/open-bank-oss/commit/d0031f56e1ac7f8e421501c13b98867719013fd8)), closes [#4262](https://github.com/JiRaska/open-bank-oss/issues/4262)

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.3.0...engagement-service-v0.4.0) (2026-08-09)


### Features

* **fraud:** raise a marketing-suppression fraud-hold signal (ADR-0220 D3.5, [#2749](https://github.com/JiRaska/open-bank-oss/issues/2749)) ([#4252](https://github.com/JiRaska/open-bank-oss/issues/4252)) ([26486a0](https://github.com/JiRaska/open-bank-oss/commit/26486a014c2df3b32b6523fd494d7071d76406f4))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.2.4...engagement-service-v0.3.0) (2026-08-08)


### Features

* **engagement:** materialise ARREARS + ERASURE_REQUESTED for the D3.5 targeting exclusion ([#4106](https://github.com/JiRaska/open-bank-oss/issues/4106)) ([fb5c455](https://github.com/JiRaska/open-bank-oss/commit/fb5c455bc69fc5749c05c2798d7bcf9358a1b7fd))


### Bug Fixes

* **engagement:** remove the duplicate %test authz key, a second time ([#4184](https://github.com/JiRaska/open-bank-oss/issues/4184)) ([c75dd17](https://github.com/JiRaska/open-bank-oss/commit/c75dd17276771a902e9a8a40fe6dfef9cc52cef2))

## [0.2.4](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.2.3...engagement-service-v0.2.4) (2026-08-08)


### Bug Fixes

* **engagement:** merge the duplicated %test authz key — main is red ([#4160](https://github.com/JiRaska/open-bank-oss/issues/4160)) ([d0af48b](https://github.com/JiRaska/open-bank-oss/commit/d0af48b68518c9f85003dc3ac69719b158199eba))
* **engagement:** SurfaceRestContractIT is red on main — an identity and test-profile authz enforcement ([#4128](https://github.com/JiRaska/open-bank-oss/issues/4128)) ([75ed482](https://github.com/JiRaska/open-bank-oss/commit/75ed48229cfda0ce5f099d8ff344b24c7a837b13))

## [0.2.3](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.2.2...engagement-service-v0.2.3) (2026-08-08)


### Bug Fixes

* **engagement:** add the missing governance.yaml — main is red without it ([#4114](https://github.com/JiRaska/open-bank-oss/issues/4114)) ([a4a94c4](https://github.com/JiRaska/open-bank-oss/commit/a4a94c429eae56fee86e4d5be1abde9c3ccf0245))

## [0.2.2](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.2.1...engagement-service-v0.2.2) (2026-08-08)


### Security

* **libs:** ADR-0219 D4 compile-time wiring assertion for the contact gate ([#4072](https://github.com/JiRaska/open-bank-oss/issues/4072)) ([9342915](https://github.com/JiRaska/open-bank-oss/commit/93429152b875d5b0656dc7f7ddfc471e6e96b13f))

## [0.2.1](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.2.0...engagement-service-v0.2.1) (2026-08-07)


### Security

* **engagement:** add edge-service-engagement authz policy ([#4054](https://github.com/JiRaska/open-bank-oss/issues/4054)) ([6b3594b](https://github.com/JiRaska/open-bank-oss/commit/6b3594b2b54cd4c2522f748daec593173ab27664))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.1.0...engagement-service-v0.2.0) (2026-08-07)


### Features

* **engagement:** first slice of ADR-0220 in-app surfaces — domain layer only ([#4025](https://github.com/JiRaska/open-bank-oss/issues/4025)) ([2e85004](https://github.com/JiRaska/open-bank-oss/commit/2e85004af104473a19f8c30aca3e753b05b48945))
* **engagement:** REST, persistence, outbox and consent-gate wiring for ADR-0220 ([#4048](https://github.com/JiRaska/open-bank-oss/issues/4048)) ([fac75d1](https://github.com/JiRaska/open-bank-oss/commit/fac75d19e767417f9beb0ba077d570a1d3e6ded4))

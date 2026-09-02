# Changelog

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.13.0...engagement-service-v0.14.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.12.4...engagement-service-v0.13.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.12.4](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.12.3...engagement-service-v0.12.4) (2026-08-22)


### Bug Fixes

* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [0.12.3](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.12.2...engagement-service-v0.12.3) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [0.12.2](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.12.1...engagement-service-v0.12.2) (2026-08-19)


### Bug Fixes

* stop swallowing transient event-consumer failures as an ack across 4 services ([#5698](https://github.com/JiRaska/open-bank-oss/issues/5698)) ([#5725](https://github.com/JiRaska/open-bank-oss/issues/5725)) ([3219c5d](https://github.com/JiRaska/open-bank-oss/commit/3219c5de3944c39f22a94b4c44532b8521f8a6b5))

## [0.12.1](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.12.0...engagement-service-v0.12.1) (2026-08-18)


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.11.1...engagement-service-v0.12.0) (2026-08-17)


### Features

* **engagement:** add gamification engine domain slice (ADR-0261) ([#5138](https://github.com/JiRaska/open-bank-oss/issues/5138)) ([fef7be1](https://github.com/JiRaska/open-bank-oss/commit/fef7be1a53ffc923da8617d0a652732800639632))


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.11.1](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.11.0...engagement-service-v0.11.1) (2026-08-16)


### Bug Fixes

* **infra:** give six services an OIDC client they can actually mint from ([#4990](https://github.com/JiRaska/open-bank-oss/issues/4990)) ([f43f88c](https://github.com/JiRaska/open-bank-oss/commit/f43f88c815fd50c32ef797147c6cbc57f060cab0))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.10.0...engagement-service-v0.11.0) (2026-08-14)


### Features

* **engagement:** support campaign story placements ([#4770](https://github.com/JiRaska/open-bank-oss/issues/4770)) ([aca2935](https://github.com/JiRaska/open-bank-oss/commit/aca29354c69f7df9ec2bf481adee865651b4d181))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.9.0...engagement-service-v0.10.0) (2026-08-13)


### Features

* **campaign:** add in-app campaign surfaces ([#4586](https://github.com/JiRaska/open-bank-oss/issues/4586)) ([8f81863](https://github.com/JiRaska/open-bank-oss/commit/8f81863a2e890efa5f9de77ff59201bb36e2a46a))


### Bug Fixes

* **engagement:** preserve legacy banner placements ([#4609](https://github.com/JiRaska/open-bank-oss/issues/4609)) ([e198644](https://github.com/JiRaska/open-bank-oss/commit/e19864494ddac96d83637ba070d8649921b544f3))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.8.0...engagement-service-v0.9.0) (2026-08-13)


### Features

* **campaign:** add in-app banner channel ([#4577](https://github.com/JiRaska/open-bank-oss/issues/4577)) ([d95c85c](https://github.com/JiRaska/open-bank-oss/commit/d95c85cf3fbe0428e4cc5e44bcca27d05bc574ab))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.7.0...engagement-service-v0.8.0) (2026-08-13)


### Features

* add trusted campaign engagement analytics ([#4555](https://github.com/JiRaska/open-bank-oss/issues/4555)) ([22ab0ba](https://github.com/JiRaska/open-bank-oss/commit/22ab0ba6930bff0d70594ab2ee72cf5407bee0b8))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/engagement-service-v0.6.0...engagement-service-v0.7.0) (2026-08-13)


### Features

* **campaign:** validate push engagement attribution ([#4526](https://github.com/JiRaska/open-bank-oss/issues/4526)) ([512c831](https://github.com/JiRaska/open-bank-oss/commit/512c831570cc654246f92e4447d5b868b40957f8))

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

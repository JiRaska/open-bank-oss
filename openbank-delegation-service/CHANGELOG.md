# Changelog

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.12.0...delegation-service-v0.13.0) (2026-09-02)


### Features

* **delegation:** preview client grants before SCA ([#8173](https://github.com/JiRaska/open-bank-oss/issues/8173)) ([cb8065c](https://github.com/JiRaska/open-bank-oss/commit/cb8065c425dde53ba02073b938ca908f2f3e7dfc))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.11.0...delegation-service-v0.12.0) (2026-09-01)


### Features

* **ledger:** declare sourceService on five money-path event producers ([#7716](https://github.com/JiRaska/open-bank-oss/issues/7716)) ([bf489ad](https://github.com/JiRaska/open-bank-oss/commit/bf489ad147f16b461e7a6c3d6f1244f596741a73))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.10.0...delegation-service-v0.11.0) (2026-08-31)


### Features

* **delegation:** expand role capability catalog ([#7732](https://github.com/JiRaska/open-bank-oss/issues/7732)) ([4fdd0d3](https://github.com/JiRaska/open-bank-oss/commit/4fdd0d3e3c3a3d4b85c37c759300af60600a6cfb))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.9.0...delegation-service-v0.10.0) (2026-08-31)


### Features

* **delegation:** manage reusable role presets ([#7697](https://github.com/JiRaska/open-bank-oss/issues/7697)) ([c3f21ee](https://github.com/JiRaska/open-bank-oss/commit/c3f21ee63cc482a7ab27e6002967b23654812abe))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.8.0...delegation-service-v0.9.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.7.0...delegation-service-v0.8.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.6.2...delegation-service-v0.7.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.6.1...delegation-service-v0.6.2) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.6.0...delegation-service-v0.6.1) (2026-08-16)


### Bug Fixes

* **delegation:** record expiration sweep liveness ([#5034](https://github.com/JiRaska/open-bank-oss/issues/5034)) ([db6e903](https://github.com/JiRaska/open-bank-oss/commit/db6e9039e6ffdd0512dafd806b9ae53400570d3f))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.5.2...delegation-service-v0.6.0) (2026-08-09)


### Features

* **delegation:** count delegated spend before the money moves ([#4196](https://github.com/JiRaska/open-bank-oss/issues/4196)) ([9d12fa0](https://github.com/JiRaska/open-bank-oss/commit/9d12fa0cd34038cfdd5c20c55d9f3e935d68910d))


### Bug Fixes

* **delegation:** an unpriced check against a priced grant is a denial, not coverage ([#4101](https://github.com/JiRaska/open-bank-oss/issues/4101)) ([3ba3f15](https://github.com/JiRaska/open-bank-oss/commit/3ba3f15e38c635a3f46261ab8c9004986dcfb909))

## [0.5.2](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.5.1...delegation-service-v0.5.2) (2026-08-07)


### Bug Fixes

* **delegation:** refuse dailyLimit/monthlyLimit — ADR-0232's cumulative ceilings are enforced nowhere ([#3613](https://github.com/JiRaska/open-bank-oss/issues/3613)) ([841d20e](https://github.com/JiRaska/open-bank-oss/commit/841d20e7f6f5e8674a20bdbd08e9488f64365fc6))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.5.0...delegation-service-v0.5.1) (2026-08-07)


### Bug Fixes

* **delegation:** attach the M2M token to every outbound REST client ([#3937](https://github.com/JiRaska/open-bank-oss/issues/3937)) ([9a7212a](https://github.com/JiRaska/open-bank-oss/commit/9a7212a51196ae39975902b3e7cee670c0e1326b))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.4.1...delegation-service-v0.5.0) (2026-08-06)


### Features

* **delegation:** snapshot counterparty display names onto a grant ([#3797](https://github.com/JiRaska/open-bank-oss/issues/3797)) ([5bc41f6](https://github.com/JiRaska/open-bank-oss/commit/5bc41f6f4735384fc65388d9a5854a3ce88f4b1d))

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.4.0...delegation-service-v0.4.1) (2026-08-02)


### Bug Fixes

* **delegation:** the SCA pre-check made every customer ceremony impossible ([#3537](https://github.com/JiRaska/open-bank-oss/issues/3537)) ([6261040](https://github.com/JiRaska/open-bank-oss/commit/6261040eff75484f1bc849b298b9939980519acb))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.3.2...delegation-service-v0.4.0) (2026-08-02)


### Features

* **delegation:** announce the authorization mode at boot ([#3440](https://github.com/JiRaska/open-bank-oss/issues/3440)) ([cb27ea0](https://github.com/JiRaska/open-bank-oss/commit/cb27ea0d2fd59396148ccc275008c247b3afb329))

## [0.3.2](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.3.1...delegation-service-v0.3.2) (2026-08-02)


### Bug Fixes

* **delegation:** put the validity window and the ceiling on the events that grant authority ([#3411](https://github.com/JiRaska/open-bank-oss/issues/3411)) ([f75fe6d](https://github.com/JiRaska/open-bank-oss/commit/f75fe6d3af521b10d171c3dd5643829fe91be0c8))

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.3.0...delegation-service-v0.3.1) (2026-08-02)


### Bug Fixes

* **delegation:** runtime-only Dockerfile, matching the [#3392](https://github.com/JiRaska/open-bank-oss/issues/3392) convention ([#3424](https://github.com/JiRaska/open-bank-oss/issues/3424)) ([fbdb1dd](https://github.com/JiRaska/open-bank-oss/commit/fbdb1dd8a56905d07e507a498b2acec288900330))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.2.0...delegation-service-v0.3.0) (2026-08-02)


### Features

* **delegation:** gitops component + ALL_SERVICES entry so delegation-service can deploy ([#3414](https://github.com/JiRaska/open-bank-oss/issues/3414)) ([8992de5](https://github.com/JiRaska/open-bank-oss/commit/8992de5359c598ee9de07f2971dc44b7252d0c1d))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/delegation-service-v0.1.0...delegation-service-v0.2.0) (2026-08-02)


### Features

* **delegation:** delegation-service — customer-to-party access grants (ADR-0232) ([#2971](https://github.com/JiRaska/open-bank-oss/issues/2971)) ([5ce707b](https://github.com/JiRaska/open-bank-oss/commit/5ce707b1c97babddda6b1b7a7df3050d988e2bdf))

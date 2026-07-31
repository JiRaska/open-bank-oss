# Changelog

## [0.3.6](https://github.com/JiRaska/open-bank-oss/compare/v0.3.5...v0.3.6) (2026-07-31)


### Bug Fixes

* **campaign:** give SegmentRule a persistence format it can be read back from ([#2908](https://github.com/JiRaska/open-bank-oss/issues/2908)) ([dde5f18](https://github.com/JiRaska/open-bank-oss/commit/dde5f188ad87698e79e17dd10133143e761a772b))

## [0.3.5](https://github.com/JiRaska/open-bank-oss/compare/v0.3.4...v0.3.5) (2026-07-31)


### Bug Fixes

* **campaign:** store the JSON documents as text so reads stop throwing ([#2902](https://github.com/JiRaska/open-bank-oss/issues/2902)) ([80b7ccb](https://github.com/JiRaska/open-bank-oss/commit/80b7ccb57afb60cf09680ca1126903f7a995663a)), closes [#2749](https://github.com/JiRaska/open-bank-oss/issues/2749)

## [0.3.4](https://github.com/JiRaska/open-bank-oss/compare/v0.3.3...v0.3.4) (2026-07-31)


### Bug Fixes

* **campaign:** evaluate segments against the silver layer that exists ([#2896](https://github.com/JiRaska/open-bank-oss/issues/2896)) ([1916342](https://github.com/JiRaska/open-bank-oss/commit/19163420e6c8121a365ddd2231bb766e4060f28b))

## [0.3.3](https://github.com/JiRaska/open-bank-oss/compare/v0.3.2...v0.3.3) (2026-07-31)


### Bug Fixes

* **campaign:** snake_case column naming + the Temporal namespace the worker polls ([#2881](https://github.com/JiRaska/open-bank-oss/issues/2881)) ([71125c0](https://github.com/JiRaska/open-bank-oss/commit/71125c072ee347c5d0afccbd5964a6c57ddda235)), closes [#2749](https://github.com/JiRaska/open-bank-oss/issues/2749)

## [0.3.2](https://github.com/JiRaska/open-bank-oss/compare/v0.3.1...v0.3.2) (2026-07-31)


### Bug Fixes

* **campaign:** open the management interface the probes and scrape target ([#2870](https://github.com/JiRaska/open-bank-oss/issues/2870)) ([706f2ae](https://github.com/JiRaska/open-bank-oss/commit/706f2ae97feac01e82e6643254e39c76f5e9e725)), closes [#2749](https://github.com/JiRaska/open-bank-oss/issues/2749)

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/v0.3.0...v0.3.1) (2026-07-31)


### Bug Fixes

* **campaign:** make the service actually boot — redis, OPA bundle mode, keycloak host ([#2865](https://github.com/JiRaska/open-bank-oss/issues/2865)) ([42d9d3e](https://github.com/JiRaska/open-bank-oss/commit/42d9d3e0ec9dc6ec0943bfcae513a47723c77ce1))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/v0.2.0...v0.3.0) (2026-07-31)


### Features

* **infra:** GitOps component for campaign-service + sandbox deploy wiring ([#2838](https://github.com/JiRaska/open-bank-oss/issues/2838)) ([bebbf84](https://github.com/JiRaska/open-bank-oss/commit/bebbf84ae16f878d5603dfd17ba62e27dfcd696c))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/v0.1.0...v0.2.0) (2026-07-31)


### Features

* **campaign:** campaign-service first slice — deterministic segments, consent-gated Temporal journeys (ADR-0200/0209 D3) ([#2751](https://github.com/JiRaska/open-bank-oss/issues/2751)) ([27e83b4](https://github.com/JiRaska/open-bank-oss/commit/27e83b42c70cc7289d5f684e1ccd40c3f326c14c))
* **feedback:** rendering context and session id on screen feedback (ADR-0192) ([#2176](https://github.com/JiRaska/open-bank-oss/issues/2176)) ([e56b9fd](https://github.com/JiRaska/open-bank-oss/commit/e56b9fdeba384a23ed06c3208b937a7857074b9f))

# Changelog

## [1.0.2](https://github.com/JiRaska/open-bank-oss/compare/pension-fund-service-v1.0.1...pension-fund-service-v1.0.2) (2026-10-16)


### Security

* **opa:** authorize machines by the verified service_account flag (phase 2 of 2, gated on [#12538](https://github.com/JiRaska/open-bank-oss/issues/12538) rollout) ([#12537](https://github.com/JiRaska/open-bank-oss/issues/12537)) ([abf3aff](https://github.com/JiRaska/open-bank-oss/commit/abf3aff4f24fd170a6e1641b3008f9e4648a9572))

## [1.0.1](https://github.com/JiRaska/open-bank-oss/compare/pension-fund-service-v1.0.0...pension-fund-service-v1.0.1) (2026-10-14)


### Bug Fixes

* audit follow-ups 4-12 — live rules.yaml reads, timeout/pool gates, ADR staleness gate, coverage floors ([#12773](https://github.com/JiRaska/open-bank-oss/issues/12773)) ([09377e6](https://github.com/JiRaska/open-bank-oss/commit/09377e6fe51a06df94b5ad8040c5cc1478c3d416))

## [1.0.0](https://github.com/JiRaska/open-bank-oss/compare/pension-fund-service-v0.5.0...pension-fund-service-v1.0.0) (2026-10-14)


### ⚠ BREAKING CHANGES

* **pension:** pension-service API moves to /api/v2; POST /api/v1/pension/contracts/{id}/activate and /early-termination are removed, and PUT .../strategy requires SCA.

### Features

* **pension:** final integration of follow-ups F1-F7 (ADR-0334 final integration) ([#12435](https://github.com/JiRaska/open-bank-oss/issues/12435)) ([1a7bd7e](https://github.com/JiRaska/open-bank-oss/commit/1a7bd7e31b28ed2c938abbb95cf4a73757c26eed))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/pension-fund-service-v0.4.0...pension-fund-service-v0.5.0) (2026-10-12)


### Features

* **pension-fund:** fund-service observability — NAV pipeline, AUM, orders, approvals, alerts, dashboards ([#12442](https://github.com/JiRaska/open-bank-oss/issues/12442)) ([f05874a](https://github.com/JiRaska/open-bank-oss/commit/f05874a76a9d289450f31cbe79d0bbd5fc140d33))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/pension-fund-service-v0.3.0...pension-fund-service-v0.4.0) (2026-10-11)


### Features

* **pension-fund:** accumulate P&L lines and classify fund positions for the ČNB returns ([#12493](https://github.com/JiRaska/open-bank-oss/issues/12493)) ([c4d4b04](https://github.com/JiRaska/open-bank-oss/commit/c4d4b045231d6eab0b76c05c4694eaeb7466fecb))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/pension-fund-service-v0.2.1...pension-fund-service-v0.3.0) (2026-10-10)


### Features

* **pension-fund:** period-end reporting read model for the ČNB returns ([#12464](https://github.com/JiRaska/open-bank-oss/issues/12464)) ([4c45622](https://github.com/JiRaska/open-bank-oss/commit/4c45622df8d09df26fa1e790d6dcda942ed866ec))

## [0.2.1](https://github.com/JiRaska/open-bank-oss/compare/pension-fund-service-v0.2.0...pension-fund-service-v0.2.1) (2026-10-10)


### Security

* **pension-fund:** close holdings to the shared M2M account via a non-read verb ([#12483](https://github.com/JiRaska/open-bank-oss/issues/12483)) ([f86ac4f](https://github.com/JiRaska/open-bank-oss/commit/f86ac4fcd712011975cfa81054b3af39bd021ebf))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/pension-fund-service-v0.1.0...pension-fund-service-v0.2.0) (2026-10-10)


### Features

* **pension-fund:** bootstrap pension-fund-service — segregated funds, strategies, forward-priced unit register, four-eyes NAV (ADR-0334 S4) ([#12355](https://github.com/JiRaska/open-bank-oss/issues/12355)) ([ffea5a5](https://github.com/JiRaska/open-bank-oss/commit/ffea5a5e692a754b83dce153592a96fa5287318d))

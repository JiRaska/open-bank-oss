# Changelog

## [2.0.0](https://github.com/JiRaska/open-bank-oss/compare/pension-service-v1.0.0...pension-service-v2.0.0) (2026-10-14)


### ⚠ BREAKING CHANGES

* **customer-edge,admin-ui:** pension-service API moves to /api/v2; POST /api/v1/pension/contracts/{id}/activate and /early-termination are removed, and PUT .../strategy requires SCA.

### Features

* **customer-edge,admin-ui:** pension lifecycle UI and BFF (ADR-0334 S6) ([#12359](https://github.com/JiRaska/open-bank-oss/issues/12359)) ([542d3aa](https://github.com/JiRaska/open-bank-oss/commit/542d3aa3d8b6daef4da9f254dda257231e0bdbf2))

## [1.0.0](https://github.com/JiRaska/open-bank-oss/compare/pension-service-v0.2.1...pension-service-v1.0.0) (2026-10-14)


### ⚠ BREAKING CHANGES

* **pension:** pension-service API moves to /api/v2; POST /api/v1/pension/contracts/{id}/activate and /early-termination are removed, and PUT .../strategy requires SCA.

### Features

* **pension:** final integration of follow-ups F1-F7 (ADR-0334 final integration) ([#12435](https://github.com/JiRaska/open-bank-oss/issues/12435)) ([1a7bd7e](https://github.com/JiRaska/open-bank-oss/commit/1a7bd7e31b28ed2c938abbb95cf4a73757c26eed))

## [0.2.1](https://github.com/JiRaska/open-bank-oss/compare/pension-service-v0.2.0...pension-service-v0.2.1) (2026-10-11)


### Security

* **pension:** bind trusted relay to client identity, not principal name ([#12447](https://github.com/JiRaska/open-bank-oss/issues/12447)) ([385d90b](https://github.com/JiRaska/open-bank-oss/commit/385d90b82029b470413f636f7b1dde280359efbb))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/pension-service-v0.1.0...pension-service-v0.2.0) (2026-10-10)


### Features

* **pension:** bootstrap pension-service — contract lifecycle and jurisdiction packs (ADR-0334 S1) ([#12353](https://github.com/JiRaska/open-bank-oss/issues/12353)) ([83f77db](https://github.com/JiRaska/open-bank-oss/commit/83f77dbdee977990f878bde07bf24c182b538bfb))

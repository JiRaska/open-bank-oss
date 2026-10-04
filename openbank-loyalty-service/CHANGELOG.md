# Changelog

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/loyalty-service-v0.4.0...loyalty-service-v0.4.1) (2026-10-03)


### Performance

* **loyalty,engagement,notification,aml,statement:** kernel outbox v2 repository (ADR-0327 phase 2) ([#11754](https://github.com/JiRaska/open-bank-oss/issues/11754)) ([3c804ab](https://github.com/JiRaska/open-bank-oss/commit/3c804ab9e0ec6cb46a1d6edaa472ee552d0a61c6))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/loyalty-service-v0.3.1...loyalty-service-v0.4.0) (2026-10-02)


### Features

* **loyalty:** earn Lístky for a qualified referral, once per invite ([#10013](https://github.com/JiRaska/open-bank-oss/issues/10013)) ([2e5c44c](https://github.com/JiRaska/open-bank-oss/commit/2e5c44c6d97469c180c5ae8ce9a38191ca5be276))

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/loyalty-service-v0.3.0...loyalty-service-v0.3.1) (2026-09-29)


### Security

* **fleet:** scope OIDC TLS verification=none to %dev and gate it ([#10870](https://github.com/JiRaska/open-bank-oss/issues/10870)) ([cf7aa55](https://github.com/JiRaska/open-bank-oss/commit/cf7aa5528809917ccb7e4a94d80f25b2d136c7b4))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/loyalty-service-v0.2.1...loyalty-service-v0.3.0) (2026-09-13)


### Features

* **loyalty:** list a party's benefit grants ([#9963](https://github.com/JiRaska/open-bank-oss/issues/9963)) ([7559b35](https://github.com/JiRaska/open-bank-oss/commit/7559b35ae1943be2a346f1b9420dc40ef2089888))

## [0.2.1](https://github.com/JiRaska/open-bank-oss/compare/loyalty-service-v0.2.0...loyalty-service-v0.2.1) (2026-09-13)


### Bug Fixes

* **loyalty:** guard loyalty_outbox created_at plausibility at INSERT ([#9314](https://github.com/JiRaska/open-bank-oss/issues/9314)) ([5106fb1](https://github.com/JiRaska/open-bank-oss/commit/5106fb15871fa5caa3739ff687e77e0a4e16f3ef))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/loyalty-service-v0.1.0...loyalty-service-v0.2.0) (2026-09-08)


### Features

* **loyalty:** the Lístek ledger — earn, redeem, expire, with the cap as an outcome ([#8807](https://github.com/JiRaska/open-bank-oss/issues/8807)) ([104df76](https://github.com/JiRaska/open-bank-oss/commit/104df76df31c6846be00ab3d7885edd59c2e5d3a))

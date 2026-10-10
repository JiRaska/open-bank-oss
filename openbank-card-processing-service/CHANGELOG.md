# Changelog

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/card-processing-service-v0.5.2...card-processing-service-v0.6.0) (2026-10-09)


### Features

* **libs:** purge SENT outbox rows fleet-wide with one shared retention job ([#11899](https://github.com/JiRaska/open-bank-oss/issues/11899)) ([b322ed7](https://github.com/JiRaska/open-bank-oss/commit/b322ed7116137ecc8f132dc79cd79444c995bb2f))

## [0.5.2](https://github.com/JiRaska/open-bank-oss/compare/card-processing-service-v0.5.1...card-processing-service-v0.5.2) (2026-10-04)


### Bug Fixes

* **card-processing:** never mint a simulated token reference with a digit run ([#12109](https://github.com/JiRaska/open-bank-oss/issues/12109)) ([5fa95c7](https://github.com/JiRaska/open-bank-oss/commit/5fa95c7a19df70ce2ebc165380e9dde901c918e4))
* **card-processing:** require idempotency keys on token status and dispute refresh ([#12031](https://github.com/JiRaska/open-bank-oss/issues/12031)) ([dfe96df](https://github.com/JiRaska/open-bank-oss/commit/dfe96dffa0265d4c6c224098f4705aa8bede40fb))
* **card-processing:** send fraud-service its real scoring contract, and pin both money-path pacts ([#12073](https://github.com/JiRaska/open-bank-oss/issues/12073)) ([18ba0b8](https://github.com/JiRaska/open-bank-oss/commit/18ba0b83d7b0ae10422d8528f2d1ad5eb929154a))
* **docs:** keep every runnable service documentation current ([#11989](https://github.com/JiRaska/open-bank-oss/issues/11989)) ([88b6e08](https://github.com/JiRaska/open-bank-oss/commit/88b6e087386cd885badf22f75cc4694faa0f30bd))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/card-processing-service-v0.5.0...card-processing-service-v0.5.1) (2026-10-04)


### Bug Fixes

* **card-processing:** apply each clearing once per key ([#11990](https://github.com/JiRaska/open-bank-oss/issues/11990)) ([1c65c8e](https://github.com/JiRaska/open-bank-oss/commit/1c65c8e76b311b35998fbffc53c687efff413a8a))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/card-processing-service-v0.4.0...card-processing-service-v0.5.0) (2026-10-03)


### Features

* **card-processing:** network-token and dispute-case lifecycle — the callers for the phase-2 ports (ADR-0283 phase 3) ([#8864](https://github.com/JiRaska/open-bank-oss/issues/8864)) ([1851c09](https://github.com/JiRaska/open-bank-oss/commit/1851c09ad884c1778e753ff9c162438da27296a7))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/card-processing-service-v0.3.0...card-processing-service-v0.4.0) (2026-10-03)


### Features

* **card-processing:** tokenisation and dispute bindings (ADR-0283 phase 2) ([#8858](https://github.com/JiRaska/open-bank-oss/issues/8858)) ([dec2efd](https://github.com/JiRaska/open-bank-oss/commit/dec2efdcd047fb7accc0ca0a9f20199bacc4a5b3))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/card-processing-service-v0.2.0...card-processing-service-v0.3.0) (2026-10-03)


### Features

* **card-processing:** Visa and Mastercard BIN adapters behind the capability port (ADR-0283 phase 2) ([#8855](https://github.com/JiRaska/open-bank-oss/issues/8855)) ([fdfd687](https://github.com/JiRaska/open-bank-oss/commit/fdfd6876571a7a38cb520ea67545666cda337148))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/card-processing-service-v0.1.0...card-processing-service-v0.2.0) (2026-10-02)


### Features

* **card-processing:** the card money path — authorisation, hold, clearing, ledger posting (ADR-0283 phase 1) ([#8837](https://github.com/JiRaska/open-bank-oss/issues/8837)) ([6a559c3](https://github.com/JiRaska/open-bank-oss/commit/6a559c3932537248960175c86800c64bb7542337))

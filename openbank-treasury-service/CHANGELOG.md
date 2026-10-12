# Changelog

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/treasury-service-v0.12.0...treasury-service-v0.13.0) (2026-10-09)


### Features

* **libs:** purge SENT outbox rows fleet-wide with one shared retention job ([#11899](https://github.com/JiRaska/open-bank-oss/issues/11899)) ([b322ed7](https://github.com/JiRaska/open-bank-oss/commit/b322ed7116137ecc8f132dc79cd79444c995bb2f))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/treasury-service-v0.11.1...treasury-service-v0.12.0) (2026-10-04)


### Features

* **treasury:** enforce product limits at submit and re-check at approval ([#11997](https://github.com/JiRaska/open-bank-oss/issues/11997)) ([b17185e](https://github.com/JiRaska/open-bank-oss/commit/b17185e44d1ac92709f178ea663ef0cef84d5bdd))


### Bug Fixes

* **docs:** keep every runnable service documentation current ([#11989](https://github.com/JiRaska/open-bank-oss/issues/11989)) ([88b6e08](https://github.com/JiRaska/open-bank-oss/commit/88b6e087386cd885badf22f75cc4694faa0f30bd))


### Security

* **libs,infra:** harden XML/TLS and patch LiteLLM PyJWT ([#12036](https://github.com/JiRaska/open-bank-oss/issues/12036)) ([66a1099](https://github.com/JiRaska/open-bank-oss/commit/66a109909a55ada01edea51509d33c9190ec666f))

## [0.11.1](https://github.com/JiRaska/open-bank-oss/compare/treasury-service-v0.11.0...treasury-service-v0.11.1) (2026-10-03)


### Bug Fixes

* **treasury:** date nostro breaks on the Prague bank day, not the pod's UTC day ([#11822](https://github.com/JiRaska/open-bank-oss/issues/11822)) ([cf8187c](https://github.com/JiRaska/open-bank-oss/commit/cf8187c3ccebaf8edc603dbe9b00f30a8c5a8633))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/treasury-service-v0.10.0...treasury-service-v0.11.0) (2026-10-02)


### Features

* **admin-ui:** make the treasury deal detail readable for a dealer ([#11711](https://github.com/JiRaska/open-bank-oss/issues/11711)) ([b6efd39](https://github.com/JiRaska/open-bank-oss/commit/b6efd395b570f42a68ca561ecb06440e561d03a0))
* **treasury:** age nostro reconciliation breaks, alert on aged ones, accept MT940 ([#11673](https://github.com/JiRaska/open-bank-oss/issues/11673)) ([396fff7](https://github.com/JiRaska/open-bank-oss/commit/396fff740047221b2d820d311648344ec590c65c))


### Bug Fixes

* **treasury:** actual vs projected daily position, Prague day, Czech UI ([#11708](https://github.com/JiRaska/open-bank-oss/issues/11708)) ([c5470b8](https://github.com/JiRaska/open-bank-oss/commit/c5470b871282135b61e77a23408f35fdaf3f28d4))
* **treasury:** take deal business dates in the bank zone, not the JVM's ([#11717](https://github.com/JiRaska/open-bank-oss/issues/11717)) ([923a379](https://github.com/JiRaska/open-bank-oss/commit/923a37993edd585c1920a1e6b53cdd9eb6add788))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/treasury-service-v0.9.0...treasury-service-v0.10.0) (2026-09-30)


### Features

* **treasury:** simulated counterparties quote off the risk engine's curve set ([#11555](https://github.com/JiRaska/open-bank-oss/issues/11555)) ([6933a2b](https://github.com/JiRaska/open-bank-oss/commit/6933a2b9da6ae25cff16ab11c2fe548764fa68b2))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/treasury-service-v0.8.0...treasury-service-v0.9.0) (2026-09-30)


### Features

* **treasury:** reconcile foreign-currency nostros on native ledger balances ([#11113](https://github.com/JiRaska/open-bank-oss/issues/11113)) ([8b87841](https://github.com/JiRaska/open-bank-oss/commit/8b87841d4924961c30559094143a55d0e894ab08))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/treasury-service-v0.7.0...treasury-service-v0.8.0) (2026-09-29)


### Features

* **treasury:** let an AI assistant draft deals it can never book ([#11121](https://github.com/JiRaska/open-bank-oss/issues/11121)) ([41a371c](https://github.com/JiRaska/open-bank-oss/commit/41a371c0952a0ff7555839041e192680e429dea2))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/treasury-service-v0.6.0...treasury-service-v0.7.0) (2026-09-29)


### Features

* **treasury:** book and settle FX spot deals ([#11041](https://github.com/JiRaska/open-bank-oss/issues/11041)) ([6a789fd](https://github.com/JiRaska/open-bank-oss/commit/6a789fdd129205de2c5c7490b46cacec419cb5a6))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/treasury-service-v0.5.0...treasury-service-v0.6.0) (2026-09-29)


### Features

* **treasury:** reconcile nostro accounts against camt.053 statements ([#11052](https://github.com/JiRaska/open-bank-oss/issues/11052)) ([5aa4f37](https://github.com/JiRaska/open-bank-oss/commit/5aa4f3714104ed3815c1c9a67ae14e6972d53bed))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/treasury-service-v0.4.0...treasury-service-v0.5.0) (2026-09-28)


### Features

* **treasury:** show counterparty limit utilisation ([#11036](https://github.com/JiRaska/open-bank-oss/issues/11036)) ([64c0aeb](https://github.com/JiRaska/open-bank-oss/commit/64c0aeb0c1749445075490f3f5758fb15427b492))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/treasury-service-v0.3.0...treasury-service-v0.4.0) (2026-09-27)


### Features

* **treasury:** borrow overnight from the ČNB lombard facility ([#11087](https://github.com/JiRaska/open-bank-oss/issues/11087)) ([7583657](https://github.com/JiRaska/open-bank-oss/commit/75836579e3f5589198dbf89eaac1065be24733de))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/treasury-service-v0.2.0...treasury-service-v0.3.0) (2026-09-26)


### Features

* **treasury:** daily interest accrual on settled deals ([#10992](https://github.com/JiRaska/open-bank-oss/issues/10992)) ([1d42529](https://github.com/JiRaska/open-bank-oss/commit/1d425298e1d9aeaa2943e45e851a8663a6fc54a5)), closes [#10896](https://github.com/JiRaska/open-bank-oss/issues/10896)
* **treasury:** senior override of a counterparty-limit breach ([#10995](https://github.com/JiRaska/open-bank-oss/issues/10995)) ([767d83e](https://github.com/JiRaska/open-bank-oss/commit/767d83e5bbb35aecaebd6e16065f06318ef4fef9))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/treasury-service-v0.1.0...treasury-service-v0.2.0) (2026-09-25)


### Features

* **treasury:** money-market deal service MVP — four-eyes booking, ledger posting, ČNB facility as HQLA (ADR-0315) ([#10872](https://github.com/JiRaska/open-bank-oss/issues/10872)) ([dad4c4b](https://github.com/JiRaska/open-bank-oss/commit/dad4c4b2801b03ca58cf3d50153310e0066d7ad0))

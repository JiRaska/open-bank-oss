# Changelog

## [0.18.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.17.0...risk-engine-v0.18.0) (2026-09-29)


### Features

* **risk-engine:** record who requested each snapshot run ([#11016](https://github.com/JiRaska/open-bank-oss/issues/11016)) ([5acdd48](https://github.com/JiRaska/open-bank-oss/commit/5acdd4825c61fdc729eacf50126970f17adc187d))

## [0.17.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.16.0...risk-engine-v0.17.0) (2026-09-29)


### Features

* **risk-engine:** state the liquidity figures in CZK at the ČNB fixing ([#11431](https://github.com/JiRaska/open-bank-oss/issues/11431)) ([4c55abf](https://github.com/JiRaska/open-bank-oss/commit/4c55abf1b048f2f1ff1d4666f2ae9ad0589de04d)), closes [#10896](https://github.com/JiRaska/open-bank-oss/issues/10896)

## [0.16.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.15.0...risk-engine-v0.16.0) (2026-09-29)


### Features

* **risk-engine:** compute the ČNB minimum reserve requirement ([#11015](https://github.com/JiRaska/open-bank-oss/issues/11015)) ([7a0b9eb](https://github.com/JiRaska/open-bank-oss/commit/7a0b9ebd5e81c531794b2aff4c93ab820524b317))

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.14.0...risk-engine-v0.15.0) (2026-09-27)


### Features

* **risk-engine:** classify ČNB lombard borrowing for LCR and NSFR ([#11096](https://github.com/JiRaska/open-bank-oss/issues/11096)) ([febc810](https://github.com/JiRaska/open-bank-oss/commit/febc81016dabd0400cae220d7806e38c5bf25367))

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.13.0...risk-engine-v0.14.0) (2026-09-27)


### Features

* **risk-engine:** state the capital total in CZK at the ČNB fixing ([#11167](https://github.com/JiRaska/open-bank-oss/issues/11167)) ([9442f1d](https://github.com/JiRaska/open-bank-oss/commit/9442f1d8531787d1bb276ee446cf412a56dd8f28))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.12.0...risk-engine-v0.13.0) (2026-09-27)


### Features

* **treasury:** borrow overnight from the ČNB lombard facility ([#11087](https://github.com/JiRaska/open-bank-oss/issues/11087)) ([7583657](https://github.com/JiRaska/open-bank-oss/commit/75836579e3f5589198dbf89eaac1065be24733de))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.11.0...risk-engine-v0.12.0) (2026-09-27)


### Features

* **finrep:** COREP C 02.00 own funds requirements from the risk engine ([#11001](https://github.com/JiRaska/open-bank-oss/issues/11001)) ([1bd04df](https://github.com/JiRaska/open-bank-oss/commit/1bd04df13614c8d145dfb116f398d8340bbde461))
* **risk-engine:** apply EU LCR/NSFR rules as the default liquidity parameter set ([#11005](https://github.com/JiRaska/open-bank-oss/issues/11005)) ([52d23ec](https://github.com/JiRaska/open-bank-oss/commit/52d23ec495fd0e519bb47ed7e693bf000e4a9944)), closes [#10860](https://github.com/JiRaska/open-bank-oss/issues/10860) [#10896](https://github.com/JiRaska/open-bank-oss/issues/10896)
* **risk-engine:** money-market deals as snapshot instruments from treasury events ([#10998](https://github.com/JiRaska/open-bank-oss/issues/10998)) ([1e0a9ea](https://github.com/JiRaska/open-bank-oss/commit/1e0a9ea8cb538d47c6ceda77e8efdbb07e209051))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.10.0...risk-engine-v0.11.0) (2026-09-26)


### Features

* **risk-engine:** IRRBB CZK shocks and post-shock floor from Delegated Regulation 2024/856 ([#10970](https://github.com/JiRaska/open-bank-oss/issues/10970)) ([d02b5ec](https://github.com/JiRaska/open-bank-oss/commit/d02b5ecd029030cd76533ea3ea20da0f0d6aa1d1))
* **risk-engine:** Pillar 1 credit-risk capital, standardised approach (BCBS d424), with an admin-ui page (ADR-0313 phase 2) ([#10900](https://github.com/JiRaska/open-bank-oss/issues/10900)) ([67ae139](https://github.com/JiRaska/open-bank-oss/commit/67ae1397d549e5000d05dce095ba45658aea458b))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.9.0...risk-engine-v0.10.0) (2026-09-25)


### Features

* **treasury:** money-market deal service MVP — four-eyes booking, ledger posting, ČNB facility as HQLA (ADR-0315) ([#10872](https://github.com/JiRaska/open-bank-oss/issues/10872)) ([dad4c4b](https://github.com/JiRaska/open-bank-oss/commit/dad4c4b2801b03ca58cf3d50153310e0066d7ad0))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.8.0...risk-engine-v0.9.0) (2026-09-25)


### Features

* **risk-engine:** LCR and NSFR on BCBS factors, with an admin-ui page (ADR-0313 phase 1) ([#10860](https://github.com/JiRaska/open-bank-oss/issues/10860)) ([6998a83](https://github.com/JiRaska/open-bank-oss/commit/6998a831635be984d661da2cb89f4aa9c5f469dd))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.7.0...risk-engine-v0.8.0) (2026-09-25)


### Features

* **risk-engine:** IRRBB — repricing gap, supervisory shocks, ΔEVE/ΔNII, with an admin-ui page (ADR-0313 phase 1) ([#10853](https://github.com/JiRaska/open-bank-oss/issues/10853)) ([5f5518f](https://github.com/JiRaska/open-bank-oss/commit/5f5518faefaf99fc041a06a0aed5c807b512bf64))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.6.0...risk-engine-v0.7.0) (2026-09-25)


### Features

* **admin-ui:** balance sheet & risk workspace, with the four-eyes ledger backfill ([#10618](https://github.com/JiRaska/open-bank-oss/issues/10618)) ([#10842](https://github.com/JiRaska/open-bank-oss/issues/10842)) ([5499e3a](https://github.com/JiRaska/open-bank-oss/commit/5499e3a11cd22f7b0c0e00b987b7622c935ffb7f))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.5.0...risk-engine-v0.6.0) (2026-09-25)


### Features

* **infra:** risk, finance and treasury department roles ([#10618](https://github.com/JiRaska/open-bank-oss/issues/10618)) ([#10833](https://github.com/JiRaska/open-bank-oss/issues/10833)) ([43a94a2](https://github.com/JiRaska/open-bank-oss/commit/43a94a2cfb0267d53c7573a33b592df9b474d77c))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.4.0...risk-engine-v0.5.0) (2026-09-24)


### Features

* **risk-engine:** read lending's loan book over mTLS in the sandbox ([#10741](https://github.com/JiRaska/open-bank-oss/issues/10741)) ([2bb2dc0](https://github.com/JiRaska/open-bank-oss/commit/2bb2dc03564a7d8827ca2c39a5667730b13a310b)), closes [#10618](https://github.com/JiRaska/open-bank-oss/issues/10618)

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.3.0...risk-engine-v0.4.0) (2026-09-24)


### Features

* **risk-engine:** loans as contract-level instruments, with a read-only lending loan book (ADR-0314 D4) ([#10729](https://github.com/JiRaska/open-bank-oss/issues/10729)) ([5cee641](https://github.com/JiRaska/open-bank-oss/commit/5cee641635fcbd0d3cd1a8bada5021bdbcd60859))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.2.0...risk-engine-v0.3.0) (2026-09-24)


### Features

* **risk-engine:** yield curves and the cash-flow engine (ADR-0314 D6) ([#10716](https://github.com/JiRaska/open-bank-oss/issues/10716)) ([21f26f9](https://github.com/JiRaska/open-bank-oss/commit/21f26f9ddd4a40cc2184276f21efbf7b1914dcb4))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.1.0...risk-engine-v0.2.0) (2026-09-24)


### Features

* **risk-engine:** bootstrap the balance-sheet snapshot service (ADR-0314) ([#10708](https://github.com/JiRaska/open-bank-oss/issues/10708)) ([8ddd024](https://github.com/JiRaska/open-bank-oss/commit/8ddd024741bdc47e7356f904de1b214de6eb5e1d))

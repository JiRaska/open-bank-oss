# Changelog

## [0.27.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.26.0...risk-engine-v0.27.0) (2026-10-10)


### Features

* **risk-engine:** keep limit-event dedup in its own table so risk_outbox can purge SENT rows ([#11905](https://github.com/JiRaska/open-bank-oss/issues/11905)) ([f35e14d](https://github.com/JiRaska/open-bank-oss/commit/f35e14d2991e681cfa159cf037e3245b03d61b2e))

## [0.26.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.25.0...risk-engine-v0.26.0) (2026-10-05)


### Features

* **fx:** ingest ČNB policy rates and minimum-reserve facts ([#12117](https://github.com/JiRaska/open-bank-oss/issues/12117)) ([35b73e9](https://github.com/JiRaska/open-bank-oss/commit/35b73e94db9d92265755e528cf0703899cb6d343))

## [0.25.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.24.0...risk-engine-v0.25.0) (2026-10-04)


### Features

* **risk-engine:** classify residual GLs for liquidity so LCR/NSFR are evaluable ([#12067](https://github.com/JiRaska/open-bank-oss/issues/12067)) ([4216eed](https://github.com/JiRaska/open-bank-oss/commit/4216eeddb92c50bfa4801a43d9c8a463cde2c9ec))
* **risk-engine:** IRRBB data gaps, default curve set and limit-based outlier test, with an admin-ui panel ([#12032](https://github.com/JiRaska/open-bank-oss/issues/12032)) ([6cf7d47](https://github.com/JiRaska/open-bank-oss/commit/6cf7d479c2733a54ec6036388e465f9430b134b9))
* **risk-engine:** project treasury money-market deals into IRRBB gap, EVE and NII ([#12051](https://github.com/JiRaska/open-bank-oss/issues/12051)) ([8f4633a](https://github.com/JiRaska/open-bank-oss/commit/8f4633a6cfb93a4422fb9a749d2fcf9f670a8dde))


### Bug Fixes

* **docs:** keep every runnable service documentation current ([#11989](https://github.com/JiRaska/open-bank-oss/issues/11989)) ([88b6e08](https://github.com/JiRaska/open-bank-oss/commit/88b6e087386cd885badf22f75cc4694faa0f30bd))
* **risk-engine:** classify nostro credit balances and pin the loan-book tie-out ([#12062](https://github.com/JiRaska/open-bank-oss/issues/12062)) ([77eb114](https://github.com/JiRaska/open-bank-oss/commit/77eb114ffeabd564ca9860f39b8cb2cac0eb7885))

## [0.24.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.23.1...risk-engine-v0.24.0) (2026-10-03)


### Features

* **risk-engine:** sandbox reference curve sets, CZK IRRBB aggregate, usable IRRBB/forecast pages ([#11719](https://github.com/JiRaska/open-bank-oss/issues/11719)) ([9cfbc7a](https://github.com/JiRaska/open-bank-oss/commit/9cfbc7a882d7de062c165977e239dc09c343c6d9))

## [0.23.1](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.23.0...risk-engine-v0.23.1) (2026-10-02)


### Bug Fixes

* **risk-engine:** reject a snapshot as-of after the current business date ([#11718](https://github.com/JiRaska/open-bank-oss/issues/11718)) ([a56389b](https://github.com/JiRaska/open-bank-oss/commit/a56389b05fd559348f5717c3a657d6feb1e0ec6c))

## [0.23.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.22.0...risk-engine-v0.23.0) (2026-10-02)


### Features

* **lending,risk-engine,admin-ui:** immutable human loan contract number ([#11722](https://github.com/JiRaska/open-bank-oss/issues/11722)) ([f19ab46](https://github.com/JiRaska/open-bank-oss/commit/f19ab4629b01673c29c8d4c1f1f8b01a6db89915))
* **risk-engine,admin-ui:** read liquidity and capital by category, not by raw loan id ([#11716](https://github.com/JiRaska/open-bank-oss/issues/11716)) ([e93fa1d](https://github.com/JiRaska/open-bank-oss/commit/e93fa1dffd2fe045fb638f0236361d2807cb1573))
* **risk-engine:** average minimum-reserve holdings over the maintenance period ([#11546](https://github.com/JiRaska/open-bank-oss/issues/11546)) ([67af0c5](https://github.com/JiRaska/open-bank-oss/commit/67af0c5533ef2ea3c4e1540818e5be2fcb078498))
* **risk-engine:** evaluate declarative risk limits on every snapshot ([#11549](https://github.com/JiRaska/open-bank-oss/issues/11549)) ([c46453f](https://github.com/JiRaska/open-bank-oss/commit/c46453fd72d565e4b9aa8e56d2caba18cb08ca00))
* **treasury:** age nostro reconciliation breaks, alert on aged ones, accept MT940 ([#11673](https://github.com/JiRaska/open-bank-oss/issues/11673)) ([396fff7](https://github.com/JiRaska/open-bank-oss/commit/396fff740047221b2d820d311648344ec590c65c))

## [0.22.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.21.0...risk-engine-v0.22.0) (2026-09-30)


### Features

* **risk-engine:** record model versions and cut-off in the snapshot manifest ([#11539](https://github.com/JiRaska/open-bank-oss/issues/11539)) ([58bf6aa](https://github.com/JiRaska/open-bank-oss/commit/58bf6aaa557aa72f792517db47ac39caa6dca58e))
* **treasury:** simulated counterparties quote off the risk engine's curve set ([#11555](https://github.com/JiRaska/open-bank-oss/issues/11555)) ([6933a2b](https://github.com/JiRaska/open-bank-oss/commit/6933a2b9da6ae25cff16ab11c2fe548764fa68b2))

## [0.21.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.20.0...risk-engine-v0.21.0) (2026-09-29)


### Features

* **risk-engine:** compute Pillar 1 credit risk under EU CRR by default ([#11496](https://github.com/JiRaska/open-bank-oss/issues/11496)) ([c6a02ac](https://github.com/JiRaska/open-bank-oss/commit/c6a02ac30783ac45aa92566ff2a34d515016c978))


### Bug Fixes

* **risk-engine:** count eligible maturing placements in LCR ([#11102](https://github.com/JiRaska/open-bank-oss/issues/11102)) ([a2c0d90](https://github.com/JiRaska/open-bank-oss/commit/a2c0d9035819ed69bdb15792818d78bce6b0f53e))

## [0.20.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.19.0...risk-engine-v0.20.0) (2026-09-29)


### Features

* **risk-engine:** forecast the liquidity survival horizon from a snapshot ([#11040](https://github.com/JiRaska/open-bank-oss/issues/11040)) ([44cbf58](https://github.com/JiRaska/open-bank-oss/commit/44cbf58b03a562ad31fd0ae6e1697a80f8c9b6f1))

## [0.19.0](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.18.1...risk-engine-v0.19.0) (2026-09-29)


### Features

* **risk-engine:** classify clearing, allowance and FX position accounts conservatively ([#11481](https://github.com/JiRaska/open-bank-oss/issues/11481)) ([41cf055](https://github.com/JiRaska/open-bank-oss/commit/41cf05533de16bb565967218c5289378e22f0492))

## [0.18.1](https://github.com/JiRaska/open-bank-oss/compare/risk-engine-v0.18.0...risk-engine-v0.18.1) (2026-09-29)


### Security

* **fleet:** scope OIDC TLS verification=none to %dev and gate it ([#10870](https://github.com/JiRaska/open-bank-oss/issues/10870)) ([cf7aa55](https://github.com/JiRaska/open-bank-oss/commit/cf7aa5528809917ccb7e4a94d80f25b2d136c7b4))

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

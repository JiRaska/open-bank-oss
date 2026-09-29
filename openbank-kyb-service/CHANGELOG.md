# Changelog

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/kyb-service-v0.8.0...kyb-service-v0.8.1) (2026-09-23)


### Security

* **aml:** own M2M identities for the remaining writers ([#10486](https://github.com/JiRaska/open-bank-oss/issues/10486) batch 3) ([#10540](https://github.com/JiRaska/open-bank-oss/issues/10540)) ([b6c829a](https://github.com/JiRaska/open-bank-oss/commit/b6c829a5e2ddcc41c62ad4aac9c58d5b481c676f))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/kyb-service-v0.7.0...kyb-service-v0.8.0) (2026-09-19)


### Features

* **kyb:** give the sandbox demo company a second jednatel acting jointly ([#10349](https://github.com/JiRaska/open-bank-oss/issues/10349)) ([3ab9e43](https://github.com/JiRaska/open-bank-oss/commit/3ab9e43e793370c156828553f3c0b73422edfa6a)), closes [#10281](https://github.com/JiRaska/open-bank-oss/issues/10281)

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/kyb-service-v0.6.0...kyb-service-v0.7.0) (2026-09-17)


### Features

* **kyb:** preserve PSC source evidence and completeness ([#10242](https://github.com/JiRaska/open-bank-oss/issues/10242)) ([ab45286](https://github.com/JiRaska/open-bank-oss/commit/ab452866569c13cc5c2facb0922cc986d570e209))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/kyb-service-v0.5.0...kyb-service-v0.6.0) (2026-09-17)


### Features

* **kyb:** collect AML answers and sign the business agreement with SCA ([#10199](https://github.com/JiRaska/open-bank-oss/issues/10199)) ([8d5a644](https://github.com/JiRaska/open-bank-oss/commit/8d5a64401ffcd80980d9b759c1d21ec9eaa97e97))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/kyb-service-v0.4.0...kyb-service-v0.5.0) (2026-09-17)


### Features

* **kyb:** confirm a single-member representation rule automatically ([#10169](https://github.com/JiRaska/open-bank-oss/issues/10169)) ([10ded65](https://github.com/JiRaska/open-bank-oss/commit/10ded65f3e33a5e6c4bdf914e20c2c8b6d454bc5)), closes [#9711](https://github.com/JiRaska/open-bank-oss/issues/9711)

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/kyb-service-v0.3.0...kyb-service-v0.4.0) (2026-09-14)


### Features

* **kyb:** bind the initiator to their verified identity, and add a sandbox demo company ([#10059](https://github.com/JiRaska/open-bank-oss/issues/10059)) ([545c744](https://github.com/JiRaska/open-bank-oss/commit/545c744be77662f22be40a6e7c392e2f9a2529d9))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/kyb-service-v0.2.0...kyb-service-v0.3.0) (2026-09-13)


### Features

* **kyb:** a human confirms the representation rule, per IČO — no agreement bound by a parser ([#9710](https://github.com/JiRaska/open-bank-oss/issues/9710)) ([995fa19](https://github.com/JiRaska/open-bank-oss/commit/995fa19ea1c1246407047eb16eb64a287b470a53))
* **kyb:** find a company by name and town, not only by ICO ([#9708](https://github.com/JiRaska/open-bank-oss/issues/9708)) ([8430d6d](https://github.com/JiRaska/open-bank-oss/commit/8430d6d2844af4897bab1bc87e95197e88e9094d))


### Bug Fixes

* **kyb:** guard kyb_outbox created_at plausibility at INSERT ([#9313](https://github.com/JiRaska/open-bank-oss/issues/9313)) ([6c86bdd](https://github.com/JiRaska/open-bank-oss/commit/6c86bdd553a3b8bc034e5aecdabe92456a8f396d))
* **kyb:** publish requiredSignerRoles, the last request-schema gap in the fleet ([#9886](https://github.com/JiRaska/open-bank-oss/issues/9886)) ([b15ed77](https://github.com/JiRaska/open-bank-oss/commit/b15ed77913531afbf79d66906861849959c2affe)), closes [#8828](https://github.com/JiRaska/open-bank-oss/issues/8828)
* **kyb:** unparseable declared legal form class throws, never silently OTHER ([#9415](https://github.com/JiRaska/open-bank-oss/issues/9415)) ([bcb405d](https://github.com/JiRaska/open-bank-oss/commit/bcb405de4bc679e3363117e4de149af84b0dc8e1))
* **party:** preserve statutory signature quorum ([#9391](https://github.com/JiRaska/open-bank-oss/issues/9391)) ([4a79a3b](https://github.com/JiRaska/open-bank-oss/commit/4a79a3b0d71fed27ae117600d8ab4af15ef11a80))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/kyb-service-v0.1.0...kyb-service-v0.2.0) (2026-09-08)


### Features

* **kyb:** GB country pack, Companies House register and the UK PSC beneficial-ownership adapter ([#8879](https://github.com/JiRaska/open-bank-oss/issues/8879)) ([b353765](https://github.com/JiRaska/open-bank-oss/commit/b353765fb807e8153fb97d6d708fdd59ee733cc4))
* **kyb:** legal-entity onboarding, representation mandates and profile switching (ADR-0284) ([#8863](https://github.com/JiRaska/open-bank-oss/issues/8863)) ([3766d3d](https://github.com/JiRaska/open-bank-oss/commit/3766d3de2281dbeb17e0b7a6a4e6c754988d1145))


### Bug Fixes

* **kyb:** unknown case-status filter is a 400, not the MANUAL_REVIEW list ([#9040](https://github.com/JiRaska/open-bank-oss/issues/9040)) ([3cccbff](https://github.com/JiRaska/open-bank-oss/commit/3cccbff94010540e37c73bf5524168371b46ca30))

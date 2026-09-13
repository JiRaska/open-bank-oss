# Changelog

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

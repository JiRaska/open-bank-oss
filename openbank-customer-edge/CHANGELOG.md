# Changelog

## [0.15.1](https://github.com/JiRaska/open-bank/compare/customer-edge-v0.15.0...customer-edge-v0.15.1) (2026-06-29)


### Bug Fixes

* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank/issues/2501)) ([7306f85](https://github.com/JiRaska/open-bank/commit/7306f85891b7c9f440a8c376bf741523d6eef498))
* **customer-edge:** per-party Valkey rate limit — 100 req/min, configurable ([#2492](https://github.com/JiRaska/open-bank/issues/2492)) ([fc1e8f6](https://github.com/JiRaska/open-bank/commit/fc1e8f6e42e8421e29b4f7d26ef6fe4bf41d469d))

## [0.15.0](https://github.com/JiRaska/open-bank/compare/customer-edge-v0.14.2...customer-edge-v0.15.0) (2026-06-27)


### Features

* **observability:** RUM gateway cardinality budget, attribute audit, HPA (ADR-0088 O1-O3) ([#2208](https://github.com/JiRaska/open-bank/issues/2208)) ([b7f0849](https://github.com/JiRaska/open-bank/commit/b7f08494cf923f66dbd910e92522fb9453394de7))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.14.2](https://github.com/JiRaska/open-bank/compare/customer-edge-v0.14.1...customer-edge-v0.14.2) (2026-06-25)


### Bug Fixes

* **sepa-instant,balance,audit,security-scanner,copilot,customer-edge,sca:** inject Clock via CDI (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2145](https://github.com/JiRaska/open-bank/issues/2145)) ([d680007](https://github.com/JiRaska/open-bank/commit/d68000775625cc423c95d8a27db29ff25a708f9f))

## [0.14.1](https://github.com/JiRaska/open-bank/compare/customer-edge-v0.14.0...customer-edge-v0.14.1) (2026-06-25)


### Bug Fixes

* **detekt:** suppress LongMethod/CyclomaticComplexMethod on createDomesticPayment; fix MagicNumber ([#2036](https://github.com/JiRaska/open-bank/issues/2036)) ([3612651](https://github.com/JiRaska/open-bank/commit/36126511ffbf0329f9e5762785df885b6186c173))

## [0.14.0](https://github.com/JiRaska/open-bank/compare/customer-edge-v0.13.0...customer-edge-v0.14.0) (2026-06-25)


### Features

* **domestic-payment,customer-edge:** add INSTANT priority; forward priority from app body ([e683832](https://github.com/JiRaska/open-bank/commit/e683832c0f71a69531d2a8e53bbca94da22b2749))
* **domestic-payment,customer-edge:** add INSTANT priority; forward priority from app body ([9fddff9](https://github.com/JiRaska/open-bank/commit/9fddff995d15fe94b6db4ae9eb05732a99938cff))
* **nearbypay:** resolve real creditor server-side in domestic payment (ADR-0087) ([#2020](https://github.com/JiRaska/open-bank/issues/2020)) ([936c121](https://github.com/JiRaska/open-bank/commit/936c1213381384b085b970488a346b6876c2d35f))


### Bug Fixes

* **customer-edge:** remove DIAG logs from SCA decision endpoint ([#2022](https://github.com/JiRaska/open-bank/issues/2022)) ([5f2f646](https://github.com/JiRaska/open-bank/commit/5f2f646189b5e21c16f0c9f1263132e3627ef426))


### Security

* **infra:** Kafka mTLS + write-ACL for payment.scheme-accepted (Closes [#2013](https://github.com/JiRaska/open-bank/issues/2013)) ([#2018](https://github.com/JiRaska/open-bank/issues/2018)) ([0bcfdf1](https://github.com/JiRaska/open-bank/commit/0bcfdf1092d07ba975b7f5aa42793a9a82a6edac))

## [0.13.0](https://github.com/JiRaska/open-bank/compare/customer-edge-v0.12.0...customer-edge-v0.13.0) (2026-06-25)


### Features

* **customer-edge:** add @Authorize resource-gate on REST endpoints (ADR-0034 D3) ([#1357](https://github.com/JiRaska/open-bank/issues/1357)) ([17ce859](https://github.com/JiRaska/open-bank/commit/17ce859caaf99969aa15c591251d2c2a20165a58))
* **customer-edge:** ADR-0104 P1 — expose currency-pocket lifecycle to customers ([#1683](https://github.com/JiRaska/open-bank/issues/1683)) ([24b3530](https://github.com/JiRaska/open-bank/commit/24b35308f61cd2dfdc5ac4a6f040955a5abf237a))
* **customer-edge:** onboarding auto-resume on four-eyes decision (ADR-0072) ([#1563](https://github.com/JiRaska/open-bank/issues/1563)) ([be42ad4](https://github.com/JiRaska/open-bank/commit/be42ad4e9f7e147a7dadd631a0887382da91cae5))
* **customer-edge:** same-account currency exchange endpoint (ADR-0108) ([#1830](https://github.com/JiRaska/open-bank/issues/1830)) ([c41f0a8](https://github.com/JiRaska/open-bank/commit/c41f0a8e091c1a99cd67f7e89240b6cdbb87f42b))
* **customer-edge:** set Keycloak party_id attribute after MATCH_EXISTING re-link (issue [#1270](https://github.com/JiRaska/open-bank/issues/1270) PR3) ([7c7396a](https://github.com/JiRaska/open-bank/commit/7c7396aff7960a0998a923da8409b25c8b10e2e5))
* **infra:** OPA namespace deployment + docker-compose REST policy wiring (ADR-0034 D4) ([#1355](https://github.com/JiRaska/open-bank/issues/1355)) ([dd7022b](https://github.com/JiRaska/open-bank/commit/dd7022be9bdcd77df33a84bb4679442960ef8598))
* **libs:** flip authz.enforce to true for non-money-path services (ADR-0034 D5) ([#1365](https://github.com/JiRaska/open-bank/issues/1365)) ([6a4df3d](https://github.com/JiRaska/open-bank/commit/6a4df3d763b026f66c683b161e1160d22a2a89e6))
* **pockets:** convert pocket balance to primary currency (ADR-0107) ([#1797](https://github.com/JiRaska/open-bank/issues/1797)) ([c3df994](https://github.com/JiRaska/open-bank/commit/c3df99430d6976555b052fefd78186f3b55de795))


### Bug Fixes

* **customer-edge:** accept Czech IBAN as creditor in domestic payment ([#1942](https://github.com/JiRaska/open-bank/issues/1942)) ([57ff658](https://github.com/JiRaska/open-bank/commit/57ff658a7b8982ee2ea545d4685f97be3907b063))
* **customer-edge:** accept JsonNode body in initiateChallenge to fix empty-body 400 ([462ab2a](https://github.com/JiRaska/open-bank/commit/462ab2adb67406b0aaeba0bbbe0054d20cbe7c1d))
* **customer-edge:** fix SCA challenge 400 — switch body from String to JsonNode ([213e577](https://github.com/JiRaska/open-bank/commit/213e577b9fb53a83f2fe8a28d294c5380381c0bf))
* **customer-edge:** move /onboarding/register to OnboardingResource (JAX-RS path conflict) ([#1858](https://github.com/JiRaska/open-bank/issues/1858)) ([71c051b](https://github.com/JiRaska/open-bank/commit/71c051b6da59f55cfe32f89926eebc3b7da0801d))
* **customer-edge:** onboarding /start input validation — Jackson parsing + partyType + legalName length (pentest [#628](https://github.com/JiRaska/open-bank/issues/628)) ([6fdafed](https://github.com/JiRaska/open-bank/commit/6fdafed427a844de081cbec12ba16681dab771a9))
* **customer-edge:** registerParty syntax fix — onboarding/register endpoint ([#1861](https://github.com/JiRaska/open-bank/issues/1861)) ([cccf00c](https://github.com/JiRaska/open-bank/commit/cccf00c4e3e82b29023dc45d9500b23f17a58d8c))
* **customer-edge:** replace substringAfter JSON parsing with Jackson (pentest [#628](https://github.com/JiRaska/open-bank/issues/628)) ([#1421](https://github.com/JiRaska/open-bank/issues/1421)) ([b68f074](https://github.com/JiRaska/open-bank/commit/b68f0745e62c4bc916dcf271abe73481a8f91aed))
* **customer-edge:** route /onboarding/register via OnboardingResource delegate ([#1863](https://github.com/JiRaska/open-bank/issues/1863)) ([7b01355](https://github.com/JiRaska/open-bank/commit/7b01355fdb0bc270323504e53480707c434cc4e0))
* **infra:** harden registry-cache + gradle-build-cache pod specs, enforce PSS restricted ([#853](https://github.com/JiRaska/open-bank/issues/853)) ([#1551](https://github.com/JiRaska/open-bank/issues/1551)) ([002f8e1](https://github.com/JiRaska/open-bank/commit/002f8e123a4fc1ce423ec49ed00324dd93dcdd04))

## [0.12.0](https://github.com/JiRaska/open-bank/compare/customer-edge-v0.11.0...customer-edge-v0.12.0) (2026-06-15)


### Features

* **customer-edge:** enrich FX rate sheet with ČNB reference mid + spread % ([#1063](https://github.com/JiRaska/open-bank/issues/1063)) ([4b285ab](https://github.com/JiRaska/open-bank/commit/4b285ab5b4b7eda443a4ba2b6f2360ca94fbd5bd))
* **customer-edge:** GET /customer/v1/banks — CNB bank-code registry proxy ([#1096](https://github.com/JiRaska/open-bank/issues/1096)) ([a2de025](https://github.com/JiRaska/open-bank/commit/a2de0251dd96c6929f11ac6e7ecb76e8d6ec16d1))
* **customer-edge:** GET /customer/v1/fx/rates/{base}/{quote}/history ([#1080](https://github.com/JiRaska/open-bank/issues/1080)) ([6a8fe20](https://github.com/JiRaska/open-bank/commit/6a8fe20d91deaffb37e705b1fdb3e9ebb97609cf))


### Bug Fixes

* **dispute:** assign unique HTTP port 8135 (resolve collision with lending) ([#1045](https://github.com/JiRaska/open-bank/issues/1045)) ([b219e93](https://github.com/JiRaska/open-bank/commit/b219e93ac044ee2d7c4b8e352bbba17265c624dc))
* **fx,customer-edge:** FX history prázdná + chybí ECB odchylka ([#1115](https://github.com/JiRaska/open-bank/issues/1115)) ([2a5d872](https://github.com/JiRaska/open-bank/commit/2a5d872c9e3f68be2afa6860ae7a3b363ac43908))
* **statement:** assign unique HTTP port 8136 (resolve collision with customer-edge) ([#1046](https://github.com/JiRaska/open-bank/issues/1046)) ([d9aa9c6](https://github.com/JiRaska/open-bank/commit/d9aa9c6538ab767118de226fddb6cfc2d0eafb53))

## [0.11.0](https://github.com/JiRaska/open-bank/compare/customer-edge-v0.10.0...customer-edge-v0.11.0) (2026-06-14)


### Features

* **customer-edge:** expose published FX rate sheet to the customer app ([#1004](https://github.com/JiRaska/open-bank/issues/1004)) ([d4f4abe](https://github.com/JiRaska/open-bank/commit/d4f4abe81bab39ffa4c10ca841ed5129be50f26a))
* **customer-edge:** FX, cards, disputes, nearby-pay + card-issuance deploy ([#955](https://github.com/JiRaska/open-bank/issues/955)) ([921c657](https://github.com/JiRaska/open-bank/commit/921c65707991a1a101f4571e5110f3bbc28a3570))
* **onboarding:** self-service E2E — savings account, sub-bound party, own-account transfers ([#894](https://github.com/JiRaska/open-bank/issues/894)) ([91dd603](https://github.com/JiRaska/open-bank/commit/91dd603e62f8f9038a0c71d1a0187ce0303442ce))
* **security:** customer payment non-repudiation — SCA settlement gate, identity threading, audit hash chain (ADR-0086) ([#900](https://github.com/JiRaska/open-bank/issues/900)) ([fcc1e52](https://github.com/JiRaska/open-bank/commit/fcc1e52b247b0eb61b9ee8d5332f110984a6fb33))
* standing orders + open additional account (customer self-service) ([#906](https://github.com/JiRaska/open-bank/issues/906)) ([9a6c07a](https://github.com/JiRaska/open-bank/commit/9a6c07aa51f7077d4e2660a0bad44bdaa39b27de))


### Bug Fixes

* **customer-edge:** expose /q/metrics via micrometer prometheus registry ([#991](https://github.com/JiRaska/open-bank/issues/991)) ([203e05f](https://github.com/JiRaska/open-bank/commit/203e05f9c2c2c15fa78d610a88768c6ff7ca7ac2))

## [0.10.0](https://github.com/JiRaska/open-bank/compare/customer-edge-v0.9.1...customer-edge-v0.10.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.9.1](https://github.com/JiRaska/open-bank/compare/customer-edge-v0.9.0...customer-edge-v0.9.1) (2026-06-10)


### Security

* **customer-edge:** enforce account ownership on account & balance reads (IDOR) ([#627](https://github.com/JiRaska/open-bank/issues/627)) ([0eca1f6](https://github.com/JiRaska/open-bank/commit/0eca1f689defb0aacc169fc4402b26fd0969c876))
* **customer-edge:** parse party status with Jackson in the KYC gate ([#633](https://github.com/JiRaska/open-bank/issues/633)) ([8956117](https://github.com/JiRaska/open-bank/commit/89561171ce786c840066d47923cda82d2431e1f6))
* **customer-edge:** pin OIDC token issuer instead of accepting any ([#621](https://github.com/JiRaska/open-bank/issues/621)) ([2d187d3](https://github.com/JiRaska/open-bank/commit/2d187d330d57a8def3ea77344c4d93a0afa550ca))

## [0.9.0](https://github.com/JiRaska/open-bank/compare/customer-edge-v0.8.1...customer-edge-v0.9.0) (2026-06-08)


### Features

* **account:** welcome-bonus notification + edge notification feed ([#565](https://github.com/JiRaska/open-bank/issues/565)) ([a7c8d8f](https://github.com/JiRaska/open-bank/commit/a7c8d8ff3e6b43c792727fb4e6eb71b1608def52))
* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **customer-edge:** add onboarding routes + party_id JWT claim fix (ADR-0069) ([f3d345b](https://github.com/JiRaska/open-bank/commit/f3d345b0b88d14fee2956a2ae9109dee89961ff0))
* **customer-edge:** customer-facing edge proxy + gitops (ADR-0065) ([#403](https://github.com/JiRaska/open-bank/issues/403)) ([389e3c6](https://github.com/JiRaska/open-bank/commit/389e3c6a258900cbccfd9569f435fe61afa8c26f))
* **customer-edge:** Dockerfile + openbank-edge service account in operator realm ([#404](https://github.com/JiRaska/open-bank/issues/404)) ([0bf2e73](https://github.com/JiRaska/open-bank/commit/0bf2e731d77f4d5d5fbe937975bb90f143868c97))
* **customer-edge:** enrich customer domestic payment into full instruction ([#588](https://github.com/JiRaska/open-bank/issues/588)) ([09b99c0](https://github.com/JiRaska/open-bank/commit/09b99c0c4fa3a566b68f851a9b76da67c52156f1))
* **customer-edge:** expose customer transaction history (ownership-enforced) ([#564](https://github.com/JiRaska/open-bank/issues/564)) ([a6c7ae3](https://github.com/JiRaska/open-bank/commit/a6c7ae3771abc617f6c2de2d08196baeff883298))
* **customer-edge:** expose the caller's own customer profile ([#572](https://github.com/JiRaska/open-bank/issues/572)) ([9fecee6](https://github.com/JiRaska/open-bank/commit/9fecee60eb404b12e8bc10abf8e6724f705cdb49))
* **customer-edge:** payment initiation + SCA challenge routes (3a, no settlement) ([#569](https://github.com/JiRaska/open-bank/issues/569)) ([ecb82aa](https://github.com/JiRaska/open-bank/commit/ecb82aa4773aa8341f8c5768b6350bcc41c162b7))
* **customer-edge:** proxy + enrich SEPA credit-transfer initiation ([#614](https://github.com/JiRaska/open-bank/issues/614)) ([4d14d3d](https://github.com/JiRaska/open-bank/commit/4d14d3d1d8c2c04a82a369904947d859b246e267))
* **customer-edge:** proxy on-demand statement document render ([#584](https://github.com/JiRaska/open-bank/issues/584)) ([8b2549d](https://github.com/JiRaska/open-bank/commit/8b2549de3609b3de2bc3ca6ac0045dfdb7b0cfe8))
* **notification:** PUSH delivery via FCM/APNs + device token registry ([#535](https://github.com/JiRaska/open-bank/issues/535)) ([73c4ebd](https://github.com/JiRaska/open-bank/commit/73c4ebdac4dc96fffc6c60823df00d88a07f1c78))
* **statements:** customer statement list — M2M auth fix + edge route ([#574](https://github.com/JiRaska/open-bank/issues/574)) ([05d81d3](https://github.com/JiRaska/open-bank/commit/05d81d3400dde07a0f38a4965cef3699a5aed493))


### Bug Fixes

* **customer-edge:** bump version 0.1.0 → 0.1.1 after singleton HttpClient fix ([#476](https://github.com/JiRaska/open-bank/issues/476)) ([976718e](https://github.com/JiRaska/open-bank/commit/976718e08dd0992c328e8126bd116cd56277b5a7))
* **customer-edge:** complete onboarding wiring — field-inject M2M secret, idempotency-key, HTTP/1.1 ([#499](https://github.com/JiRaska/open-bank/issues/499)) ([44af3cc](https://github.com/JiRaska/open-bank/commit/44af3cca81fe0d421e5fd3450920aa4924eb3a3d))
* **customer-edge:** force HTTP/1.1 upstream + getEntity body read (onboarding 502) ([#496](https://github.com/JiRaska/open-bank/issues/496)) ([c2eca1f](https://github.com/JiRaska/open-bank/commit/c2eca1f3fb4f047b48c6a9dd054ace1c771eb4e3))
* **customer-edge:** inject partyId via Jackson on device + onboarding routes ([#571](https://github.com/JiRaska/open-bank/issues/571)) ([9630670](https://github.com/JiRaska/open-bank/commit/963067045cb2fc5309a6af8c5548accc51f7d141))
* **customer-edge:** make @PermitAll onboarding routes truly public (lazy auth) + deploy ([#494](https://github.com/JiRaska/open-bank/issues/494)) ([f2a0798](https://github.com/JiRaska/open-bank/commit/f2a0798746edcc08328149f4ff1214138cd8c49a))
* **customer-edge:** move public /onboarding/start to its own un-annotated resource + deploy ([#495](https://github.com/JiRaska/open-bank/issues/495)) ([b54ec7b](https://github.com/JiRaska/open-bank/commit/b54ec7ba80e9a43434dfdec0a8e3a4607947d569))
* **customer-edge:** read getRaw body as ByteArray for binary-safe document proxy ([#593](https://github.com/JiRaska/open-bank/issues/593)) ([4612f0e](https://github.com/JiRaska/open-bank/commit/4612f0eed72e0e2b7b98b1b1b664e36d76c110c9))
* **customer-edge:** send Accept */* when proxying statement render (was 406) ([#585](https://github.com/JiRaska/open-bank/issues/585)) ([72ea64e](https://github.com/JiRaska/open-bank/commit/72ea64efc8c8f489303d87aebb447c03a477f00f))
* **customer-edge:** singleton HttpClient + token cache, compile fix ([#406](https://github.com/JiRaska/open-bank/issues/406)) ([625b7fb](https://github.com/JiRaska/open-bank/commit/625b7fb95cc43af2b7faa266cdbc361c1b045962))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))

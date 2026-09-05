# Changelog

## [0.30.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.30.0...notification-service-v0.30.1) (2026-09-03)


### Bug Fixes

* **notification:** KYC outcome notifications get a producer — consume openbank.kyc.events ([#8432](https://github.com/JiRaska/open-bank-oss/issues/8432)) ([#8547](https://github.com/JiRaska/open-bank-oss/issues/8547)) ([e6d3e35](https://github.com/JiRaska/open-bank-oss/commit/e6d3e35be818978324c9508e9d524e6462b1fb05))

## [0.30.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.29.2...notification-service-v0.30.0) (2026-09-02)


### Features

* **notification:** complete delegation lifecycle alerts ([#8184](https://github.com/JiRaska/open-bank-oss/issues/8184)) ([0656e71](https://github.com/JiRaska/open-bank-oss/commit/0656e7187fd1cdcffa36c0c9d8009b4bd534f14e)), closes [#8183](https://github.com/JiRaska/open-bank-oss/issues/8183) [#8172](https://github.com/JiRaska/open-bank-oss/issues/8172)

## [0.29.2](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.29.1...notification-service-v0.29.2) (2026-09-01)


### Bug Fixes

* **notification:** reject a null map value with 400 instead of 500 ([#8005](https://github.com/JiRaska/open-bank-oss/issues/8005)) ([56bfc37](https://github.com/JiRaska/open-bank-oss/commit/56bfc37ee6c8e8f43964305feeef26c0a3c2a7e2)), closes [#7867](https://github.com/JiRaska/open-bank-oss/issues/7867)

## [0.29.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.29.0...notification-service-v0.29.1) (2026-08-30)


### Bug Fixes

* **notification:** stop acking a failed dispatch and wire its DLQ ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#7588](https://github.com/JiRaska/open-bank-oss/issues/7588)) ([69a64ad](https://github.com/JiRaska/open-bank-oss/commit/69a64ad29cdd06b72b15a7f3b12e760079d66a5f))

## [0.29.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.28.0...notification-service-v0.29.0) (2026-08-27)


### Features

* **notification:** add safe no-device push fallback ([#7463](https://github.com/JiRaska/open-bank-oss/issues/7463)) ([7c9cc2c](https://github.com/JiRaska/open-bank-oss/commit/7c9cc2c2b87b8d2a6d1a9fc4a5ef2b94a5935012))

## [0.28.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.27.0...notification-service-v0.28.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.27.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.26.0...notification-service-v0.27.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.26.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.25.2...notification-service-v0.26.0) (2026-08-24)


### Features

* **notification:** expose pending four-eyes approvals via approval inbox ([#6547](https://github.com/JiRaska/open-bank-oss/issues/6547)) ([312a73b](https://github.com/JiRaska/open-bank-oss/commit/312a73b0ddffa4eb834b871d75e93fbd022411f1))

## [0.25.2](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.25.1...notification-service-v0.25.2) (2026-08-22)


### Bug Fixes

* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [0.25.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.25.0...notification-service-v0.25.1) (2026-08-21)


### Bug Fixes

* **notification:** wire the DLQ the [#5698](https://github.com/JiRaska/open-bank-oss/issues/5698) sweep assumes, and enumerate every consumer ([#5737](https://github.com/JiRaska/open-bank-oss/issues/5737)) ([0121c10](https://github.com/JiRaska/open-bank-oss/commit/0121c10a71577ddd550c8421b7d19666a61e92a9))

## [0.25.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.24.0...notification-service-v0.25.0) (2026-08-19)


### Features

* **notification:** wire ADR-0232 delegation lifecycle into notifications ([#5661](https://github.com/JiRaska/open-bank-oss/issues/5661)) ([b89e1fb](https://github.com/JiRaska/open-bank-oss/commit/b89e1fb52f64d0d417726c1b14ef6b4c172194a6))

## [0.24.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.23.3...notification-service-v0.24.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.23.3](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.23.2...notification-service-v0.23.3) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.23.2](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.23.1...notification-service-v0.23.2) (2026-08-16)


### Bug Fixes

* **notification:** follow the ADR-0179 merged_into pointer at dispatch ([#5112](https://github.com/JiRaska/open-bank-oss/issues/5112)) ([ff55163](https://github.com/JiRaska/open-bank-oss/commit/ff551638aea391365621e5f3d1722dba5ca309df)), closes [#1984](https://github.com/JiRaska/open-bank-oss/issues/1984)

## [0.23.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.23.0...notification-service-v0.23.1) (2026-08-15)


### Bug Fixes

* **notification:** a mocked mailer committed status=SENT with sent_at for mail that never left ([#4938](https://github.com/JiRaska/open-bank-oss/issues/4938)) ([7a01350](https://github.com/JiRaska/open-bank-oss/commit/7a01350af1531eb7ac78cb2fdb844c84a2840d9e)), closes [#4737](https://github.com/JiRaska/open-bank-oss/issues/4737) [#4363](https://github.com/JiRaska/open-bank-oss/issues/4363)

## [0.23.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.22.2...notification-service-v0.23.0) (2026-08-14)


### Features

* **scheduler:** register workflow liveness on four retention and cleanup jobs (ADR-0237) ([#4739](https://github.com/JiRaska/open-bank-oss/issues/4739)) ([c2a2fa4](https://github.com/JiRaska/open-bank-oss/commit/c2a2fa4b788a172ef85c8babb439cecd10fbfe23)), closes [#3345](https://github.com/JiRaska/open-bank-oss/issues/3345)

## [0.22.2](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.22.1...notification-service-v0.22.2) (2026-08-13)


### Bug Fixes

* **notification:** count the outcome events published for a notification row that does not exist ([#4711](https://github.com/JiRaska/open-bank-oss/issues/4711)) ([e6b05dc](https://github.com/JiRaska/open-bank-oss/commit/e6b05dc5b8d9403f3f95c658898e9c0690f95d65)), closes [#4512](https://github.com/JiRaska/open-bank-oss/issues/4512)

## [0.22.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.22.0...notification-service-v0.22.1) (2026-08-13)


### Bug Fixes

* **notification:** record device sweep liveness ([#4650](https://github.com/JiRaska/open-bank-oss/issues/4650)) ([67cfb60](https://github.com/JiRaska/open-bank-oss/commit/67cfb60b5588c2ebf1ede760ae70d927900fc48d))

## [0.22.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.21.0...notification-service-v0.22.0) (2026-08-13)


### Features

* **campaign:** add push interaction reference ([#4516](https://github.com/JiRaska/open-bank-oss/issues/4516)) ([1bb708b](https://github.com/JiRaska/open-bank-oss/commit/1bb708b5f3d5bd8af2c578f4f530bd665bbe7e3e))

## [0.21.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.20.0...notification-service-v0.21.0) (2026-08-13)


### Features

* **campaign:** add measured holdout experiments ([#4471](https://github.com/JiRaska/open-bank-oss/issues/4471)) ([8756228](https://github.com/JiRaska/open-bank-oss/commit/8756228553b5daa828762cace3a84457d3a4b816))

## [0.20.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.19.1...notification-service-v0.20.0) (2026-08-09)


### Features

* **notification:** tag push fan-outs by template so an undelivered SCA approval can be alerted on ([#4362](https://github.com/JiRaska/open-bank-oss/issues/4362)) ([347f1c6](https://github.com/JiRaska/open-bank-oss/commit/347f1c6f035da6cba461c5111f567e2e7325e196))

## [0.19.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.19.0...notification-service-v0.19.1) (2026-08-09)


### Bug Fixes

* **notification:** persist WHY a delivery failed (complements ADR-0252's counters) ([#4349](https://github.com/JiRaska/open-bank-oss/issues/4349)) ([28295b3](https://github.com/JiRaska/open-bank-oss/commit/28295b35453627d72e171284126286a7ad44ae43))

## [0.19.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.18.1...notification-service-v0.19.0) (2026-08-09)


### Features

* **notification:** ADR-0252 synthetic assurance — phase 0, make the push channel observable ([#4351](https://github.com/JiRaska/open-bank-oss/issues/4351)) ([98c3623](https://github.com/JiRaska/open-bank-oss/commit/98c36233a0f8a55bb2be191a8b7142b77e9db492))

## [0.18.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.18.0...notification-service-v0.18.1) (2026-08-08)


### Security

* **notification:** wire ContactPolicyGate at the ADR-0219 D4 choke point ([#4224](https://github.com/JiRaska/open-bank-oss/issues/4224)) ([91902ea](https://github.com/JiRaska/open-bank-oss/commit/91902ea3293a95c78e74cf74d0b61d1f99d0b861))

## [0.18.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.17.4...notification-service-v0.18.0) (2026-08-06)


### Features

* **campaign,notification:** tell a delivered message from an accepted handoff (ADR-0239) ([#3838](https://github.com/JiRaska/open-bank-oss/issues/3838)) ([789d662](https://github.com/JiRaska/open-bank-oss/commit/789d6626fde13ab48cd72806a07b7c4e6c19ed0e))

## [0.17.4](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.17.3...notification-service-v0.17.4) (2026-08-03)


### Bug Fixes

* **campaign,notification:** resolve the EMAIL recipient from partyId, and stop the send log losing a failed handoff ([#3662](https://github.com/JiRaska/open-bank-oss/issues/3662)) ([f050b19](https://github.com/JiRaska/open-bank-oss/commit/f050b198fbdae19a1909a180f00e9bbddce57297))

## [0.17.3](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.17.2...notification-service-v0.17.3) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.17.2](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.17.1...notification-service-v0.17.2) (2026-08-02)


### Bug Fixes

* **consent:** the expiration sweep runs on a Vert.x context, and the gate now checks the property ([#2976](https://github.com/JiRaska/open-bank-oss/issues/2976)) ([5f3c535](https://github.com/JiRaska/open-bank-oss/commit/5f3c535d44d728598d547dcdb499a6a1e595c626)), closes [#2913](https://github.com/JiRaska/open-bank-oss/issues/2913)

## [0.17.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.17.0...notification-service-v0.17.1) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))

## [0.17.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.16.2...notification-service-v0.17.0) (2026-07-31)


### Features

* **campaign:** campaign-service first slice — deterministic segments, consent-gated Temporal journeys (ADR-0200/0209 D3) ([#2751](https://github.com/JiRaska/open-bank-oss/issues/2751)) ([27e83b4](https://github.com/JiRaska/open-bank-oss/commit/27e83b42c70cc7289d5f684e1ccd40c3f326c14c))

## [0.16.2](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.16.1...notification-service-v0.16.2) (2026-07-30)


### Bug Fixes

* **notification:** disable OIDC discovery in the test profile too ([#2765](https://github.com/JiRaska/open-bank-oss/issues/2765)) ([cb666a6](https://github.com/JiRaska/open-bank-oss/commit/cb666a6ef7e57330c73e2288ebd187938070a0c2))

## [0.16.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.16.0...notification-service-v0.16.1) (2026-07-30)


### Bug Fixes

* **notification:** disable eager OIDC token acquisition in the test profile ([#2746](https://github.com/JiRaska/open-bank-oss/issues/2746)) ([5829c46](https://github.com/JiRaska/open-bank-oss/commit/5829c4604ed9db8791471d7504ba8d30a0ac375b))

## [0.16.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.15.3...notification-service-v0.16.0) (2026-07-29)


### Features

* **notification:** wire the real marketing consent check per send (ADR-0198 D4) ([#2692](https://github.com/JiRaska/open-bank-oss/issues/2692)) ([f74662a](https://github.com/JiRaska/open-bank-oss/commit/f74662a6f95ad1139c2493cb586d3d7a621239f0)), closes [#2660](https://github.com/JiRaska/open-bank-oss/issues/2660)

## [0.15.3](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.15.2...notification-service-v0.15.3) (2026-07-26)


### Bug Fixes

* **notification:** fail closed on marketing push and suppress marketing email (ADR-0198 D4) ([#2543](https://github.com/JiRaska/open-bank-oss/issues/2543)) ([1beeb81](https://github.com/JiRaska/open-bank-oss/commit/1beeb8198cfef2fdd977ba3620563d4413955e01))

## [0.15.2](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.15.1...notification-service-v0.15.2) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.15.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.15.0...notification-service-v0.15.1) (2026-07-25)


### Bug Fixes

* **notification:** remove SMS and IN_APP — both were logging stubs reporting success ([#2386](https://github.com/JiRaska/open-bank-oss/issues/2386)) ([25f2177](https://github.com/JiRaska/open-bank-oss/commit/25f217786fcc714f7bd50ce447513f09d5c1677d))

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.14.1...notification-service-v0.15.0) (2026-07-24)


### Features

* **sca:** send a real approval push on challenge initiate ([#2026](https://github.com/JiRaska/open-bank-oss/issues/2026)) ([f53c527](https://github.com/JiRaska/open-bank-oss/commit/f53c52733722b684e838d317fc29981185dc0f20)), closes [#2025](https://github.com/JiRaska/open-bank-oss/issues/2025)

## [0.14.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.14.0...notification-service-v0.14.1) (2026-07-24)


### Bug Fixes

* **notification:** stop push payloads leaking amount/PII, add party-scoped read ([#1182](https://github.com/JiRaska/open-bank-oss/issues/1182)) ([#2011](https://github.com/JiRaska/open-bank-oss/issues/2011)) ([3fb2044](https://github.com/JiRaska/open-bank-oss/commit/3fb2044f8476628ab0d30ed21227263f7a63e6c7))

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.13.8...notification-service-v0.14.0) (2026-07-23)


### Features

* **notifications:** per-party push preferences with category opt-out ([#1990](https://github.com/JiRaska/open-bank-oss/issues/1990)) ([6de0d5e](https://github.com/JiRaska/open-bank-oss/commit/6de0d5e95b849ad5d84cd663503e307cec435dc6)), closes [#1989](https://github.com/JiRaska/open-bank-oss/issues/1989)

## [0.13.8](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.13.7...notification-service-v0.13.8) (2026-07-23)


### Bug Fixes

* **notification:** use uuidv7 for consumer notification ids ([#1909](https://github.com/JiRaska/open-bank-oss/issues/1909)) ([bd2fed2](https://github.com/JiRaska/open-bank-oss/commit/bd2fed23b01711bd510ec80ebcbf2917553b357d))

## [0.13.7](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.13.6...notification-service-v0.13.7) (2026-07-18)


### Security

* **notification:** escape HTML variables in rendered notification bodies ([#1634](https://github.com/JiRaska/open-bank-oss/issues/1634)) ([5934967](https://github.com/JiRaska/open-bank-oss/commit/5934967b1d3deb82695632c7f259e63cfcb60288)), closes [#1382](https://github.com/JiRaska/open-bank-oss/issues/1382) [#1386](https://github.com/JiRaska/open-bank-oss/issues/1386)

## [0.13.6](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.13.5...notification-service-v0.13.6) (2026-07-17)


### Bug Fixes

* **notification:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1539](https://github.com/JiRaska/open-bank-oss/issues/1539)) ([0af8f0f](https://github.com/JiRaska/open-bank-oss/commit/0af8f0f07562ee329fe0bf1d677a23666d9654c5))

## [0.13.5](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.13.4...notification-service-v0.13.5) (2026-07-17)


### Bug Fixes

* **notification:** keep the PUSH status update on the Vert.x context after the async APNs send ([#1559](https://github.com/JiRaska/open-bank-oss/issues/1559)) ([2da8588](https://github.com/JiRaska/open-bank-oss/commit/2da858803500a1f630a3e989d9d1945e1180cdee)), closes [#1548](https://github.com/JiRaska/open-bank-oss/issues/1548)

## [0.13.4](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.13.3...notification-service-v0.13.4) (2026-07-17)


### Bug Fixes

* **notification:** correct stale comment on OperatorMessageService's notificationId ([#1430](https://github.com/JiRaska/open-bank-oss/issues/1430)) ([fe46205](https://github.com/JiRaska/open-bank-oss/commit/fe46205b64e5196190604e4037a2cb53a8e1d2aa))

## [0.13.3](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.13.2...notification-service-v0.13.3) (2026-07-17)


### Bug Fixes

* **notification:** validate opsmessage.compose recipient format before sending ([#1426](https://github.com/JiRaska/open-bank-oss/issues/1426)) ([38d343a](https://github.com/JiRaska/open-bank-oss/commit/38d343a0ec74a6a0cef19c351fb78bb5f5858e19)), closes [#1384](https://github.com/JiRaska/open-bank-oss/issues/1384)

## [0.13.2](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.13.1...notification-service-v0.13.2) (2026-07-17)


### Performance

* **notification:** scoped bulk UPDATE for opsmessage.compose's terminal status transition ([#1436](https://github.com/JiRaska/open-bank-oss/issues/1436)) ([2fdcee6](https://github.com/JiRaska/open-bank-oss/commit/2fdcee63797751d013b7230621b18f1ebd656b1e)), closes [#1393](https://github.com/JiRaska/open-bank-oss/issues/1393)

## [0.13.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.13.0...notification-service-v0.13.1) (2026-07-17)


### Bug Fixes

* **notification:** reject opsmessage.compose requests missing required template variables ([#1424](https://github.com/JiRaska/open-bank-oss/issues/1424)) ([e99ad93](https://github.com/JiRaska/open-bank-oss/commit/e99ad931ff4d69a43ff4e090aa81ccc4df0e0181)), closes [#1381](https://github.com/JiRaska/open-bank-oss/issues/1381)
* **notification:** split NotificationConsumer.sendEmail's mail-failure handling from SENT-recording ([#1433](https://github.com/JiRaska/open-bank-oss/issues/1433)) ([9ed42eb](https://github.com/JiRaska/open-bank-oss/commit/9ed42ebe11db31783f6cc9c4dba1e9705488b808)), closes [#1392](https://github.com/JiRaska/open-bank-oss/issues/1392)

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.12.9...notification-service-v0.13.0) (2026-07-17)


### Features

* **notification:** operator-initiated customer messaging, four-eyes gated ([#1368](https://github.com/JiRaska/open-bank-oss/issues/1368)) ([6e6be0e](https://github.com/JiRaska/open-bank-oss/commit/6e6be0e7e0a834f501f34c46202ed8acff644ef2))

## [0.12.9](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.12.8...notification-service-v0.12.9) (2026-07-17)


### Security

* **notification:** close the variable schema; render every template ([#1350](https://github.com/JiRaska/open-bank-oss/issues/1350)) ([721e26c](https://github.com/JiRaska/open-bank-oss/commit/721e26c84d46d92656f021ddfbdcb50d2e00edb4)), closes [#1325](https://github.com/JiRaska/open-bank-oss/issues/1325)

## [0.12.8](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.12.7...notification-service-v0.12.8) (2026-07-16)


### Bug Fixes

* **notification:** correct openapi.yaml drift from the implemented contract ([#1303](https://github.com/JiRaska/open-bank-oss/issues/1303)) ([8910700](https://github.com/JiRaska/open-bank-oss/commit/8910700c042c224c449deb5db9702670cde389e6)), closes [#1179](https://github.com/JiRaska/open-bank-oss/issues/1179)


### Security

* **notification:** never store rendered OTP or password-reset bodies ([#1180](https://github.com/JiRaska/open-bank-oss/issues/1180)) ([d078e52](https://github.com/JiRaska/open-bank-oss/commit/d078e52a63797d03627be9f76af40d4222585f4b)), closes [#1179](https://github.com/JiRaska/open-bank-oss/issues/1179)

## [0.12.7](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.12.6...notification-service-v0.12.7) (2026-07-11)


### Bug Fixes

* **notification-service:** fix silent Kafka client.id dotted-key bug ([#731](https://github.com/JiRaska/open-bank-oss/issues/731)) ([5efec71](https://github.com/JiRaska/open-bank-oss/commit/5efec7181047b1e95ccbb57927c788050d35c716)), closes [#686](https://github.com/JiRaska/open-bank-oss/issues/686)

## [0.12.6](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.12.5...notification-service-v0.12.6) (2026-07-09)


### Bug Fixes

* **notification-service:** fix silent Kafka group.id/auto.offset.reset config bug ([#693](https://github.com/JiRaska/open-bank-oss/issues/693)) ([1b4f2a0](https://github.com/JiRaska/open-bank-oss/commit/1b4f2a04bcb726bf1ad7421be8f1f8e4beed2a3f)), closes [#686](https://github.com/JiRaska/open-bank-oss/issues/686)

## [0.12.5](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.12.4...notification-service-v0.12.5) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.12.4](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.12.3...notification-service-v0.12.4) (2026-07-03)


### Bug Fixes

* **notification:** notification-service authz fails closed with no OPA sidecar; add read-state ([#212](https://github.com/JiRaska/open-bank-oss/issues/212)) ([b976fbd](https://github.com/JiRaska/open-bank-oss/commit/b976fbdfb932f2e5adfb1c0fb62d8fc4e76f3b7e))

## [0.12.3](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.12.2...notification-service-v0.12.3) (2026-07-03)


### Bug Fixes

* **security:** pin remaining unpinned dependencies fleet-wide (Scorecard) ([#190](https://github.com/JiRaska/open-bank-oss/issues/190)) ([e9e8ec3](https://github.com/JiRaska/open-bank-oss/commit/e9e8ec37d3c195bf2a889619894272f3a5b1a100))

## [0.12.2](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.12.1...notification-service-v0.12.2) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))
* **notification:** clarify DER-parsing operator precedence with explicit parens ([#107](https://github.com/JiRaska/open-bank-oss/issues/107)) ([e5f9cf2](https://github.com/JiRaska/open-bank-oss/commit/e5f9cf27b774f1bf726ba8d938612903c9ce1f71))

## [0.12.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.12.0...notification-service-v0.12.1) (2026-06-30)


### Security

* **notification:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2752](https://github.com/JiRaska/open-bank-oss/issues/2752)) ([c39a798](https://github.com/JiRaska/open-bank-oss/commit/c39a7987bbc6852a622ec5afbd22b1002cb604b2))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.11.0...notification-service-v0.12.0) (2026-06-29)


### Features

* **notification:** add DLQ janitor, oversight audit, and Slack webhook IT ([#2549](https://github.com/JiRaska/open-bank-oss/issues/2549)) ([9cea598](https://github.com/JiRaska/open-bank-oss/commit/9cea598a70ad2949849df48d18477333d2618610))
* **notification:** add push token lifecycle columns (ADR-0135 §2) ([#2674](https://github.com/JiRaska/open-bank-oss/issues/2674)) ([4c36d47](https://github.com/JiRaska/open-bank-oss/commit/4c36d47ca2427e566c46e7739c2c3213034935f1))
* **notification:** device deactivation endpoint + 90-day TTL sweep (IDOR-safe) ([#2527](https://github.com/JiRaska/open-bank-oss/issues/2527)) ([2823bc2](https://github.com/JiRaska/open-bank-oss/commit/2823bc2a6beb5515f0da1cedeb950f9616e4f5be))
* **notification:** Teams webhook adapter + fan-out to all oversight channels ([#2521](https://github.com/JiRaska/open-bank-oss/issues/2521)) ([0fd6d6a](https://github.com/JiRaska/open-bank-oss/commit/0fd6d6a9e9c68bd8ff3d94eac8dff42878fd129e))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([ce39af7](https://github.com/JiRaska/open-bank-oss/commit/ce39af7c5b02ff3fef226f34e78c7e726b149ebd))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** handle PARTY_ERASED event to delete PII (GDPR Art. 17) ([#2267](https://github.com/JiRaska/open-bank-oss/issues/2267)) ([572cd80](https://github.com/JiRaska/open-bank-oss/commit/572cd80fd531a00c46a9796104e96b0ebcf92ffd))
* **notification:** push token lifecycle — DELETE endpoint, 90-day TTL sweep, STALE status ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485)) ([7d49b11](https://github.com/JiRaska/open-bank-oss/commit/7d49b11610b63b00587df821129f6eb2d8f45323))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.9.0...notification-service-v0.10.0) (2026-06-29)


### Features

* **notification:** add DLQ janitor, oversight audit, and Slack webhook IT ([#2549](https://github.com/JiRaska/open-bank-oss/issues/2549)) ([9cea598](https://github.com/JiRaska/open-bank-oss/commit/9cea598a70ad2949849df48d18477333d2618610))
* **notification:** device deactivation endpoint + 90-day TTL sweep (IDOR-safe) ([#2527](https://github.com/JiRaska/open-bank-oss/issues/2527)) ([2823bc2](https://github.com/JiRaska/open-bank-oss/commit/2823bc2a6beb5515f0da1cedeb950f9616e4f5be))
* **notification:** Teams webhook adapter + fan-out to all oversight channels ([#2521](https://github.com/JiRaska/open-bank-oss/issues/2521)) ([0fd6d6a](https://github.com/JiRaska/open-bank-oss/commit/0fd6d6a9e9c68bd8ff3d94eac8dff42878fd129e))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([ce39af7](https://github.com/JiRaska/open-bank-oss/commit/ce39af7c5b02ff3fef226f34e78c7e726b149ebd))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** handle PARTY_ERASED event to delete PII (GDPR Art. 17) ([#2267](https://github.com/JiRaska/open-bank-oss/issues/2267)) ([572cd80](https://github.com/JiRaska/open-bank-oss/commit/572cd80fd531a00c46a9796104e96b0ebcf92ffd))
* **notification:** push token lifecycle — DELETE endpoint, 90-day TTL sweep, STALE status ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485)) ([7d49b11](https://github.com/JiRaska/open-bank-oss/commit/7d49b11610b63b00587df821129f6eb2d8f45323))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.8.0...notification-service-v0.9.0) (2026-06-29)


### Features

* **notification:** add DLQ janitor, oversight audit, and Slack webhook IT ([#2549](https://github.com/JiRaska/open-bank-oss/issues/2549)) ([8cbf232](https://github.com/JiRaska/open-bank-oss/commit/8cbf232cc54896520604d42b97152eea5ce6afa7))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.7.3...notification-service-v0.8.0) (2026-06-29)


### Features

* **notification:** Teams webhook adapter + fan-out to all oversight channels ([#2521](https://github.com/JiRaska/open-bank-oss/issues/2521)) ([60647d9](https://github.com/JiRaska/open-bank-oss/commit/60647d939547a041f112b17a562fddf14d2c7573))

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.7.2...notification-service-v0.7.3) (2026-06-29)


### Bug Fixes

* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([812d005](https://github.com/JiRaska/open-bank-oss/commit/812d00515f5f2f932c670ce5b4d25630539e10ec))

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.7.1...notification-service-v0.7.2) (2026-06-29)


### Bug Fixes

* **notification:** push token lifecycle — DELETE endpoint, 90-day TTL sweep, STALE status ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485)) ([c6fa3f5](https://github.com/JiRaska/open-bank-oss/commit/c6fa3f58e11605a97ef12cefe0fd0882033d044b))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.7.0...notification-service-v0.7.1) (2026-06-28)


### Bug Fixes

* **notification:** handle PARTY_ERASED event to delete PII (GDPR Art. 17) ([#2267](https://github.com/JiRaska/open-bank-oss/issues/2267)) ([248441e](https://github.com/JiRaska/open-bank-oss/commit/248441e5e0595fc7abcfdfd0220195d925da0610))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.6.0...notification-service-v0.7.0) (2026-06-27)


### Features

* **notification,party,standing-order:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2115](https://github.com/JiRaska/open-bank-oss/issues/2115)) ([596924a](https://github.com/JiRaska/open-bank-oss/commit/596924a5ea3f05e8722767c66fe89638aeaaeb87))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank-oss/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.5.0...notification-service-v0.6.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank-oss/commit/4ea273195d038704acc6341f684c0f1cb039ce82))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.4.0...notification-service-v0.5.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **notification:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#805](https://github.com/JiRaska/open-bank-oss/issues/805)) ([a76f35e](https://github.com/JiRaska/open-bank-oss/commit/a76f35eebc4e6a74d4e8035934f1b8b823c396a7))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/notification-service-v0.3.0...notification-service-v0.4.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **notification:** anonymized Slack/Teams oversight webhooks (ADR-0059) ([#288](https://github.com/JiRaska/open-bank-oss/issues/288)) ([2bbe76c](https://github.com/JiRaska/open-bank-oss/commit/2bbe76c623f69f7382f66db4d8d4e898d5492ed7))
* **notification:** PUSH delivery via FCM/APNs + device token registry ([#535](https://github.com/JiRaska/open-bank-oss/issues/535)) ([73c4ebd](https://github.com/JiRaska/open-bank-oss/commit/73c4ebdac4dc96fffc6c60823df00d88a07f1c78))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **notifications:** page + count the feed in a single reactive session ([#567](https://github.com/JiRaska/open-bank-oss/issues/567)) ([204e22d](https://github.com/JiRaska/open-bank-oss/commit/204e22d1b703c3cb691b16cf9cf486b7fba4ce2b))

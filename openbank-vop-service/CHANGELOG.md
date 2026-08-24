# Changelog

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/vop-service-v0.4.1...vop-service-v0.5.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/vop-service-v0.4.0...vop-service-v0.4.1) (2026-08-22)


### Bug Fixes

* **docs:** repair the 7 .mmd diagrams that do not parse ([#6496](https://github.com/JiRaska/open-bank-oss/issues/6496)) ([c1e6ad7](https://github.com/JiRaska/open-bank-oss/commit/c1e6ad7b14887db70ec3365747f2ed06d9ec02db))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/vop-service-v0.3.4...vop-service-v0.4.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))

## [0.3.4](https://github.com/JiRaska/open-bank-oss/compare/vop-service-v0.3.3...vop-service-v0.3.4) (2026-08-07)


### Bug Fixes

* **libs:** move CDI interceptor bindings out of libs-domain and close the gate hole that hid them ([#3808](https://github.com/JiRaska/open-bank-oss/issues/3808)) ([7316bb1](https://github.com/JiRaska/open-bank-oss/commit/7316bb1257dbf04bf43f2d8498bc7ea8e78cc490))

## [0.3.3](https://github.com/JiRaska/open-bank-oss/compare/vop-service-v0.3.2...vop-service-v0.3.3) (2026-08-07)


### Bug Fixes

* **vop:** correct both payee-name hop URLs — ports were transposed, namespace never existed ([#3968](https://github.com/JiRaska/open-bank-oss/issues/3968)) ([fcabf55](https://github.com/JiRaska/open-bank-oss/commit/fcabf551e34a4385629c4698250bb162fcd62fa4))

## [0.3.3](https://github.com/JiRaska/open-bank-oss/compare/vop-service-v0.3.2...vop-service-v0.3.3) (2026-08-07)


### Bug Fixes

* **vop:** correct both payee-name hop URLs — ports were transposed, namespace never existed ([#3968](https://github.com/JiRaska/open-bank-oss/issues/3968)) ([fcabf55](https://github.com/JiRaska/open-bank-oss/commit/fcabf551e34a4385629c4698250bb162fcd62fa4))

## [0.3.2](https://github.com/JiRaska/open-bank-oss/compare/vop-service-v0.3.1...vop-service-v0.3.2) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/vop-service-v0.3.0...vop-service-v0.3.1) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/vop-service-v0.2.0...vop-service-v0.3.0) (2026-07-25)


### Features

* **vop:** instrument verification outcomes, no-data reasons and rate-limit decisions ([#2275](https://github.com/JiRaska/open-bank-oss/issues/2275)) ([fc6795a](https://github.com/JiRaska/open-bank-oss/commit/fc6795af7ee129a3ee63675f60e2d520d5d8a019)), closes [#2255](https://github.com/JiRaska/open-bank-oss/issues/2255)

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/vop-service-v0.1.0...vop-service-v0.2.0) (2026-07-16)


### Features

* **vop:** add Verification of Payee backend; give control-plane agents episodic memory ([#1195](https://github.com/JiRaska/open-bank-oss/issues/1195)) ([91460fc](https://github.com/JiRaska/open-bank-oss/commit/91460fcc62bb72f4a99953e51e90374597dda9c3))


### Bug Fixes

* **ci:** add vop-service to auto-deploy ALL_SERVICES; give it a Dockerfile ([#1229](https://github.com/JiRaska/open-bank-oss/issues/1229)) ([2bc5158](https://github.com/JiRaska/open-bank-oss/commit/2bc51585b101f6bbe6e3d27f9b39310e0c9ce44f))
* **vop:** match legal forms per token so a different payee is never confirmed ([#1207](https://github.com/JiRaska/open-bank-oss/issues/1207)) ([d808a5c](https://github.com/JiRaska/open-bank-oss/commit/d808a5c64da3ef805a7bb6fc4a8c90544d8fb753)), closes [#1204](https://github.com/JiRaska/open-bank-oss/issues/1204) [#1195](https://github.com/JiRaska/open-bank-oss/issues/1195)

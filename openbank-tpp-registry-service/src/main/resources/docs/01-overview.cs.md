# Přehled

## Co služba dělá

`openbank-tpp-registry-service` je **systém záznamů pro autorizace poskytovatelů třetích stran (TPP)** podle PSD2 / Open Banking v platformě OpenBank. Drží:

- **Agregát TppEntry** — unikátní identifikátor EBA/CNB (`tppId`, např. `CZ-CNB-123456`), právní název, zemi (ISO 3166-1 alpha-2), národní příslušný orgán (`nca`, např. `CNB`, `BaFin`), množinu autorizovaných **rolí** (AISP / PISP / PIISP / ASPSP), stav (ACTIVE / SUSPENDED / REVOKED / BLACKLISTED) a metadata eIDAS certifikátů (Subject DN a expirace QWAC a QSeal).
- **EbaRegisterSyncState** — evidence (plánované) synchronizace proti centrálnímu registru EBA: čas posledního syncu, poslední úspěch, počet záznamů, poslední chyba.
- **Kontrola autorizace** — rychlý read, který používají ostatní služby k rozhodnutí, zda TPP smí *právě teď* uplatnit danou roli (je aktivní, má roli, certifikát neexpiroval).

## Co služba **NEDĚLÁ**

- ❌ Neukládá ani nevyhodnocuje **souhlasy** zákazníků — to je `openbank-consent-service`.
- ❌ Neprovádí **silné ověření zákazníka (SCA)** — to je `openbank-sca-service` (ADR-0021).
- ❌ Není **Open Banking API fasáda**, kterou TPP volá — to je `openbank-psd2-service`, který tento registr volá k autorizaci volajícího.
- ❌ **Neověřuje řetězec eIDAS certifikátu na TLS vrstvě** — ukládá Subject DN a expiraci; živý mTLS/QWAC pinning je záležitost edge/gateway.
- ❌ Zatím **netáhne automaticky z registru EBA** — EBA sync je zatím stub; TPP se registrují manuálně (viz `attemptEbaSync`).

## Pozice v doméně

```
   ┌────────────┐  registrace / blacklist  ┌──────────────────────┐
   │  admin UI  │ ───────────────────────► │ tpp-registry-service │
   └────────────┘                          └─────────┬────────────┘
                                                      │ GET /check (autorizuj TPP)
   ┌────────────┐    AIS/PIS volání  ┌──────────────┐ ▲
   │    TPP     │ ─────────────────► │ psd2-service │─┘
   └────────────┘                    └──────────────┘
                                                      │ outbox → Kafka
                                                      ▼
                                          ┌──────────────────────┐
                                          │ audit-service / další │
                                          │ (topic openbank.tpp.  │
                                          │  registry.event)      │
                                          └──────────────────────┘
                                                      │
                                                      ▼
                                                 PostgreSQL
                                          (db: openbank_tpp_registry)
```

## Klíčové use case

| Use case | API | Událost |
|---|---|---|
| Ověř, zda TPP smí uplatnit roli | `GET /api/v1/tpp-registry/check?tppId=…&role=AIS` | — (read) |
| Registruj nový TPP | `POST /api/v1/tpp-registry` | (outbox infra přítomná; zatím se nevypouští) |
| Vypiš / filtruj registrované TPP | `GET /api/v1/tpp-registry?countryCode=&role=&status=` | — (read) |
| Získej jeden TPP | `GET /api/v1/tpp-registry/{tppId}` | — (read) |
| Zařaď TPP na blacklist | `POST /api/v1/tpp-registry/{tppId}/blacklist` | (outbox infra přítomná; zatím se nevypouští) |
| Spusť sync registru EBA | `POST /api/v1/tpp-registry/sync/eba` | — (stub) |
| Přečti stav EBA syncu | `GET /api/v1/tpp-registry/sync/state` | — (read) |

## Volající

- **psd2-service** — hlavní volající; autorizuje každý příchozí požadavek TPP přes `GET /check`, než obslouží operaci AIS/PIS/PIIS (deklarováno jako upstream v `governance.yaml`).
- **admin-ui** (přes Keycloak token) — operátoři / compliance registrují, vypisují, prohlížejí a blacklistují TPP.
- **consent-service / sca-service** — mohou číst registr k přiřazení souhlasu nebo ověření ke známému TPP.

## Závislosti

- **PostgreSQL** (databáze `openbank_tpp_registry`)
- **Kafka** (`openbank-kafka`, topic `openbank.tpp.registry.event`)
- **Redis (Valkey)** — idempotenční cache
- **Keycloak** — OIDC auth
- **OPA sidecar** (ADR-0034) — policy decision point pro `@Authorize`, defaultně advisory
- **openbank-libs** — `IdempotencyStore`, `authz` (`@Authorize`, `OpaSidecarPolicyDecisionPoint`), outbox konvence, BuildInfo, DocsResource

## Byznysová hodnota

- **Jediná brána pro důvěru v TPP** — jedna autoritativní odpověď na otázku „smí tento TPP?", takže každá PSD2 plocha vynucuje stejný stav registrace a blacklistu.
- **Regulatorní soulad** — zrcadlí registr EBA / národního příslušného orgánu; expirace certifikátu a blacklist jsou prvotřídní, podporuje PSD2 RTS o bezpečné komunikaci.
- **Provozní kill-switch** — zařazení TPP na blacklist okamžitě selže jeho autorizační kontroly, čímž odřízne kompromitovaného nebo delicencovaného poskytovatele.
- **Auditovatelnost** — registrace a blacklisting mají propagovat přes outbox + Kafka do auditní stopy (transport zapojen; vypouštění událostí čeká).

# 02 — Architektura

Hexagonální podle [ADR-0002](../../../../docs/adr/0002-hexagonal-architecture-per-service.md). Doména má nula frameworkových importů; vynucuje `check-domain-purity.py`.

## Vrstvy

```
domain/
  match/VopNameMatchPolicy.kt     rozhodnutí — čisté, deterministické, symetrické, bez hodin
  model/VopModels.kt              VopOutcome, VopNoDataReason, VopVerification (+ jeho invarianty)

application/
  port/in/VopUseCases.kt          VerifyPayeeUseCase, VerifyPayeeCommand
  port/out/VopPorts.kt            AccountHolderNameLookupPort, VopSchemeRoutingPort,
                                  VopVerificationRecordPort, NameLookupUnavailableException
  usecase/VopVerificationService  routing + zapojení + evidence — sám nerozhoduje nic

infrastructure/
  rest/VopResource.kt             POST /api/v1/vop/verify
  rest/dto/VopDtos.kt             mapování na drát + explicitní validace
  ratelimit/                      VopRateLimiter, VopRateLimitFilter
  client/                         AccountServiceClient, PartyServiceClient (lokální zrcadla)
  adapter/                        AccountHolderNameLookupAdapter, NoSchemeRoutingAdapter
  persistence/                    VopVerificationEntity, VopVerificationRecordAdapter
  authz/AuthzProducer.kt          OPA sidecar PDP
  ClockProducer.kt                injektované Clock (ADR-0100)
```

Use case je **zapojovací** vrstva: routuje (domácí vs zahraniční), volá porty a zapíše evidenci. Každé *rozhodnutí* je ve `VopNameMatchPolicy`. Stejný tvar jako `ScreeningPolicy` v sepa-instant nebo `CollectionAuthorisationPolicy` v sdd-service.

Viz [diagram toku](../diagrams/01-vop-verification-flow.mmd) a [rozhodovací strom výsledků](../diagrams/03-outcome-decision.mmd).

## Dvouskokové dohledání jména

Nejpřekvapivější fakt o téhle službě:

> **`openbank-account-service` nedrží jméno majitele účtu vůbec.**

Jeho tabulka `accounts` nemá sloupec se jménem a `V10__account_search_trgm.sql` indexuje pouze `account_number`. Autoritativní jméno je `parties.legal_name` / `parties.trading_name` v `openbank-party-service`, dosažitelné z účtu jedině přes `party_id`. Takže IBAN → jméno je:

```
IBAN → account-service GET /accounts/iban/{iban} → partyId
     → party-service   GET /parties/{partyId}     → legalName ?: tradingName
```

Oba skoky používají M2M token (`openbank-services`, ražený oidc-client filtrem). Záměrně **neposíláme** hlavičku `X-Customer-Party-Id`, kterou account-service používá pro owner-scoping: VoP je kontrola jménem plátce, který legitimně **není** majitelem účtu. To znamená, že vop-service drží širší čtení než jakákoli zákaznická session — umí přeložit *libovolný* domácí IBAN na jméno. Tuhle výsadu drží v mezích to, že vrací *pásmo*, ne surový záznam, a asymetrie vyzrazení níže. **Neopravujte chybějící owner hlavičku: rozbilo by to smysl nařízení.**

**Proč jméno nedenormalizovat do account-service?** Zkrátilo by to dva skoky na jeden. Zamítnuto (ADR-0171): duplikovalo by autoritativní jméno mimo party-service a vytvořilo druhé místo, kde může zestárnout — přesně ten drift, kvůli kterému party-service existuje. Cena v latenci je známý, přijatý negativ; cache ve vop-service je únikový východ, až si to měření vyžádá.

## Fail-open — rozhodnutí, které je třeba pochopit dřív než cokoli jiného

| | sankční brána (ADR-0032) | VoP (ADR-0171) |
|---|---|---|
| Při výpadku | **Fail CLOSED** — platba držena `PENDING` | **Fail OPEN** — `no_data` + varování, platba pokračuje |
| Proč | Propuštěná sankce je porušení zákona | Odmítnout každou platbu při výpadku VoP by samo porušilo lhůtu pro provedení podle IPR |

**Obě stojí vedle sebe ve stejném pre-execution toku s opačnou sémantikou, záměrně.** Vypadá to jako nekonzistence a není. `VopVerificationServiceTest` to pinuje testem pojmenovaným přesně na to, takže budoucí čtenář, který VoP „opraví“ podle souseda, spadne na červeném testu, jehož název důvod vysvětluje.

Jediné, co se při selhání nikdy nestane: tichý `MATCH`. Absence odpovědi je `no_data`, nikdy odpověď kladná.

### Rate limiter selhává CLOSED — a je to konzistentní

Když je Valkey nedostupný, nemůžeme prokázat, že je volající pod limitem, takže vracíme 429. Zní to jako rozpor s výše uvedeným, ale není: **429 způsobí, že volající vykreslí `no_data`, takže platba stejně projde s varováním** — fail-open chování VoP dosažené jinou cestou. Fail-open na limiteru by za výpadku tiše sebral jedinou obranu proti enumeraci, aniž by získal jakoukoli dostupnost plateb.

## Odolnost

`AccountHolderNameLookupAdapter` nese `@CircuitBreaker` / `@Retry` / `@Timeout(3s)`, kopíruje tvar `SanctionsScreeningAdapter` — včetně jeho triku se `self`-injekcí:

```kotlin
@Inject lateinit var self: AccountHolderNameLookupAdapter
override fun lookupHolderName(iban: String) = self.lookupWithResilience(iban)
```

To není stylový vrtoch. Interceptory SmallRye Fault Tolerance se spouští jen přes CDI proxy — volání `this.lookupWithResilience(...)` napřímo by **tiše obešlo všechny anotace** na něm.

Adaptér také rozlišuje dva druhy selhání, a to rozlišení je pointa:
- **404 na kterémkoli skoku není selhání** — znamená, že pro tenhle IBAN žádné jméno nevedeme → `null` → `NO_DATA`/`ACCOUNT_NOT_FOUND`.
- **Cokoli jiného** (timeout, 5xx, otevřený circuit) vyhodí `NameLookupUnavailableException` → také `NO_DATA`, ale s jiným důvodem a varovným logem.

## Proč tu není Kafka

VoP nepublikuje žádné doménové události. Nemění žádný business stav — jediný zápis je evidenční řádek. Takže žádný outbox, žádný dispatcher, žádný flag `openbank.outbox.dispatch-enabled` a žádné KafkaUser/mTLS mounty v Rolloutu. Pokud budoucí změna přiměje VoP událost emitovat, začnou platit pravidla outboxu (ADR-0003/0013/0050) a footgun s dispatch-enabled je rázem živý.

## Zápis evidence je best-effort, záměrně

`VopVerificationRecordAdapter` chytá vlastní selhání a loguje na ERROR místo propagace. Selhání zápisu evidence nesmí shodit ověření, na které plátce čeká — udělat z výpadku logování výpadek platební cesty by převrátilo rozhodnutí fail-open zadními vrátky. Zbytkové riziko (útočník vyvolá cílený výpadek DB, aby oslepil záznam) je přijaté a popsané v threat modelu.

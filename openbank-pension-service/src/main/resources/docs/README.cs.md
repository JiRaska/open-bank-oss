# openbank-pension-service — Dokumentace

> **Co to je:** účastnická strana penzijní platformy: životní cyklus `PensionContract`, volba strategie, plán příspěvků, obmyšlení a vyhodnocení časově platných jurisdikčních balíčků ([ADR 0334](../../../../docs/adr/0334-pension-fund-platform.md)). **Co to NENÍ (řez S1):** evidence podílových jednotek ani platební engine. Zatím nevybírá příspěvky ani nic nevyplácí.

Dokumentaci služba publikuje na management endpointu `/q/openbank/docs` (Docs-as-Service, [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## TL;DR

- **Technologie:** Kotlin / Quarkus 3.x / Hibernate Reactive Panache / PostgreSQL (`pension-db`, CNPG)
- **Porty:** 8171 (aplikace), 8090 (management)
- **Události:** zatím žádné.
- **Autorizace:** `ROLE_API`, `ROLE_OPERATOR` nebo `ROLE_ADMIN`. Volající bez role zaměstnance musí poslat `X-Customer-Party-Id` a vidí jen smlouvy tohoto účastníka; cizí smlouva vrací 404. Zaměstnanci pouze čtou.
- **Idempotence:** hlavička `Idempotency-Key` je povinná u každého POST.
- **Jurisdikční balíčky:** `jurisdiction-packs/*.json`, načítané a validované při startu. Smlouva si fixuje verzi balíčku platnou ke dni vzniku. Balíčky CZ/DPS a CZ/DIP jsou referenční data čekající na právní revizi.

## API

| Metoda a cesta | Co dělá |
|---|---|
| `GET /api/v2/pension/contracts/{id}` | Načte smlouvu včetně historie strategií |
| `GET /api/v2/pension/transfers` | Vlastní převody účastníka, příchozí i odchozí (výchozí: jen probíhající) |
| `POST /api/v2/pension/transfers/{id}/withdrawal` | Odvolá odchozí převod (nebo souhlas s ním) před oceněním — podepsáno SCA |
| `PUT /api/v2/pension/contracts/{id}/strategy` | Zvolí nebo změní strategii (historie se zachová) |
| `POST /api/v2/pension/contracts/{id}/suspend` / `resume` | Přeruší / obnoví placení příspěvků |
| `POST /api/v2/pension/contracts/{id}/incentive-evaluation` | Pobídky fixovaného balíčku pro jeden příspěvek |
| `GET /api/v2/pension/contracts` | Vlastní smlouvy účastníka (personál: podle stavu) |
| `POST /api/v2/pension/simulations` | Ilustrativní projekce podle strategie (není poradenství) |
| `PUT /api/v2/pension/contracts/{contractId}/exit/payouts/{payoutId}/account` | Změna účtu pro výplatu se SCA, odkladem 3 dny a oznámením |
| `GET /api/v2/pension/operator/payouts`, `GET /api/v2/pension/death-claims` | Fronty pro personál |
| `GET /api/v2/pension/reporting/participant-aggregates` | Agregace za období pro výkazy ČNB: jen počty a součty, čte personál (#12425) |

Aktivace probíhá jen onboardingovým workflow (podepsaná žádost, lhůta na odstoupení, první
příspěvek nebo převod); ukončení je tok kotace a podpisu v `/exit`. Každý POST vyžaduje
`Idempotency-Key` a opakování dostane první odpověď.

## Heartbeat výplatního sweepu

Recovery sweep výplat při startu registruje workflow `pension-payout-schedule-sweep`. Očekávaný interval přebírá z `openbank.pension.payout-sweep.every` (výchozí `1h`), stejného nastavení jako scheduler. Dokončený sweep zaznamená heartbeat i bez opožděných splátek. Chyba čtení repository nebo restartu workflow úspěch nezaznamená.

`openbank_workflow_success_recorded` rozlišuje registrované workflow bez dokončeného běhu od úspěchu. `openbank_workflow_last_success_age_seconds` se inicializuje při registraci, takže samotná nízká hodnota po startu nedokazuje provedení sweepu. Stávající gauge opožděných splátek měří práci; heartbeat měří provedení.

Interval `off` nebo `disabled` vypíná plánování a nepublikuje heartbeat workflow.

## Vykazování účastníků pro výkazy ČNB

`GET /api/v2/pension/reporting/participant-aggregates` vrací agregace za jedno období pro výkazy
ČNB (#12425, ADR-0336 D4). Vrací pouze počty a součty — žádný atribut smlouvy, strany ani
účastníka. Počítá smlouvy v platnosti ke konci období podle produktové řady, věkového pásma a
stavu a součtuje příspěvky (podle zdroje včetně státních příspěvků) a výplaty. Jde o personální
reportovací čtení; nemění smlouvy a není to účastnický self-service endpoint.

Součty výplat se měří dle persistovaného času přechodu do SETTLED: vypořádací záznam uchovává
čas události `settledAt` přijatý z domestic-payment (migrace `V15__payout_settlement_time`).
Během postupného nasazení zůstávají starší události SETTLED bez `settledAt` v DLQ konzumenta
a nejsou tiše předatovány.

## Postupné nasazení interního TLS

GitOps přidává TLS 1.3 na portu 8443 vedle stávajícího HTTP listeneru. Cert-manager
vystavuje serverový certifikát interní CA; PEM certifikát a klíč se načítají
z připojeného adresáře a obnovují každou hodinu.

Nejprve nasaďte Certificate a serverový listener. Před přepnutím klienta ověřte
Certificate Ready, DNS SAN služby, důvěryhodný TLS handshake a síťové politiky pro
port 8443. HTTP zachovejte během souběhu verzí; jeho odstranění vyžaduje samostatnou
inventuru klientů a nasazení. Při chybě nového listeneru ponechte původní klientskou
URL a vraťte přidanou serverovou konfiguraci. Tato fáze ověřuje identitu serveru,
neposkytuje vzájemné TLS.

`ServerTlsIT` ověřuje načtení PEM, vyjednání TLS 1.3, odmítnutí nedůvěryhodného
certifikátu a zachování HTTP s dočasnými testovacími klíči. Deklarace ani tento
test nedokazují připravenost certifikátu v clusteru.

## Opakovatelné syntetické demo

Z kořene repozitáře spusťte `python3 openbank-pension-service/demo/pension_demo.py run`.
Návod `openbank-pension-service/demo/README.md` popisuje průběh prezentace, reset a důkazy.
Spouštěč vytvoří oddělené dočasné testovací kontexty penzijní služby Alpha/Beta, následně
samostatně ověří fondovou službu a uloží HTML/JSON report do `build/pension-demo/`.
Selhání, přeskočené testy ani staré výsledky nemohou vytvořit úspěšný report.

Volba Gradlu `-PpensionDemoCompany=alpha|beta` vybírá pouze testovací data; běžné testy a běhové
výchozí hodnoty se nemění. Demo nepotřebuje skutečnou identitu společnosti ani produkční
schválení vlastnictví dat. Používá syntetická data a simulované externí služby. Neprokazuje
izolaci současně běžících nasazení se skutečnými OIDC/OPA tokeny, skutečné vypořádání plateb
ani kompletně propojený produkční tok peněz. Tyto schopnosti zůstávají v #12472 a #12479.

## Provozní metriky

Management endpoint metrik publikuje čítače přechodů a výsledků penzijních operací včetně
spotřeby SCA, financování, státních příspěvků a změny výplatního účtu. Čítače popisují
pozorované výsledky aplikace; nedokládají vypořádání na externí platební infrastruktuře.
Stavové metriky ukazují stavy smluv a agregátů, velikosti front a stáří nejstarší položky.
Úloha `pension-state-gauges` je ve výchozím nastavení obnovuje každých 60 sekund
(`openbank.pension.state-gauges.every`). Nový pod nevystaví stavové série před prvním
úspěšným snapshotem. Při chybě obnovy zůstávají předchozí hodnoty; před jejich použitím
ověřte liveness úlohy. Štítky používají omezené kategorie, nikdy identifikátory stran či smluv.

## Formát komunikace s partnerem pro renty

Požadavky referenčnímu partnerovi vynechávají nepřítomné volitelné vlastnosti
a položky map s hodnotou null; neposílají místo nich JSON null. Odpověď partnera může přidat
pole bez porušení zpracování; desetinné částky a data zachovávají protokolový
kontrakt. Kontraktové testy ověřují odchozí požadavky a načítají dokumentované
příklady nabídek.

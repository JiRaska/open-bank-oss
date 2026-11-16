# Právní agenda v OpenBank Admin UI

Návrh obrazovek pro [ADR‑0381](../adr/0381-jurisdictional-legal-processes-and-protected-funds.md).
Pokrývá obsluhu došlých zpráv, ověřování, práci s případem a dohled nad konektory.

## Navigace a obrazovky

V navigačním katalogu dostane agenda jednoho vlastníka `legal-process`. Globální
vyhledávání indexuje názvy obrazovek a jejich synonyma. Vyhledávání případů běží
uvnitř agendy v rozsahu oprávnění operátora.

| Místo | Obrazovka | Co operátor potřebuje vidět a udělat |
| --- | --- | --- |
| `/parties/[id]`, pod vlastnostmi | **Právní omezení a zastoupení** | Účinný rozsah omezení jednání, zástupci, úkony vyžadující souhlas, čas platnosti a odkazy na přístupné případy. Přístup k existenci součinnosti se vyhodnocuje zvlášť. Plný doklad identity je v Party/PID. |
| `/accounts/[id]`, vedle zůstatku | **Právní účinky na účtu** | Po měně účtu stav blokace, rezervace, ochranný režim a chráněná disponibilita. Každá hodnota má zdroj a čas. Viditelné jsou účinky, které čekají na potvrzení z Balance nebo Ledgeru, a odkaz na důvod. |
| `/legal-process/enforcements` | **Exekuce** | Fronta s filtrem jurisdikce, bankovní entity, stavu, lhůty, týmu a vlastníka. Řádek obsahuje spis, orgán, nejbližší úkon, stav případu a stav účinku. Detail ukáže dotčené účty, částky a měny, pořadí, chráněné prostředky, změny, uvolnění a účetní potvrzení. |
| `/legal-process/assistance` | **Součinnosti** | Oddělená fronta pro posouzení pravomoci, rozsahu žádosti, lhůty a přípustného příjemce. Detail má kontrolovaný výběr podkladů, náhled odpovědi, schválení přesného rozsahu a stav odeslání a doručenky. Účinek na prostředky se vede jako samostatně doložený právní případ. |
| `/legal-process/inbox` | **Doručené zprávy** | Fronta příjmu s typem kanálu, stavem ověření, vytěžení a přiřazení. Detail vedle sebe zobrazí originál a návrh polí s odkazem na zdroj, verzi čtečky, místa k ruční kontrole a historii oprav. Převzít, přidělit týmu, připojit ke spisu a předat ke kontrole jsou samostatné auditované akce. |
| `/legal-process/connectors` | **Konektory a datové schránky** | Přehled podle entity, jurisdikce, poskytovatele a prostředí. Detail ukáže plán, poslední úspěšný průchod, pokrytí kontroly, mezery, obnovu, expiraci přístupu a verzi směrování. Správce může požádat o nový běh, pozastavit plán a předložit změnu konfigurace ke schválení. UI eviduje referenci na pověření ze správce tajemství a jeho stav. |

Pracovníci právní agendy mají přímý odkaz do existujícího **Schvalování**. Detail
odkazuje na stejný požadavek, aby případ a schválení nevedly dvě paralelní historie.
Technický průběh úlohy lze otevřít v Temporal se samostatným provozním oprávněním.
U klienta a účtu stačí stručný přehled s odkazy. Změna částky, uvolnění nebo schválení
odpovědi patří do příslušného spisu.

Zpráva může dokládat více úkonů a jeden případ může mít více zpráv včetně změn a
uvolnění. Inbox zachová odkazy na konkrétní přílohy a jejich přiřazení.

Detail má stabilní odkaz s interním ID. Referenční údaje z dokumentu se používají
jako označení a pro řízené vyhledávání. URL, klientská telemetrie a notifikace
neobsahují národní identifikátory ani text zprávy.

## Příjem a pracovní postup

1. **Zachytit doručení.** Rozlišit dodání poskytovatelem, právní doručení, technický import do banky a otevření operátorem. U každého času je zdroj a časová zóna. Lhůta má zobrazený právní základ, verzi jurisdikčního pravidla, výchozí událost a potvrzení operátora, pokud je vyžadováno. Při neúplném důkazu obrazovka zobrazí „Lhůta k ověření“. [Doručenky ISDS](https://datovka.gov.cz/info/cs/help/3.14.text.html)
2. **Ověřit a vytěžit.** Originál a doručenka mají neměnné reference. Stav ověření odesílatele, obálky, oprávnění orgánu, právního instrumentu a identity subjektu je samostatný. Strukturovaná příloha se zpracuje známým schématem. PDF, scan a nepodporované přílohy jdou k ručnímu ověření. Výstup OCR nebo modelu je návrh s dohledatelným zdrojem. Sporné přiřazení klienta řeší operátor přes FOS C4 se svázaným důkazem. Žádost policie se může zpracovat i ručně přes frontu součinností.
3. **Přidělit.** Směrování používá bankovní entitu, podporovaný kanál a typ agendy. Nejednoznačné případy mají frontu s jasným vlastníkem a eskalací lhůty. Ruční převzetí nebo předání má audit a kontrolu aktuální verze při souběhu dvou operátorů. Znovuzpracování čtečky založí nový pokus. Původní výsledek i ruční opravy zůstávají dohledatelné.
4. **Posoudit a schválit.** První operátor připraví konkrétní rozsah a druhý schválí tutéž verzi. Změna subjektu, částky, účtu, rozsahu odpovědi, příloh nebo příjemce vyžaduje nové schválení. Stav „Schváleno“ je rozhodnutí. „Účinné“ nebo „Doručeno“ vyžaduje odpovídající potvrzení z vlastnící služby nebo kanálu.
5. **Provést a smířit.** Exekuce vede samostatné potvrzení právního účinku, rezervace či uvolnění z Balance a příslušné revize v Ledgeru. Součinnost má samostatný stav odeslání, dohledání pokusu po nejednoznačné odpovědi a potvrzení příjemce. Opakování zpracování a kompenzace používají ID operace a revizi. Technická obnova vytvoří stopu v historii.

Kontrola schránky a vyčítání jsou úlohy konektoru. Ruční „Načíst nyní“ spouští
stejnou auditovanou úlohu jako plán, se stejným zámkem, deduplikací a režimem.
Pozastavení plánu signalizuje dobu bez kontroly i další plánovaný krok. Při opravě
mezery se pracuje s překryvem časového okna; posun potvrzeného pokrytí vyžaduje
doložit dokončení příjmu nebo evidovat otevřené mezery. Změnu směrování lze před
aktivací ověřit na vzorcích bez spuštění finanční akce. Pro CZ má karta název
**Datová schránka ISDS** a viditelně odděluje veřejný test od produkce. Modul
pro SK nebo DE dodá vlastní adaptéry a slovník, zatímco fronty a pravidla viditelnosti
použijí stejný model. [Rozhraní a testovací prostředí ISDS](https://datovka.gov.cz/info/cs/74.html)

## Implementační hranice a ověření

- Legal Process vlastní spisy, stav inboxu, přidělení, schválené rozhodnutí a výpočet lhůty z packu. Party/PID vlastní důkazy subjektu a jednající osoby. Account vlastní vazbu ochranného režimu na účet. Balance vlastní částky a jejich rozpad. Ledger vlastní rozhodnutí o zaúčtování a aplikovanou revizi. Souhrn zobrazí čas pozorování a nedostupný dílčí zdroj zvlášť.
- Browser volá autorizovaný Admin BFF podle ADR‑0056. Každá backendová operace ověřuje příslušnost k entitě, jurisdikci, konkrétnímu případu a účelu. Oprávnění pro metadata, obsah přílohy, přiřazení, návrh, schválení, odeslání a správu konektoru se testují samostatně. Zpřístupnění zdrojového dokumentu je auditovaná akce. Skryté položky se nezapočítávají do zobrazených počtů.
- Opětovné čtení po konfliktu verze ukáže aktuální stav a zachová obsluze její koncept. Chybu zdroje, nedostatečné oprávnění, čekání na potvrzení a ověřený prázdný výsledek reprezentují odlišné stavy. Při stárnutí snapshotu vyžadují akce novou validaci v backendu.
- UI využije `PageHeader`, `StatusBadge`, `EmptyState`, `DataUnavailable` a stávající CS/EN lokalizaci. První provozní řez je inbox s oprávněným zobrazením uložených dokladů a přidělením v jedné frontě. Souhrny klienta a účtu se připojí k autoritativním projekcím. Další řez doplní schvalování a potvrzení účinku či odeslání. Správa schránek zpřístupní jen akce podporované skutečným adaptérem.
- Pro případný první release ověřit průchod zprávy od přijetí přes ruční opravu a přidělení k potvrzenému účinku. Negativní scénáře zahrnou duplikát, neúplnou nebo neověřenou zprávu, konflikt dvou operátorů, pokus o vlastní schválení, změněnou verzi odpovědi, výpadek potvrzení a nepovolený přístup z jiného týmu nebo entity. Před aktivací zopakovat s veřejným testovacím ISDS a reálnými úložnými a rozhodovacími službami.

Tento návrh je podkladem k implementaci. Stávající import CZ ukládá podepsané
originály a stav k posouzení. Vydání a spotřeba povolení v Ledgeru jsou ověřeny
na PostgreSQL se syntetickou rezervací. Externí konformita ISDS, živý důkaz FOS C4,
skutečná právní rezervace v Balance a průchod celou platební cestou jsou evidovány
v [#16324](https://github.com/JiRaska/open-bank-oss/issues/16324).

## První řez v repozitáři

`/legal-process/inbox` používá session BFF `/api/legal-inbox` a předává token
operátora do REST služby. Pro přístup musí token obsahovat `ROLE_LEGAL_INBOX_VIEW`
a alespoň jednu roli `ROLE_LEGAL_MAILBOX_<namespace>`. K suffixu patří stejný
namespace jako u konkrétního importéru. Schránky musí mít jedinečný namespace
v rámci databáze i při rozdílném prostředí nebo bankovní entitě. Zobrazení vytěženého
obsahu a stažení ZFO vyžaduje samostatné `ROLE_LEGAL_INBOX_EVIDENCE`. Přidělení
vyžaduje `ROLE_LEGAL_INBOX_ASSIGN`. Tým v tomto řezu určuje vlastníka úkolu.
Hranicí viditelnosti zůstává oprávnění ke schránce. Audit používá subjekt z tokenu
ověřeného vůči nakonfigurovanému issueru a audience.

Vytěžení čte věc, referenci odesílatele a počet příloh z uložené CMS obálky.
Neznámá struktura nebo nesouhlas identifikátoru zprávy vede k ručnímu posouzení.
Přílohy zůstávají v originálu. Ověření podpisového řetězce, OCR, rozhodování
a aplikace právního účinku jsou další kroky. [Technická dokumentace ISDS](https://testrs.gov.cz/smlouva/soubor/303682/WS-manipulace-s-datovymi-zpravami.pdf)

Pro lokální ověření z kořene repozitáře:

```sh
./gradlew :openbank-legal-process-service:test :openbank-legal-process-service:quarkusBuild
(cd openbank-admin-ui && npm ci && npx playwright install chromium)
bash openbank-admin-ui/e2e/run-legal-inbox-postgres.sh
```

Browser scénář začíná prázdnou databází. Spustí lokální SOAP simulátor a stejný
cyklus workeru, který volá plánovač. Worker vyčte seznam a stáhne originál i
doručenku. Přes skutečný BFF test ověří identické bajty, vytěžení, přidělení a
obnovení stránky. Tentýž test ověří také digest a audit přidělení v PostgreSQL.

## Vyčítání veřejného testu ISDS

Worker je standardně vypnutý. Pro veřejný test se konfiguruje
`LEGAL_ISDS_POLL_ENABLED=true`, `LEGAL_ISDS_POLL_TEST_MAILBOX` se jménem
začínajícím `test-`, `LEGAL_ISDS_POLL_FIRST_FROM` s počátkem v ISO 8601 a
`LEGAL_ISDS_POLL_BASIC` s hodnotou Basic pověření (Base64 uživatele a hesla) z runtime správce tajemství. Provozní
identita musí mít přístup ke schránce odpovídající tomuto namespace. Worker
používá nejméně dvě souběžná spojení k PostgreSQL.

Jedna instance za schránku zpracovává překrývající se okna. Plný seznam rozdělí
na menší okna. Posun okna a uložení očekávajících ID proběhnou společně.
Každý originál s doručenkou se uloží stejným importérem jako dosud. Neúspěšný
import zůstane ve frontě a opakuje se s odstupem. Uložené doklady se při běžném
průchodu nestahují znovu. Částečný nebo přerušený běh je dohledatelný.
Na záložce `/legal-process/connectors` operátor vidí historii v rozsahu svého
oprávnění ke schránce, včetně počtu zpráv čekajících na import.

Volání seznamu může způsobit doručení. Aktivace produkčního přístupu, jeho
provozní schválení a ověření velkoobjemových zpráv zůstávají součástí
konformity. Současný worker používá jen veřejný test ISDS.

### Živý konformační průchod

Do vyhrazené schránky veřejného testu připravte obyčejnou syntetickou zprávu
s unikátním předmětem. Přístup k testovací schránce se zřizuje v klientském
portálu. [Nápověda ke zřízení testovací schránky](https://datovka.gov.cz/info/cs/help/8.9.text.html)

Do prostředí procesu předejte uživatelské pověření jen lokálně. Test očekává
následující proměnné:

| Proměnná | Hodnota |
| --- | --- |
| LEGAL_ISDS_POLL_BASIC | Basic pověření z lokálního správce tajemství |
| LEGAL_ISDS_POLL_TEST_MAILBOX | Lokální namespace schránky s prefixem test- |
| LEGAL_ISDS_POLL_FIRST_FROM | Čas v ISO 8601 těsně před dodáním testovací zprávy |
| LEGAL_INBOX_E2E_DELIVERY_ID | ID syntetické zprávy v ISDS |
| LEGAL_INBOX_E2E_SUBJECT | Přesný předmět syntetické zprávy |

Po zabalení Quarkusu a instalaci závislostí pro Chromium spusťte
**bash openbank-admin-ui/e2e/run-legal-inbox-postgres.sh --live-isds**.
Izolovaný PostgreSQL začíná prázdný. Worker smí volat výhradně veřejný test ISDS.
Runner čeká na originál a doručenku od workeru a zkontroluje přítomnost času
doručení v obálce. Chromium stáhne oba doklady přes BFF, porovná jejich bajty
s uloženými a ověří předmět, přidělení a historii vyčítání. Závěrečný dotaz
ověří digest obou dokladů, tým, revizi a audit právě této zprávy. Kontrola času
v obálce sama neověřuje důvěryhodnost podpisového řetězce.

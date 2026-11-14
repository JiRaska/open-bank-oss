# Průběžné monitorování schválených firemních případů — openbank-kyb-service

Entita a její spřízněné osoby se prověřují při schválení firemního případu. Sankční nebo PEP seznam
se může změnit i potom. Tato stránka popisuje, jak služba drží schválené případy pod monitoringem
služby `openbank-sanctions-service` a co se stane, když je nahlášena shoda (ADR-0376).

Funkce je ve výchozím stavu vypnutá: `openbank.kyb.monitoring.enabled=false`. Je to jeden přepínač.
Když je vypnutý, služba nic neregistruje a neotevírá konzumenta nálezů. Jediné volání, které
dělá dál, je odhlášení subjektu, který registr už drží z doby, kdy byl přepínač zapnutý.

## Kdo je monitorován

Každý aktuální prověřený subjekt schváleného případu: entita, každý zástupce uvedený v rejstříku,
každý vykazovaný skutečný majitel a každá osoba uvedená v prohlášeních.

Rozhoduje o tom jedno pravidlo na jednom místě. Případ je monitorován, dokud:

- je ve stavu `SIGNED` nebo `ACTIVE`, nebo má nález z monitoringu, který není rozhodnutý nebo je
  potvrzenou sankční shodou, a
- nebylo zaznamenáno, že vztah byl ukončen.

Žadatel, který případ opustí, časovač ani zamítnutí tedy ukončí monitoring jen tehdy, když případ
nedrží žádný nález. Případ s otevřeným nálezem nebo potvrzenou sankční shodou zůstává monitorován,
dokud operátor nezaznamená ukončení vztahu.

Když není dostupný rejstřík skutečných majitelů, žádný subjekt se nepovažuje za odešlý z případu.

## Reference v registru

Registru se pro každý subjekt posílá reference. Je to náhodné UUID, které vygeneruje databáze ve
chvíli, kdy subjekt poprvé dlužíme registru. Není odvozená ze jména ani z identifikátoru, nikdy se
nemění a případy ji nesdílejí. Prověrka při onboardingu se nemění: posílá stejné id subjektu a
ukládá stejná data jako před touto funkcí.

Důsledek: vyjasnění, které analytik zaznamená v sanctions-service k prověrce při onboardingu, je
uloženo pod id prověrky, ne pod referencí v registru. První vyhodnocení subjektu registrem proto
může zopakovat nález, který byl při onboardingu už vyjasněn. Operátor o něm na případu rozhodne
znovu.

## Jak se registrace doručuje

1. Každý zápis případu, který byl někdy schválen, upraví ve stejné transakci tabulku
   `kyb_monitored_subjects`, jedním SQL příkazem. Subjekt, který má být monitorován, má požadovaný
   stav `REGISTERED`; každý jiný řádek případu má `WITHDRAWN`. Schválení se nemůže potvrdit, aniž
   by vznikl dluh registrací. Případ, který nikdy schválen nebyl, nestojí žádný příkaz navíc.
2. Plánované relé běží každých 30 sekund. Bere vždy jeden dlužný řádek pod 60sekundovou výpůjčkou
   a posílá `PUT` nebo `DELETE /api/v3/sanctions/monitored-subjects/{subjectRef}` jako vlastní
   klient služby `openbank-kyb`. Tělo nese typ entity a jméno, přečtené z případu v tom okamžiku.
   Dvě relé nemohou poslat stejný řádek současně a výsledek se zapíše jen tehdy, když řádek stále
   nese stejné přání a stejnou výpůjčku.
3. Výsledek volání rozhodne, co bude dál:
   - Přijato: řádek je `SYNCED`.
   - 400 nebo 422: validace registru tento subjekt odmítá. Řádek přejde do `REFUSED`. Pošle se
     znovu při příštím zápisu případu a při příštím opětovném potvrzení. Subjekt je schválen a není
     monitorován; pohled operátora ukazuje `monitoringState: REFUSED`.
   - 401 nebo 403: registr nepřijímá volajícího. Řádek zůstává `PENDING` a zkouší se znovu. Spustí
     se kritický alert.
   - Cokoli jiného (žádná odpověď, timeout, 404, 409, 429, 5xx): řádek zůstává `PENDING` a zkouší
     se znovu po 30 sekundách, pak se zdvojnásobující se prodlevou až do jedné hodiny. Limit pokusů
     není.

Tabulka drží referenci, klíč subjektu uvnitř případu a stav doručení. Klíč osoby je odvozen ze
jména a je osobním údajem. Zůstává v této databázi a registru se neposílá.

## Oprava

- **Případ zapsaný předchozím vydáním.** Každý zápis tímto vydáním zaznamená verzi případu, se
  kterou nechal řádky v souladu. Relé najde schválené nebo ukončené případy, kde se tento záznam
  liší od verze případu, a zapíše je znovu. To pokrývá případy schválené před touto funkcí i každý
  případ, který pod předchozího vydání zapsal během postupného nasazení nebo po rollbacku.
  Předchozí vydání nemůže data monitoringu odstranit: reference a nálezy jsou v tabulkách, do
  kterých nezapisuje.
- **Registr, který ztratil stav.** Potvrzená nebo odmítnutá registrace se pošle znovu po
  `openbank.kyb.monitoring.reassert-after` (výchozí 7 dní). Registrace je idempotentní. Chcete-li
  všechny subjekty zaregistrovat dříve, snižte tuto hodnotu, počkejte, až se
  `openbank_kyb_monitoring_registrations_pending` vrátí na 0, a hodnotu vraťte.

## Co udělá nález

Služba konzumuje `openbank.sanctions.screening.event` ve skupině konzumentů
`kyb-service-sanctions-monitoring` a typ události čte z Kafka hlavičky `ce-type`. Událost
`MONITORED_SUBJECT_MATCHED`, jejíž `ownerService` je `openbank-kyb`, se zaznamená na případ, který
drží referenci, do tabulky `kyb_monitoring_findings`.

Každý nález má vlastní rozhodnutí. Pozdější nález o stejném subjektu dřívější rozhodnutí neotevírá
ani nenahrazuje. Stejný nález doručený dvakrát nic nezmění.

Případ se nemění: `status` zůstává, `updatedAt` se neposune a nepublikuje se žádná událost
životního cyklu. Každá cesta, kterou může volat žadatel, odpovídá stejně jako předtím.

KYC revizoři (`ROLE_KYC_REVIEWER`) pracují čtyřmi cestami, každá má vlastní akci:

1. `GET /api/v1/kyb/monitoring/holds` vypíše případy, které čekají na revizora: nález
   k rozhodnutí nebo ukončení vztahu k zaznamenání (`kyb.monitoring.queue.list`).
2. `POST /api/v1/kyb/cases/{id}/monitoring/findings/{findingId}/disposition` rozhodne jeden nález:
   `CLEARED_FALSE_POSITIVE` nebo `CONFIRMED`, s odůvodněním. Rozhoduje se jednou
   (`kyb.monitoring.finding.decide`).
3. `POST /api/v1/kyb/cases/{id}/monitoring/exit/propose` navrhne, s důkazem, že vztah byl
   ukončen (`kyb.monitoring.exit.propose`).
4. `POST /api/v1/kyb/cases/{id}/monitoring/exit/complete` návrh potvrdí. Potvrzující revizor
   nesmí být navrhovatel a zopakuje důkaz z návrhu (`kyb.monitoring.exit.confirm`).

Návrh i potvrzení se přijmou jen tehdy, když je ukončení dlužné, žádný nález není nerozhodnutý
a vztah prokazatelně skončil: případ je `REJECTED` nebo `ABANDONED` a party-service hlásí entitní
stranu jako `CLOSED`. Do té doby zůstává případ monitorován.

Případ s otevřeným nálezem se při aktivaci entitní strany nestane `ACTIVE`. Zůstane `SIGNED`, což
vypadá stejně jako strana, která ještě čeká na KYC kontrolu. Rozhodnutí, které uvolní poslední
blokaci, ho převede do `ACTIVE` a publikuje `completed`. Po potvrzené sankční shodě se neaktivuje
nikdy.

Potvrzená PEP shoda je rizikový fakt. Nevyžaduje ukončení vztahu a nic nepublikuje.

Potvrzená shoda jakéhokoli jiného druhu znamená, že vztah musí skončit. První taková nastaví
`monitoring.exitOwedSince`. Tento stav nikdo nenastavuje ručně: čte se z nálezů. Nic se o něm
nepublikuje: topic životního cyklu čte projekce pro zákazníky i analytický sklad. Případ si
ponechá stav a zůstává monitorován. Vztah zatím žádná služba neukončuje (#15992), dělá se ručně.
Zaznamenání ukončení ukončí monitoring a odhlásí subjekty z registru.

Záznam typovaný jako nález bez použitelného `ownerService`, `findingId` nebo `subjectRef` se
započítá jako `malformed` a pošle se do dead-letter topicu
`openbank.dlq.kyb.sanctions-monitoring-in`.

## Metriky

| Metrika | Význam |
|---|---|
| `openbank_kyb_monitoring_enabled` | 1 nebo 0: přepínač |
| `openbank_kyb_monitoring_registrations_total{outcome,reason}` | pokusy relé: `registered`, `withdrawn`, `failed`, `unauthorised`, `refused` |
| `openbank_kyb_monitoring_registrations_pending` | řádky, které registru stále dlužíme |
| `openbank_kyb_monitoring_registrations_oldest_pending_age_seconds` | stáří nejstaršího dlužného řádku |
| `openbank_kyb_monitoring_registrations_refused` | subjekty, které registr odmítá: schválené a nemonitorované |
| `openbank_kyb_monitoring_subjects_registered` | řádky, které registr potvrdil |
| `openbank_kyb_monitoring_findings_total{outcome}` | záznamy na kanálu nálezů podle výsledku |
| `openbank_kyb_monitoring_reviews_open` | případy s nerozhodnutým nálezem |
| `openbank_kyb_monitoring_reviews_oldest_open_age_seconds` | stáří nejstaršího nerozhodnutého nálezu |
| `openbank_kyb_monitoring_exits_owed` | případy s potvrzenou sankční shodou a bez zaznamenaného ukončení |
| `openbank_kyb_monitoring_exits_oldest_owed_age_seconds` | stáří nejstaršího takového případu |
| `openbank_kyb_monitoring_cases_unreconciled` | schválené nebo ukončené případy, jejichž řádky nejsou v souladu s případem |
| `openbank_kyb_monitoring_cases_unscreened` | schválené případy bez prověrky, které nelze zaregistrovat |
| `openbank_kyb_monitoring_runs_total{outcome}` | běhy relé: `completed`, `disabled`, `failed` |

Měřidla čte z databáze plánovaný běh. Do prvního běhu ukazují NaN.

`registered` znamená, že registr subjekt přijal. Vyhodnotí ho ve vlastním průchodu.

Výsledky `openbank_kyb_monitoring_findings_total`: `review_opened`, `duplicate`, `not_monitored`,
`unknown_subject`, `foreign_owner`, `ignored`, `malformed`, `unrecognised`.

## Alerty

- `KybMonitoringRegistrationStuck` (kritický): nejstarší dlužný řádek je starší než 30 minut a
  přepínač je zapnutý.
- `KybMonitoringRegistryUnauthorised` (kritický): registr odpověděl 401 nebo 403.
- `KybMonitoringSubjectRefused` (varování): registr odmítá alespoň jeden subjekt.
- `KybMonitoringCaseUnreconciled` (varování): případ zůstal 30 minut mimo soulad a přepínač je
  zapnutý.
- `KybMonitoringReviewUnresolved` (varování): nález je den nerozhodnutý.
- `KybMonitoringExitOwed` (kritický): ukončení vztahu je dlužné a není zaznamenané.
- `KybMonitoringFindingNotRouted` (varování): záznam skončil výsledkem `unrecognised`, `malformed`,
  `unknown_subject` nebo `not_monitored`.
- `KybMonitoringFindingDeadLettered` (kritický): nález skončil v dead-letter topicu.
- `KybMonitoringApprovedCaseUnscreened` (varování): schválený případ nemá prověrku a přepínač je
  zapnutý.

## Zapnutí

Image, Kafka ACL a dead-letter topic lze nasadit v libovolném pořadí, dokud je přepínač vypnutý.
Skupina konzumentů a politika offsetu jsou v image, takže není potřeba žádný override ConfigMap.

1. Nastavte `openbank.sanctions.monitoring.enabled=true` v sanctions-service. Bez toho je každá
   registrace odmítnuta s 503 a zůstává dlužná.
2. Nastavte `openbank.kyb.screening.enabled=true`. Případ schválený bez prověrky nemá subjekty.
3. Nastavte `openbank.kyb.monitoring.enabled=true`.

Vypnutí: nastavte `openbank.kyb.monitoring.enabled=false`. Už zaregistrované subjekty zůstávají
zaregistrované a nálezy o nich počkají v topicu, dokud přepínač znovu nezapnete. Subjekty
případů, které mezitím skončí, se přesto odhlásí.

Rollback na předchozí vydání nevyžaduje žádnou změnu dat. Předchozí vydání nové tabulky a sloupce
ignoruje. Případy, které zapíše, se uvedou do souladu, až toto vydání znovu poběží. Monitoring
nejdřív vypněte: předchozí vydání aktivuje blokovaný případ a zaznamená ukončení na slovo jednoho
operátora.

## Omezení

- Registru se posílá jen jméno a typ entity. Shoda je podle jména.
- Vyjasnění zaznamenané v sanctions-service k prověrce při onboardingu se na referenci v registru
  nevztahuje (viz výše).
- Ukončení vztahu není automatizované. Tato služba zaznamená rozhodnutí a, na slovo dvou
  revizorů, že vztah byl ukončen. Případ `ACTIVE` zde dnes nemůže stav `ACTIVE` opustit, takže
  jeho ukončení zaznamenat nelze a zůstává monitorován (#15992).
- Případ schválený při vypnuté prověrce není monitorován.
- V admin UI zatím není obrazovka pro operátora. Rozhraním revizora jsou čtyři cesty výše.

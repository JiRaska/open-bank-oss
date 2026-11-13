# Průběžné monitorování schválených firemních případů — openbank-kyb-service

Subjekt a osoby s ním spojené se prověřují při schválení firemního případu. Sankční seznam nebo
seznam PEP se ale může změnit i později. Tato stránka popisuje, jak služba drží schválené případy
pod monitorováním ve službě `openbank-sanctions-service` a co se stane, když je nahlášena shoda
(ADR-0376).

Funkce je ve výchozím stavu vypnutá: `openbank.kyb.monitoring.enabled=false`.

## Kdo je monitorován

Každý aktuální prověřený subjekt schváleného případu: samotný subjekt, každý zástupce uvedený
v rejstříku, každý vykazovaný skutečný majitel a každá osoba uvedená v prohlášeních. Případ je
schválený, pokud je ve stavu `SIGNED` nebo `ACTIVE`. U případu v jiném stavu není monitorován
nikdo. Revize kvůli monitorování stav nemění, takže monitorování nikdy neukončí.

Každý subjekt má referenci. Prověření při onboardingu ji posílá jako `subjectId` a registr je podle
ní klíčován, takže výjimka, kterou analytik zaznamená ve službě sanctions-service, platí i pro
monitorování.

- Samotný subjekt: identifikátor jeho strany (party id). Subjekt se neprověřuje dříve, než stranu má.
- Osoba prověřená touto nebo novější verzí: náhodné UUID. Není odvozeno ze jména.
- Osoba prověřená starší verzí: identifikátor, který tehdy prověření použilo
  (`kyb-case:<případ>:<klíč>`). Pod ním je ve službě sanctions-service uložena kontrola i její
  výjimka, proto zůstává.

Reference se nikdy nemění. Subjekt, který z případu odejde, zůstává uložen s příznakem
`current: false` a se svou referencí. Pokud není dostupný rejstřík skutečných majitelů, žádný
subjekt se nepovažuje za odešlý.

## Jak se registrace doručuje

1. Každý zápis schváleného nebo ukončeného případu upraví ve stejné transakci tabulku
   `kyb_monitored_subjects`, a to jedním příkazem SQL. Subjekt, který
   má být monitorován, dostane řádek s požadovaným stavem `REGISTERED`. Subjekt případu, který
   skončil, nebo subjekt, který z případu odešel, dostane `WITHDRAWN`. Schválení se proto nemůže
   potvrdit, aniž by vznikl závazek registrace.
2. Plánované relé běží každých 30 sekund. Pro každý dlužný řádek pošle `PUT` nebo `DELETE
   /api/v3/sanctions/monitored-subjects/{subjectRef}` pod vlastním klientem služby `openbank-kyb`.
   Tělo obsahuje typ subjektu a jméno, které se v tu chvíli přečte z případu.
3. Výsledek volání určí další postup:
   - Přijato: řádek je `SYNCED`.
   - Žádná odpověď, vypršení času, 5xx, 408 nebo 429: řádek zůstává `PENDING` a volání se opakuje po
     30 sekundách, pak s dvojnásobnou prodlevou až do jedné hodiny, potom každou hodinu. Počet
     pokusů není omezen.
   - 401 nebo 403: registr volajícího nepřijímá. Řádek zůstává `PENDING` a volání se opakuje stejně.
     Spustí se kritická výstraha.
   - Jiná odpověď 4xx: registr tento subjekt odmítá. Řádek přejde do stavu `REFUSED` a neopakuje se,
     dokud se případ nezmění nebo dokud není řádek znovu potvrzován. Subjekt je schválený a není
     monitorován. Pohled operátora na případ ukazuje u subjektu `monitoringState: REFUSED`.

Tabulka obsahuje reference a stav doručení. Neobsahuje žádné jméno.

## Oprava

- **Případy schválené před touto funkcí.** Každý běh najde schválené případy, které mají prověření
  a žádné řádky, přidělí jejich subjektům reference a zaregistruje je.
- **Registr, který ztratil stav.** Potvrzená nebo odmítnutá registrace se pošle znovu po uplynutí
  `openbank.kyb.monitoring.reassert-after` (výchozí hodnota 7 dní). Registrace je idempotentní.
  Chcete-li znovu zaregistrovat všechny subjekty dříve, snižte tuto hodnotu, počkejte, až se
  `openbank_kyb_monitoring_registrations_pending` vrátí na 0, a pak ji vraťte zpět.

## Co způsobí nález

Služba čte téma `openbank.sanctions.screening.event` a typ události bere z hlavičky Kafka `ce-type`.
Událost `MONITORED_SUBJECT_MATCHED`, jejíž `ownerService` je `openbank-kyb`, se použije na případ,
který referenci drží:

- Nález se uloží k prověřenému subjektu: seznam, reference položky seznamu, skóre a způsob zjištění.
  Shoda typu PEP nastaví příznak PEP. Každý jiný typ se uloží jako shoda se sankčním seznamem.
- Rozhodnutí o subjektu se změní na `OPEN`. Dřívější rozhodnutí zůstane v historii.
- Na případu se otevře revize kvůli monitorování (`monitoringReview.openedAt`).

Stav případu se nemění. Zůstává `SIGNED` nebo `ACTIVE`, hodnota `updatedAt` se neposune a
nepublikuje se žádná událost životního cyklu. Zákazník i všechny ostatní služby čtou stejný případ
jako předtím. `GET /api/v1/kyb/cases?status=MANUAL_REVIEW` vrací také schválené případy s otevřenou
revizí kvůli monitorování, takže je operátoři najdou ve stávající frontě revizí.

Operátor potom použije stávající operace:

1. Rozhodne o nálezu: `POST /api/v1/kyb/cases/{id}/screening/findings/{subjectKey}/disposition`.
2. Pokud není potvrzen žádný sankční nález, uzavře revizi:
   `POST /api/v1/kyb/cases/{id}/review/resolve`. Nic dalšího se nezmění.
3. Pokud má vztah skončit, zavolá `POST /api/v1/kyb/cases/{id}/reject`. U případu s revizí kvůli
   monitorování tato operace případ nezamítne. Zaznamená, že je vyžadováno ukončení vztahu
   (`monitoringReview.exitRequiredAt` a `exitReason`), a jednou publikuje událost
   `BUSINESS_RELATIONSHIP_EXIT_REQUIRED`.

Po vyžádání ukončení si případ ponechá svůj stav a zůstává monitorován. Na událost zatím žádná
služba nereaguje. Výstraha `KybMonitoringExitRequired` trvá, dokud ukončení někdo neprovede ručně.

Stejný nález doručený dvakrát nic nezmění: jeho identifikátor už na případu je.

Záznam označený jako nález, který nemá použitelné `ownerService`, `findingId` nebo `subjectRef`, se
započítá jako `malformed` a odešle se do tématu `openbank.dlq.kyb.sanctions-monitoring-in`.

Nálezy se zpracovávají při zapnutém i vypnutém přepínači. Přepínač zastavuje pouze relé.

## Metriky

| Metrika | Význam |
|---|---|
| `openbank_kyb_monitoring_enabled` | 1 nebo 0: přepínač |
| `openbank_kyb_monitoring_registrations_total{outcome,reason}` | pokusy relé: `registered`, `withdrawn`, `failed`, `unauthorised`, `refused` |
| `openbank_kyb_monitoring_registrations_refused` | subjekty, které registr odmítá: schválené a nemonitorované |
| `openbank_kyb_monitoring_exits_required` | schválené případy, u kterých má vztah skončit |
| `openbank_kyb_monitoring_registrations_pending` | řádky, které registr ještě nedostal |
| `openbank_kyb_monitoring_registrations_oldest_pending_age_seconds` | stáří nejstaršího dlužného řádku |
| `openbank_kyb_monitoring_subjects_registered` | řádky, které registr potvrdil |
| `openbank_kyb_monitoring_findings_total{outcome}` | záznamy na kanálu nálezů podle výsledku |
| `openbank_kyb_monitoring_reviews_open` | schválené případy v revizi kvůli monitorování |
| `openbank_kyb_monitoring_reviews_oldest_open_age_seconds` | stáří nejstarší takové revize |
| `openbank_kyb_monitoring_cases_unscreened` | schválené případy bez prověření, které nelze zaregistrovat |
| `openbank_kyb_monitoring_runs_total{outcome}` | běhy relé: `completed`, `disabled`, `failed` |

Hodnoty ukazatelů čte plánovaný běh z databáze. Do prvního běhu mají hodnotu NaN.

`registered` znamená, že registr subjekt přijal. Vyhodnotí ho až při vlastním průchodu.

Výsledky v `openbank_kyb_monitoring_findings_total`: `review_opened`, `duplicate`, `not_monitored`,
`unknown_subject`, `foreign_owner`, `ignored`, `malformed`, `unrecognised`.

## Výstrahy

- `KybMonitoringRegistrationStuck` (critical): nejstarší dlužný řádek je starší než 30 minut a
  přepínač je zapnutý.
- `KybMonitoringReviewUnresolved` (warning): revize kvůli monitorování je otevřená jeden den.
- `KybMonitoringFindingNotRouted` (warning): záznam měl výsledek `unrecognised`, `malformed`,
  `unknown_subject` nebo `not_monitored`.
- `KybMonitoringRegistryUnauthorised` (critical): registr odpověděl 401 nebo 403.
- `KybMonitoringSubjectRefused` (warning): registr odmítá alespoň jeden subjekt.
- `KybMonitoringApprovedCaseUnscreened` (warning): schválený případ nemá prověření a přepínač je
  zapnutý.
- `KybMonitoringExitRequired` (critical): ukončení vztahu je vyžadováno a ještě nebylo provedeno.
- `KybMonitoringFindingDeadLettered` (critical): nález se dostal do tématu nedoručených zpráv.

## Zapnutí

1. Nasaďte image. Konzument nálezů se pak připojí k náhradní skupině konzumentů.
2. V samostatné změně přidejte do `kyb-service-msg-override` dva řádky pro kanál
   `sanctions-monitoring-in`. Pokud tam kanál uvedete dříve, než ho běžící image obsahuje, služba
   se při startu zastaví.
3. Ve službě sanctions-service nastavte `openbank.sanctions.monitoring.enabled=true`. Bez toho je
   každá registrace odmítnuta s kódem 503.
4. Nastavte `openbank.kyb.screening.enabled=true`. Případ schválený bez prověření nemá žádné
   subjekty.
5. Nastavte `openbank.kyb.monitoring.enabled=true`.

Vypnutí: nastavte `openbank.kyb.monitoring.enabled=false`. Již zaregistrované subjekty zůstanou
zaregistrované.

## Omezení

- Registr dostává pouze jméno a typ subjektu. Shoda se hledá podle jména.
- První vyhodnocení subjektu v registru může zopakovat nález, který už vznikl při onboardingu.
  Každý takový nález otevře revizi.
- Ukončení vztahu není automatizováno. Tato služba zaznamená rozhodnutí a publikuje událost.
- Případ schválený při vypnutém prověřování není monitorován.

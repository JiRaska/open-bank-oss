# Průběžné monitorování schválených firemních případů — openbank-kyb-service

Subjekt a osoby s ním spojené se prověřují při schválení firemního případu. Sankční seznam nebo
seznam PEP se ale může změnit i později. Tato stránka popisuje, jak služba drží schválené případy
pod monitorováním ve službě `openbank-sanctions-service` a co se stane, když je nahlášena shoda
(ADR-0376).

Funkce je ve výchozím stavu vypnutá: `openbank.kyb.monitoring.enabled=false`.

## Kdo je monitorován

Každý aktuální prověřený subjekt schváleného případu: samotný subjekt, každý zástupce uvedený
v rejstříku, každý vykazovaný skutečný majitel a každá osoba uvedená v prohlášeních. Případ je
schválený, pokud je ve stavu `SIGNED` nebo `ACTIVE`, a také pokud je ve stavu `MANUAL_REVIEW` kvůli
nálezu z monitorování. U případu v jiném stavu není monitorován nikdo.

Každý subjekt má neprůhlednou referenci: u subjektu je to identifikátor jeho strany (party id),
u všech ostatních náhodné UUID. Stejnou hodnotu posílá prověření při onboardingu jako `subjectId`,
takže výjimka, kterou analytik zaznamená ve službě sanctions-service, platí i pro monitorování.
Reference není odvozena ze jména.

## Jak se registrace doručuje

1. Každý zápis případu upraví ve stejné transakci tabulku `kyb_monitored_subjects`. Subjekt, který
   má být monitorován, dostane řádek s požadovaným stavem `REGISTERED`. Subjekt případu, který
   skončil, nebo subjekt, který z případu odešel, dostane `WITHDRAWN`. Schválení se proto nemůže
   potvrdit, aniž by vznikl závazek registrace.
2. Plánované relé běží každých 30 sekund. Pro každý dlužný řádek pošle `PUT` nebo `DELETE
   /api/v3/sanctions/monitored-subjects/{subjectRef}` pod vlastním klientem služby `openbank-kyb`.
   Tělo obsahuje typ subjektu a jméno, které se v tu chvíli přečte z případu.
3. Odmítnuté nebo neúspěšné volání se opakuje: po 30 sekundách, pak s dvojnásobnou prodlevou až do
   jedné hodiny, potom každou hodinu. Počet pokusů není omezen.

Tabulka obsahuje reference a stav doručení. Neobsahuje žádné jméno.

## Oprava

- **Případy schválené před touto funkcí.** Každý běh najde schválené případy, které mají prověření
  a žádné řádky, přidělí jejich subjektům reference a zaregistruje je.
- **Registr, který ztratil stav.** Potvrzená registrace se pošle znovu po uplynutí
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
- Případ přejde do stavu `MANUAL_REVIEW`. Původní stav se uloží do `monitoringReview.from`.

Operátor potom použije stávající operace: rozhodne o nálezu
(`POST /api/v1/kyb/cases/{id}/screening/findings/{subjectKey}/disposition`) a pak uzavře revizi
(`POST /api/v1/kyb/cases/{id}/review/resolve`), nebo případ zamítne. Uzavření revize vrátí případ
do stavu `SIGNED`, nebo do stavu `ACTIVE`, pokud je strana subjektu aktivní. Uzavření je odmítnuto,
dokud je některý nález otevřený, a také po potvrzení sankčního nálezu. Žádné oprávnění se neuděluje
znovu a žádná událost životního cyklu se neopakuje.

Stejný nález doručený dvakrát nic nezmění: jeho identifikátor už na případu je.

Zákazník žádnou změnu neuvidí. V odpovědích pro zákazníka zůstává ve `status` stav, ve kterém byl
případ schválen, a `reviewReason`, `screening` i `monitoringReview` jsou null. K revizi se
nepublikuje žádná událost životního cyklu. Žadatel nemůže případ během revize opustit a neukončí ho
ani časovač nečinnosti.

Nálezy se zpracovávají při zapnutém i vypnutém přepínači. Přepínač zastavuje pouze relé.

## Metriky

| Metrika | Význam |
|---|---|
| `openbank_kyb_monitoring_enabled` | 1 nebo 0: přepínač |
| `openbank_kyb_monitoring_registrations_total{outcome,reason}` | pokusy relé: `registered`, `withdrawn`, `failed` |
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
- `KybMonitoringFindingNotRouted` (warning): záznam byl potvrzen s výsledkem `unrecognised`,
  `unknown_subject` nebo `not_monitored`.

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
- Subjekt prověřený před zavedením referencí dostane novou referenci. Výjimka zaznamenaná pod jeho
  původním `subjectId` se na monitorování nevztahuje.
- Případ schválený při vypnutém prověřování není monitorován.

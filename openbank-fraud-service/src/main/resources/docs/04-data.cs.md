# Data

Tato stránka zatím popisuje jen životní cyklus outboxu; zbytek datového modelu ještě není sepsán.

## Retence outboxu (řádky SENT)

`fraud_outbox` je doručovací buffer, ne záznam. Řádky, které už dorazily do brokeru (`status = 'SENT'`), se mažou, jakmile je jejich `sent_at` starší než `openbank.outbox.retention.sent-days` (výchozí **7**), sdíleným jobem `OutboxSentRetentionJob` z libs-runtime (ADR-0329, ADR-0327 D8). Repozitář je jaderný `AbstractPanacheOutboxRepository` (ADR-0327 fáze 2) a job volá jeho `purgeSent`.

## Odesílání z outboxu (jaderný repozitář)

`FraudOutboxRepositoryImpl` dědí z `AbstractPanacheOutboxRepository` z libs-runtime. Claim bere u každé strany (`aggregate_id`) jen nejstarší rozpracovaný řádek, takže události `fraud.hold_changed` jedné strany zůstávají v pořadí i při více replikách. Selhaný řádek tu stranu podrží až do svého opakování. Opakování mají exponenciální odstup přes `next_attempt_at` a po sdíleném limitu pokusů končí jako DEAD. `V11__outbox_v2.sql` přidává sloupec a dva částečné indexy, `ix_fraud_outbox_claim` a `ix_fraud_outbox_inflight_aggregate`.

- Běží každou noc (`openbank.outbox.retention.cron`, výchozí `0 17 3 * * ?`) na každé replice; každé mazání je omezené (`batch-size` 5 000, nejvýš `max-batches` 200 za běh), takže dlouho nečištěná tabulka se vyprázdní během několika nocí.
- Řádků PENDING, FAILED, DISPATCHING a DEAD se nikdy nedotkne — DEAD řádky jsou DLQ na straně producenta.
- Replay události starší než okno jde z Kafka topicu nebo z audit-service, ne z této tabulky.
- Signály: `openbank_outbox_purged_total{service,status="SENT"}`, `openbank_outbox_purge_failed_total` a workflow liveness `outbox-sent-retention`.
- Vypnutí: `openbank.outbox.retention.enabled=false` (při startu zaloguje WARN). Nesnižujte `sent-days` pod potřebu kteréhokoli čtenáře SENT řádků.

## Přepsání consumer group pro Kafka

Consumer group id, offset reset a dead-letter topic pro kanály fraud-service záměrně nejsou YAML klíče v `application.yaml`; pocházejí ze souboru `fraud-service-msg-override.yaml` načítaného přes `QUARKUS_CONFIG_LOCATIONS`. Referenčním vzorem je kanál `party-events-in` ve službě card-issuance-service, na který komentář v `application.yaml` nyní odkazuje. Jde pouze o změnu komentáře a dokumentace; žádná hodnota konfigurace ani chování za běhu se nemění.

## Sdílený typ verdiktu (ADR-0353)

`FraudVerdict` je deklarován jednou v `openbank-libs-fincrime` a sdílen s platebními kanály. Hodnota `UNKNOWN` je to, co čte konzument, když odpověď nelze přečíst; tato služba ji nikdy nevytváří. `RuleHit` ji odmítne a `POST /api/v1/fraud/score` raději vrátí 500, než aby ji vydal. Verdikty ukládané do `fraud_scores.verdict` a vracené na drátě zůstávají `ALLOW`, `CHALLENGE`, `REVIEW` a `DECLINE`.

## Testovací profil: odchozí kanál outboxu vypnut

V profilu `%test` je odchozí kanál `fraud-outbox-out` vypnutý vedle již vypnutého konzumenta `transaction-signal` a `dispatch-enabled=false`, takže integrační testy startují bez brokeru. Produkční a vývojová konfigurace se nemění.

## Historie příjemců: klíčovaný pseudonym protistrany (ADR-0377 D3)

`POST /api/v1/fraud/score` přijímá volitelný objekt `counterparty` (`scheme` je `IBAN` nebo `DOMESTIC_ACCOUNT`, k tomu `value`; verze API kontraktu 1.4.0). Služba surovou hodnotu nikdy neukládá: zahashuje ji pomocí HMAC-SHA-256 s klíčem `openbank.fraud.counterparty-hmac.key`, do `payee_history.payee_identifier` zapíše `cp2:<keyId>:<hex>` (id klíče z `openbank.fraud.counterparty-hmac.key-id`) a surový identifikátor zahodí.

- **Klasifikace.** Hodnota `cp2:` je klíčovaný pseudonym. Kdo má klíč, dokáže ji znovu přiřadit ke konkrétní osobě, takže jde podle GDPR stále o osobní údaj a tak se s ní zachází.
- **Schéma.** `V13__payee_history_counterparty_ref_width.sql` rozšiřuje `payee_identifier` z `VARCHAR(64)` na `VARCHAR(128)`, aby se pseudonym vešel. Změna se týká jen metadat; vyhledávání dál obsluhuje stávající primární klíč `(account_id, payee_identifier)`. Starších řádků s UUID se změna nedotkne.
- **Kdy se příjemce považuje za známého.** Příjemce se zaznamená jen při verdiktu `ALLOW`; platba s výzvou, k přezkoumání nebo zamítnutá z příjemce známého neudělá.
- **Chybějící klíč.** Bez klíče není signál příjemce k dispozici: žádná náhradní cesta bez klíče neexistuje, služba při startu jednou zaloguje varování a každý dotčený požadavek započítá do `openbank.fraud.counterparty.key.missing`.
- **Retence.** Touto funkcí se nemění: řádky s pseudonymem mají stejnou retenci jako zbytek `payee_history`.

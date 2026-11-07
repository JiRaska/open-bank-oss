# Data

Tato stránka zatím popisuje jen životní cyklus outboxu; zbytek datového modelu ještě není sepsán.

## Retence outboxu (řádky SENT)

`fraud_outbox` je doručovací buffer, ne záznam. Řádky, které už dorazily do brokeru (`status = 'SENT'`), se mažou, jakmile je jejich `sent_at` starší než `openbank.outbox.retention.sent-days` (výchozí **7**), sdíleným jobem `OutboxSentRetentionJob` z libs-runtime (ADR-0329, ADR-0327 D8). Repozitář se zapojuje delegací `SentOutboxRetention` na `PanacheOutboxRetention`.

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

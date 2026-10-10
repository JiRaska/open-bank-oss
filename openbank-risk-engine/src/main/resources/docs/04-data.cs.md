# Data

Tato stránka zatím popisuje jen outbox limitních událostí a jeho deduplikační tabulku; zbytek datového modelu ještě není sepsán.

## Outbox limitních událostí a deduplikace (ADR-0313 D9, ADR-0329)

- **`risk_limit_event_dedup`** (`dedup_key` PK, `event_id`, `created_at`) je trvalý záznam, že limitní událost byla vyslána, právě jednou pro (běh, id limitu, id sady limitů, verze sady). **Nikdy se nemaže**: nejvýš desítky řádků denně, žádná osobní data, a trvalé uchování dělá „právě jednou“ bezpodmínečným.
- **`risk_outbox`** je doručovací buffer. `PgRiskOutbox.recordNonOk` nejdřív zabere klíč v deduplikační tabulce a řádek outboxu vloží jen z nového záboru, v jediné transakci vyhodnocení. Přehraný EOD běh nebo dva pody tikající současně tak nezapíšou nic nového — ani poté, co byl původní řádek outboxu smazán.
- **Retence SENT:** doručené řádky starší než `openbank.outbox.retention.sent-days` (výchozí 7) maže každou noc `OutboxSentRetentionJob` z libs-runtime. `PgRiskOutbox` před každou dávkou v téže transakci doplní způsobilé klíče do trvalé deduplikační tabulky a smaže jen řádky s trvalým záznamem. Platí to i pro starší pod, který zapsal až po jednorázovém backfillu V10. Řádků PENDING, FAILED, DISPATCHING a DEAD se nikdy nedotkne. Signály: `openbank_outbox_purged_total{service="risk",status="SENT"}`, `openbank_outbox_purge_failed_total`, workflow liveness `outbox-sent-retention`.
- **Rollback V10:** starší binárka deduplikuje jen přes `risk_outbox.dedup_key` a nerespektuje záznamy pro již smazané řádky. Před rollbackem vypněte retenci; zabrání to další ztrátě ochrany, ale neobnoví již smazané řádky. Se starší binárkou nepřehrávejte běhy, jejichž původní outbox řádky byly smazány. Deduplikační tabulku zachovejte, dokud ji některá binárka čte.

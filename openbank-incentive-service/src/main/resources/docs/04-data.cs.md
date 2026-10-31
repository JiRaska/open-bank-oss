# Data

Tato stránka zatím popisuje jen životní cyklus outboxu; zbytek datového modelu ještě není sepsán.

## Retence outboxu (řádky SENT)

`incentive_outbox` je doručovací buffer, ne záznam obchodního přechodu. `incentive_audit_event` zaznamenává typ, **aktéra** a čas každého přechodu ve stejné transakci jako řádek outboxu; `incentive_offer` / `promo_reservation` uchovávají aktuální obchodní údaje. Nabídka, atribuce, klient, produkt a stav rezervace zůstávají dohledatelné i po smazání doručeného řádku. Generované `eventId` outboxu z těchto tabulek obnovit nelze; `correlationId` se rovná uchovanému ID agregátu. Poznámka migrace V2, že řádky „zůstávají platným auditním důkazem“, se týká jejího rollbacku, ne jejich retence (#11902).

Doručené řádky (`status = 'SENT'`) se proto mažou, jakmile je jejich **`published_at`** starší než `openbank.outbox.retention.sent-days` (výchozí **7**), sdíleným jobem `OutboxSentRetentionJob` z libs-runtime (ADR-0329). Tabulka je starší než jednotný tvar outboxu: doručení zapisuje do `published_at` (ne `sent_at`) a řadí podle `occurred_at`, což `OutboxTableShape(sentAtColumn = "published_at", orderColumn = "occurred_at")` vyjádří bez migrace.

- Běží každou noc (`openbank.outbox.retention.cron`, výchozí `0 17 3 * * ?`) v omezených dávkách; řádků PENDING, FAILED, DISPATCHING a DEAD se nikdy nedotkne.
- Po mazání má `incentive_outbox` méně řádků než `incentive_audit_event` — očekávané; vztah 1:1 platí jen v okamžiku zápisu.
- Signály: `openbank_outbox_purged_total{service="incentive",status="SENT"}`, `openbank_outbox_purge_failed_total`, workflow liveness `outbox-sent-retention`.

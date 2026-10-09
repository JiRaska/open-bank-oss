

## Retence SENT řádků (ADR-0329)

libs vlastní mazání, ne tabulky. `SentOutboxRetention` (libs-domain) je port pro zapojení; implementuje ho každý `OutboxRepositoryV2` a v1 repozitář se zapojí přes `SentOutboxRetention by PanacheOutboxRetention(OutboxTableShape("<tabulka>"))`. `OutboxSentRetentionJob` (libs-runtime) najde všechny takové beany a každou noc v omezených dávkách maže SENT řádky starší než `openbank.outbox.retention.sent-days` (výchozí 7), s workflow liveness `outbox-sent-retention`. Vynucená brána `outbox-sent-retention` shodí modul s dispatcherem, který se nezapojí ani nemá zdůvodněnou výjimku.

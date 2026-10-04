# Provoz

## Relaye commitmentů

`ContextAuditCommitmentRelay` a `ContextDisclosureCommitmentRelay` publikují auditní a disclosure
commitmenty z transakčních outboxů do Kafky. Každý se spouští každých 5 sekund a po startu má
zpoždění 10 sekund. Bez zpoždění první tik vytvoří relay dřív, než SmallRye Reactive Messaging
připojí odchozí kanál, a plánovač při každém startu podu zaloguje jednou za relay
`SRMSG00019: Unable to connect an emitter with the channel ...`.

Řádek, který se nepodaří odeslat, se označí jako `FAILED`, započítá se do
`openbank_context_audit_outbox_publish_failures_total` (nebo disclosure obdoby) a po 30 sekundách se
zkusí znovu se stejným ID. Řádek, který zůstane 2 minuty ve stavu `DISPATCHING`, se převezme znovu.
`openbank_context_audit_outbox_pending` ukazuje řádky, které ještě nejsou `SENT`.

## Timeout příkazů v databázi

Aplikační role má v kontextové databázi výchozí `statement_timeout` 750 ms, nastavený Flyway
migrací `V12__application_role_statement_timeout.sql`. Request cesty ho v rámci transakce upravují
přes `set_config('statement_timeout', ...)` podle `openbank.context.query-timeout-ms`.

Timeout záměrně **není** parametr celého PostgreSQL clusteru. Ten by platil i pro superuživatele
`postgres`, kterého CloudNativePG používá pro zálohy. Na primáru `pg_backup_stop()` čeká na archivaci
WAL, takže ho timeout přerušil (`canceling statement due to statement timeout`, SQLSTATE 57014)
a každá base záloha z primáru selhala.

Rollback: `ALTER ROLE <aplikační role> IN DATABASE <db> RESET statement_timeout;`

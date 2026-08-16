# openbank-tpp-registry-service — service notes

The PSD2 third-party-provider directory: registration, status transitions, and the eIDAS licence
check `psd2-service` calls per request (`GET /api/v1/tpp-registry/check`).

## Two package roots, on purpose — and the trap that came with them

The tree has two disjoint roots and no import used to cross between them:

- `com.openbank.tppregistry.*` — the registry itself (domain, use case, REST, persistence)
- `com.openbank.tpp.*` — the transactional-outbox apparatus (dispatcher, backlog gauge, Kafka
  publisher, entity, repository)

That split is why **the outbox shipped complete and nothing ever wrote to it** (issue #4007). The
table, the `@Scheduled` dispatcher, the atomic `FOR UPDATE SKIP LOCKED` claim, the backlog gauge,
`openbank.outbox.dispatch-enabled: true`, the `KafkaTopic` resource and the write ACL all existed;
`persistInTransaction` had exactly one occurrence in `src/main` — its own declaration, no caller.
Unlike `party` or `balance` there was no second direct emitter either, so nothing had ever been
produced to `openbank.tpp.registry.event` at all, and no consumer could notice.

**Decision: wired, not deleted.** Every other end of the arrow already existed and only the write
did not, and the service's own docs listed the wiring as the next follow-up. `TppEvents` builds
`TPP_REGISTERED` and `TPP_BLACKLISTED`; `TppRepository.save`/`update` take the event as a
**required parameter**, so there is deliberately no eventless overload for a future call site to
bypass, and `TppRepositoryImpl` persists the aggregate row and the outbox row in one
`Panache.withTransaction`.

- **A mocked repository cannot see any of this.** The unit tests were green for the whole life of
  the defect. Only `TppOutboxWriteIT` — REST in, plain JDBC out, dispatcher switched off — can
  distinguish "wrote the row" from "did not". Falsify it before trusting it: delete the
  `persistInTransaction` call and both tests must go red with `Expected size: 1 but was: 0`.
- The IT sets `authz.enforce=false`: the blacklist endpoint carries `@Authorize`, and an OPA
  sidecar the interceptor cannot reach fails **closed** with 503 (not 403), which would otherwise
  look like an authorization bug.

## `tpp_entries_seq` vs the V1 seed rows

`V1__init.sql` declares `id BIGSERIAL` and then seeds three TPPs, so ids 1..3 come from the
implicit `tpp_entries_id_seq`. Panache allocates from `tpp_entries_seq`, which `V4` created with
the default `START 1`. Nothing reconciled them, so the **first** registration through
`POST /api/v1/tpp-registry` was a deterministic
`duplicate key value violates unique constraint "tpp_entries_pkey"` → 500, on every database that
ran the seed. `V7` fixes it with a `setval` past `MAX(id)`.

Why it went unnoticed for the life of the service, and the general lesson: measured on the sandbox
2026-08-16, `tpp_entries_seq` read `last_value = 1, is_called = f` — **the sequence had never been
called**, so no registration had ever been attempted in a deployed environment. An unexercised
write path is not an absent defect, and an empty table is evidence of nothing on its own. Note the
`n_live_tup` trap in the same measurement: `pg_stat_user_tables` reported 0 rows for *every* table
including `flyway_schema_history`, while `count(*)` reported 3 and 5. Use `count(*)`.

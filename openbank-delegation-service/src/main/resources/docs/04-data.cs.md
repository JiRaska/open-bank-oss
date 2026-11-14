# Data

Tato stránka zatím popisuje jen životní cyklus outboxu; zbytek datového modelu ještě není sepsán.

## Retence outboxu (řádky SENT)

`delegation_outbox` je doručovací buffer, ne záznam. Řádky, které už dorazily do brokeru (`status = 'SENT'`), se mažou, jakmile je jejich `sent_at` starší než `openbank.outbox.retention.sent-days` (výchozí **7**), sdíleným jobem `OutboxSentRetentionJob` z libs-runtime (ADR-0329, ADR-0327 D8). Repozitář se zapojuje delegací `SentOutboxRetention` na `PanacheOutboxRetention`.

- Běží každou noc (`openbank.outbox.retention.cron`, výchozí `0 17 3 * * ?`) na každé replice; každé mazání je omezené (`batch-size` 5 000, nejvýš `max-batches` 200 za běh), takže dlouho nečištěná tabulka se vyprázdní během několika nocí.
- Řádků PENDING, FAILED, DISPATCHING a DEAD se nikdy nedotkne — DEAD řádky jsou DLQ na straně producenta.
- Replay události starší než okno jde z Kafka topicu nebo z audit-service, ne z této tabulky.
- Signály: `openbank_outbox_purged_total{service,status="SENT"}`, `openbank_outbox_purge_failed_total` a workflow liveness `outbox-sent-retention`.
- Vypnutí: `openbank.outbox.retention.enabled=false` (při startu zaloguje WARN). Nesnižujte `sent-days` pod potřebu kteréhokoli čtenáře SENT řádků.

## Čtení vlastnictví zdroje podle typu (ADR-0375 D5)

Grant je sám o sobě autorita, jakmile si ho produktová služba promítne, takže před vystavením
nabídky se tato služba zeptá služby, která zdroj **vlastní**, jestli ho poskytovatel grantu opravdu
vlastní. Odpověď je fail-closed v obou chybových směrech: `NOT_OWNED` odmítá a `UNVERIFIABLE` taky —
dohledání, které se nepovedlo provést, není povolení.

| typ zdroje | kdo odpovídá | stav |
|---|---|---|
| `ACCOUNT`, `SAVINGS_GOAL` | account-service (spořicí cíl je klíčovaný vlastnícím účtem, ADR-0153) | vyřešeno |
| `CARD` | card-issuance-service | vyřešeno |
| `DOCUMENT` | document-service `GET /api/v1/documents/{id}/owner`, akce `document.verifyOwnership` | vyřešeno (ADR-0375 D5) |
| `PAYMENT`, `STATEMENT` | nikdo — viz níže | vždy `UNVERIFIABLE`, takže každá taková nabídka je odmítnuta |

Čtení dokumentu používá POJMENOVANÉHO OIDC klienta `m2m`, takže se autentizuje jako
`service-account-openbank-delegation` (jen ROLE_API), ne jako sdílená identita `openbank-services`,
kterou document-service pro tuto akci odmítá. `M2mOidcClientIdentityWiringTest` výběr klienta pojišťuje.

`PAYMENT` a `STATEMENT` jsou odmítané na základě měření, ne proto, že by práce nebyla dokončená.
statement-service je adresovaná `accountId` a nemá objekt výpisu s vlastním id, takže id zdroje typu
`STATEMENT` neodkazuje na nic, co by vlastnící služba uměla dohledat. Id platby se neresolvuje v
žádné jedné službě: platby vlastní šest railů a nic nemapuje id na rail. Ani jeden typ nemá koho se
zeptat, takže oba zůstávají odmítané, dokud ty páry neodejdou z matice capabilities.

### Opravy katalogu presetů jsou dopředné migrace (ADR-0375 D7)

Katalog rolí se seeduje migrací a preset může být špatně způsobem, kterého si nikdo nevšimne,
dokud je špatně i vynucování kolem něj. „Účetní" a „Pokladník" udělují `ACCOUNT_READ_BALANCES` bez
`ACCOUNT_VIEW_DETAILS`; to bylo neviditelné tak dlouho, dokud se čtení detailu účtu na edge ptalo
na capability pro zůstatky. V33 oběma presetům chybějící capability doplňuje.

Sahá to jen na KATALOG. Už vystavené granty jsou neměnné snímky — grant nese to, s čím poskytovatel
v té chvíli souhlasil, a jeho dodatečné rozšíření by znamenalo, že tahle služba edituje někomu
mandát. Stávající držitel těch dvou rolí si nechává zůstatky i transakce a o detail účtu přijde,
dokud mu poskytovatel nesdílí znovu z opraveného presetu.

## Rezervace limitu vypořádané výsledkem platby (dodatek ADR-0249 D3, #16000)

Rezervace limitu tuzemské platby se vypořádá podle toho, jak platba skutečně skončila, ne podle
toho, že ji platební kanál přijal. `DomesticPaymentOutcomeConsumer` čte
`openbank.domestic.payment.events` (skupina `delegation-service-domestic-payment-outcomes`) a
rozhoduje podle hlavičky `ce-type`:

| Událost | Rezervace |
|---|---|
| `domestic.payment.status-changed`, `newStatus` SETTLED | RESERVED → CONFIRMED |
| … REJECTED, CANCELLED; `domestic.delegated-spend.finalized-absent` | RESERVED → RELEASED |
| … RETURNED | RESERVED nebo CONFIRMED → RELEASED (jediná cesta ze stavu CONFIRMED) |
| bez `reservationId`, nekoncový stav, `created`, `held` | beze změny (`ignored`) |
| jiný nebo chybějící `ce-type`, nečitelné tělo | beze změny (`unrecognised`, s alertem) |

Opakovaně doručený výsledek nic nezmění; protichůdný se potvrdí a započítá jako `conflict`. Každý
záznam se počítá v `openbank_delegation_payment_outcome_events_total{outcome}`. Do
`openbank.dlq.delegation.domestic-payment-events-in` jde jen záznam, jehož zápis selhal.

Konzument je vypnutý, dokud není `OPENBANK_DELEGATION_PAYMENT_OUTCOME_CONSUMER_ENABLED=true`;
vypnutý kanál vůbec neexistuje. Pořadí zapnutí: tento konzument, potom vazba rezervace v platebním
kanálu, potom edge přestane potvrzovat na odpověď 2xx.



`OutboxTableShape.sentAtColumn` (výchozí `sent_at`) pojmenovává sloupec s časem doručení u tabulky starší než jednotný tvar; `incentive_outbox` v incentive používá `published_at` (#11902).

## Vlastnictví nároku na outbox řádek

Služba může zapnout ověřování vlastnictví nároku pomocí `AbstractClaimFencedOutboxRepository`
a `PanacheClaimFencedOutboxEntityV2`. Vedle sloupců pro opakování ve V2 přidá volitelný
sloupec `claim_token UUID`. Dotaz při nárokování přiřadí UUID a potvrzení změní řádek jen
při shodě ID události i tokenu. Dispatcher do metrik započítá pouze potvrzení uložená
v databázi. Příjemci dál odstraňují duplicity podle ID události, protože zpožděný odesílatel
může publikovat i po převzetí řádku jiným dispatcherem.

Před rozšířením tabulky nasaďte kompatibilní verzi a před zapnutím nového repository
zastavte dispatchery, které potvrzují bez tokenu.

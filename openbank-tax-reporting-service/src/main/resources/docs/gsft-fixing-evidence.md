# Offline GSFT fixing evidence

GSFT needs the EUR fixing for its compensation cap and a CZK-per-unit fixing for every other
retained foreign deposit currency. The FX service persists native ČNB rates and their
`fx.fixing.published.v1` outbox record in the same transaction. The publication identifies its
declared fixing date, validity interval and the stored rate IDs. A single publication may carry
only the currencies that were newly stored in that ingestion run. The FX history migration also
seeds approximate values tagged `CNB` for sandbox and CI. Those rows have no matching fixing
publication. The verifier requires the publication so it can preserve dated source lineage.

The offline verifier accepts local JSON payload exports from that existing outbox event. Run it
with the requested GSFT reference day and the currencies in the retained deposit roster.

```sh
python openbank-tax-reporting-service/tools/gsft_cnb_fixing_evidence.py \
  --reference-day 2026-05-29 \
  --deposit-currency CZK \
  --publication synthetic-fx-publication.json
```

Repeat `--publication` for separate exports and `--deposit-currency` for additional deposit
currencies. An exact result records the declared fixing date, source payload digests, original
rate IDs and the native rates at the precision of the FX source. The tool reports
`filingReady: false`. Source lineage depends on the provenance of the exports supplied by the
operator or test fixture.

When there is a missing exact-day currency, the tool exits with status 1 and returns the
`FX_RATES` gap, the missing currencies and any observed fixing dates. A Friday fixing's validity
interval may extend past Friday. Its declared date remains Friday, so a Saturday or holiday
without an exact publication stays unresolved until the reference-day selection rule is approved.
Conflicting or malformed evidence exits with status 2.

The tool uses the existing FX event contract and runs offline. The GSFT service snapshot's
`FX_RATES` completeness gate still applies. Runtime source admission and regulatory coverage
remain on #15356.

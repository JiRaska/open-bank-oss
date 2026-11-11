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

## Reference-day archive response

Section 41e(1)–(2) uses the ČNB rate for the GSFT reference day for foreign-currency deposits
and the EUR cap. ČNB extends each daily fixing to any following weekend and public holiday
before the next working day. Keep the actual reference day separate from the fixing date in
that day's archive response. [Banking Act, effective 11 January 2026](https://e-sbirka.gov.cz/sb/1992/21/2026-01-11#par_41e-odst_1)
[GSFT compensation guidance](https://www.garancnisystem.cz/vyse-nahrady)
[ČNB fixing methodology](https://www.cnb.cz/docs/ARADY/MET_LIST/kurzy_cs.pdf)

Pass `--cnb-archive-response archive.json` along with `--reference-day` and the exported FX
publications. The local JSON object carries these fields.

- `requestUrl` is the exact Czech ČNB daily TXT endpoint with `?date=DD.MM.YYYY` for the reference day.
- `httpStatus` is `200`.
- `capturedAt` is the capture timestamp with its UTC offset. If the response declares an earlier
  fixing date, the timestamp must fall after the reference day in Europe/Prague. This avoids
  accepting a previous fixing returned before the current day's publication.
- `responseText` is the original UTF-8 response text including its header and line endings.
- `responseSha256` is the lowercase SHA-256 of those original UTF-8 bytes.

For example, the response to a 30 May 2026 request declares the 29 May 2026 fixing and sequence
102. The header carries the fixing date and sequence. The request URL in the operator's export
records which calendar day was requested. [ČNB TXT format](https://www.cnb.cz/cs/casto-kladene-dotazy/Kurzy-devizoveho-trhu-na-www-strankach-CNB/)
[ČNB archive response for 30 May 2026](https://www.cnb.cz/cs/financni-trhy/devizovy-trh/kurzy-devizoveho-trhu/kurzy-devizoveho-trhu/denni_kurz.txt?date=30.05.2026)

The verifier matches the archive's fixing date and sequence to the FX publications. It divides
quoted CZK by the source quantity at FX's eight-decimal HALF_UP precision and compares each
required currency with its stored CZK-per-unit rate. `ARCHIVE_MATCHED_FIXING_EVIDENCE` retains
both dates, the archive digest, quantities, original FX rate IDs and source digests. The result
marks archive provenance `OPERATOR_SUPPLIED_UNVERIFIED` and keeps `filingReady: false`.

The default check still requires a publication with the exact declared day. Missing evidence
returns `FX_RATES` and exit status 1 and withholds all usable rates. Conflicting or malformed
input returns exit status 2. The tool requires the explicit archive response for an earlier
fixing. The FX event's internally calculated `validTo` supplies no archive date association.
Runtime source admission, statutory completeness and filing remain on #15356.

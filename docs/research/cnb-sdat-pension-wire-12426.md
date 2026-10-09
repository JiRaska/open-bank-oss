# ČNB SDAT pension return wire research (#12426)

Verified on 2026-10-10 against ČNB's public sources. This note inventories source material; it does not certify a renderable PSP/PEF return.

## Current public sources

- [ČNB pension methodology landing page](https://www.cnb.cz/cs/statistika/dohledova-statistika/metodicke-informace/ps-a-fps/) directs reporters to SDAT for detailed methodology.
- [ČNB plan of methodologies](https://www.cnb.cz/cs/statistika/sdat/plan-metodik/) lists `PEF20260101` in production and test, with `PEF20270101` as a future version. The [public `PEF20260101` SDAT report index](https://sdat.cnb.cz/sdat_ext/pages/sdat/portal/EXT/public/metapopis/vykazy/F2001-prehled-vykazu.zul?contextId=22063) exposes the currently selected context (`contextId=22063`) and report versions, including `DOPES31` / `PSP31-04` / `012.000`. Its [report detail](https://sdat.cnb.cz/sdat_ext/pages/sdat/portal/EXT/public/metapopis/vykazy/F2011-detail-verze-vykazu.zul?id=24487&contextId=22063) exposes blocks, data areas, checks, reporting scope, mapping and an Excel export. The [DOPE31_11 data-area detail](https://sdat.cnb.cz/sdat_ext/pages/sdat/portal/EXT/public/metapopis/vykazy/F2013-detail-verze-datove-oblasti.zul?id=212829&contextId=22063&typ=DATOVA_OBLAST&blokVerzeId=208471) specifies cumulative figures through the quarter in thousands of CZK and distinct rows for participant, employer and state contributions, transfers and unidentified payments, among others.
- The [ČNB SDAT technical documentation index](https://www.cnb.cz/cs/statistika/sdat/dokumentace/) publishes [Reporting, v1.4, 2025-01-15](https://www.cnb.cz/export/sites/cnb/cs/statistika/files/sdat/technicka-specifikace/SDAT_TS_3_Vykazovani.docx), [Technical information, v1.3, 2026-09-03](https://www.cnb.cz/export/sites/cnb/cs/statistika/files/sdat/technicka-specifikace/SDAT_TS_5_TechnickeInformace.docx), [web service catalog, v1.4, 2024-06-21](https://www.cnb.cz/export/sites/cnb/cs/statistika/files/sdat/technicka-specifikace/SDAT_TS_6_KatalogWebovychSluzeb.docx), [control language, v1.9, 2026-09-03](https://www.cnb.cz/export/sites/cnb/cs/statistika/files/sdat/technicka-specifikace/SDAT_TS_8_PopisKontrol.docx), and a [WSDL/XSD archive](https://www.cnb.cz/export/sites/cnb/cs/statistika/files/sdat/ws.zip). The Reporting specification distinguishes report content from the signed input message and its SOAP transport; it describes status retrieval and correction modes. Public data-area metadata alone does not prove the transport envelope or an accepted submission.

## Gap against the implementation

`openbank-tax-reporting-service/src/main/resources/statutory-returns/cz/pension-cnb.v1.json` declares platform datapoint identifiers and `wireFormatVerified: false`. For example its `PSP31-04` entry collapses contributions to own, employer and state amounts, while the public `DOPE31_11` area has additional payment/transfer categories. These identifiers cannot be treated as ČNB cell codes, nor is the three-term sum a complete official return. The existing `ReturnWireRendererPort` therefore must remain unbound.

## Evidence needed before a renderer

1. Pin the applicable SDAT methodology/report version per return and reporting period. Export and review every block, data area, dimension, cell identifier, type, unit, mandatory condition and JVK/MVK control for one **entire** return; reconcile it against authoritative source data. A partial mapping must not turn the capability on.
2. Select the ČNB-permitted payload format for that report version, implement the exact content plus signed message/transport from the published specifications and schemas, and preserve submission/status acknowledgement evidence. Do not infer the envelope from an Excel export.
3. Obtain an accepted test-system submission and rejection fixtures for initial and §7 corrected submissions, including correction reason and allowed full/delta mode. Keep `wireFormatVerified: false` until this complete proof exists.

The access premise changes: public SDAT metadata and technical documents are available. Completeness of the return mapping, transport and acceptance proof remains open.

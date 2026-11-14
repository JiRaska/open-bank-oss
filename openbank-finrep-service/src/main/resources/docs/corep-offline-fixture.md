# Offline COREP OF synthetic fixture

The C 01.00 fixture uses the seven synthetic equity GL balances from the corrected own-funds
preview test in #15666. It covers eight of the 110 monetary facts in the EBA 4.2 C 01.00 table.
The source is a capital slice. Its balances do not represent a complete trial balance.

The tool uses the checked-in source and the EBA dummy consolidated entity. The standalone
reconciliation reads the archive and recomputes all eight amounts from the GL balances. Its
evidence records the source and archive digests, the two omitted source gaps and
`filingReady: false`. The filing indicator identifies the table carried by this synthetic package.
GL 6040 contributes to the preview CET1 subtotal as an aggregate deduction. Goodwill (r0300) and
profit or loss attributable to owners of the parent (r0160) require separate authoritative sources,
so the archive omits both facts.

Run these commands from the repository root using the pinned EBA 4.2 archives and Arelle 2.47.4
described in the [offline structure check](README.md#offline-xbrl-csv-structure-check).

```sh
python openbank-finrep-service/tools/check_corep_datapoints.py \
  --taxonomy-package taxo_package_4.2_hotfix.zip
python openbank-finrep-service/tools/corep_c01_synthetic_fixture.py \
  --output synthetic-corep-c01.zip
python openbank-finrep-service/tools/validate_xbrl_csv_structure.py \
  --module COREP_OF --report synthetic-corep-c01.zip \
  --taxonomy-package taxo_package_4.2_hotfix.zip \
  --full-taxonomy Full_taxonomy_and_technical_documentation.zip
python openbank-finrep-service/tools/corep_c01_synthetic_fixture.py \
  --report synthetic-corep-c01.zip
```

The coordinate check covers ten C 01.00 cells and 24 C 02.00.a TREA cells in column c0010.
C 02.00.b uses different facts under the same filing indicator. The C 02.00 result is coordinate
evidence only. The own-funds-requirements values and risk-source coverage are unchanged.

Arelle checks the report package, xBRL-CSV types and taxonomy resolution offline. The structural
check skips formula and severity linkbases. Full module coverage, filing-rule validation,
authoritative production capital sources and regulator acceptance remain on #5914.

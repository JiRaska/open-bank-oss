# Czech pension-company reporting inventory (issue #12422)

Verified against the ČNB sources linked below on 2026-10-10. This inventory applies to an
**own-provider Czech pension company** and its managed funds. A bank that only distributes a
partner's DPS does not automatically become the reporting pension company. It is an obligation
inventory, not a claim that the platform can assemble or submit any of these returns today.

| Return / information | Reporting unit | Recipient | Reference date / cadence | Deadline | Format / channel | Basis and status |
|---|---|---|---|---|---|---|
| PSP (ČNB) 10-12 balance sheet; 20-12 profit and loss | Company **and each managed fund** | ČNB | Month end | Within 20 days after month end | Electronic data message through ČNB collection system; current ČNB pension framework is SDAT | [425/2012 §3(1)(a), (2), §6][psp-law]; legal scope verified; SDAT cell schema **not verified** |
| PSP (ČNB) 30-12 pension unit | Company report covering managed participant funds | ČNB | Month end | Within 20 days after month end | As above | [425/2012 §3(1)(a), annex][psp-law]; verified; no transformed-fund unit rows inferred |
| PSP (ČNB) 50-04 organisation; 31-04 receipts and payouts; 32-04 capital and capital requirements; 34-12 portfolio composition | Company | ČNB | Quarter end | Within 30 days after quarter end | As above | [425/2012 §3(1)(b)][psp-law]; verified |
| PSP (ČNB) 40-01 risk supplementary information | Company | ČNB | Year end | Within 30 days after year end | As above | [425/2012 §3(1)(c)][psp-law]; verified |
| PSP (ČNB) 34-12 portfolio composition | **Each managed fund** | ČNB | Month end | Within 20 days after month end | As above | [425/2012 §3(2)(c)][psp-law]; verified. This is a different cadence from the company row. |
| PEF (ČNB) 12-04 balance sheet | Company **and each fund separately** | ČNB | Quarter end | Within 30 days after quarter end | Electronic data message in current SDAT pension framework | [314/2013 §7(1)(a)][pef-law], [ČNB methodology page][cnb-method]; verified |
| PEF (ČNB) 13-04 fund loans; 14-04 pension claims | **Each fund separately** | ČNB | Quarter end | Within 30 days after quarter end | As above | [314/2013 §7(1)(b–c)][pef-law]; verified |
| PEF (ČNB) 15-01 participants | Company report | ČNB | Year end | By 30 January of following year | As above | [314/2013 §7(2)][pef-law]; verified |
| Annual reports for company and each managed fund | Company and each fund | ČNB | Annual | **Not established by the cited §5**; verify applicable accounting/reporting deadline | Electronically signed report to ČNB electronic filing address or data box, with prescribed fallback for oversized files | [425/2012 §5][psp-law]; recipient/channel verified, deadline pending |
| Post-submission corrections to PSP returns | Same unit as affected return | ČNB | Event-driven | Without undue delay; audited year-end correction within 20 days after audit | Corrected return plus content/reason of correction | [425/2012 §7][psp-law]; verified; the system must retain revisions and linked downstream corrections |
| Pension tax administration returns and certificates | **To be classified per tax obligation** | Tax administration / participant, as applicable | **Unverified** | **Unverified** | **Unverified** | ADR-0334 mentions pension tax certificates, but this research has not verified a specific filing obligation; do not generate a deadline or form from assumption. |

The [ČNB methodology plan][cnb-plan] lists pension framework `PEF20260101`, valid from
2026-01-01, in production and test. The ČNB says individual return metadata and reporting
obligations are maintained in SDAT; the public legal PDFs do **not** supply a verified, current
cell-level file specification. A jurisdiction pack must therefore pin both the legal-rule version
and the independently reviewed SDAT methodology version. No renderer or transmission adapter should
assert SDAT compatibility until its format and recipient acknowledgement are tested.

The legal PDFs and live SDAT obligation register may differ for a particular reporting entity.
Before activating a pack, compliance must verify its licence, managed-fund roster, any ČNB
entity-specific obligation or exemption, and the current methodology in SDAT. The 314/2013 §10
statistical-significance alternative must not be applied without a ČNB notification to that entity.

[psp-law]: https://www.cnb.cz/export/sites/cnb/cs/legislativa/.galleries/vyhlasky/vyhlaska_425_2012_uplne_zneni_k_20210101.pdf
[pef-law]: https://www.cnb.cz/export/sites/cnb/cs/legislativa/.galleries/vyhlasky/vyhlaska_314_2013_uplne_zneni_01_07_2025.pdf
[cnb-method]: https://www.cnb.cz/cs/statistika/metodicke-informace/penzijni_spolecnosti/
[cnb-plan]: https://www.cnb.cz/cs/statistika/sdat/plan-metodik/

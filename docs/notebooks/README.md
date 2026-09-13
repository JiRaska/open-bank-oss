# Notebooks

Analyst notebooks that read an **export**, never a live database. The bank's OLTP databases are
not an analytics surface (ADR-0022): a notebook pointed at `lending` would hold a session against
a money-path database, bypass every role gate the service applies, and read PII with no audit
trail. So the flow is:

1. A credit-risk analyst opens the console (`/lending/risk`) with their own role.
2. They export the decision set they are entitled to see (JSON or CSV).
3. The notebook reads that file from disk.

The export is the audit boundary. What is in the file is what the analyst was allowed to read at
the moment they read it, and the file carries the policy bundle that produced the decisions, so a
notebook cell can always answer "against which rules?".

| Notebook | Reads | Answers |
|---|---|---|
| [`credit-risk-decisioning.ipynb`](credit-risk-decisioning.ipynb) | the console's JSON export | Outcome and reason-code distribution, affordability against the policy's own thresholds, engine-vs-human overrides, IFRS 9 stage/ECL and vintage |

Dependencies are `pandas` and `matplotlib` only — deliberately not a project environment: the
notebook must open on an analyst's laptop without this repo being built.

**What a notebook must not become.** These are exploratory. A number that has to be defensible to
the regulator belongs in a service or a regulatory return, where it is versioned, tested and
gated — never in a notebook cell that only one person has run.

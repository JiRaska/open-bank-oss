# Segregated accounting close E2E

Build the actual fast jars, then run the local three-application scenario:

```sh
./gradlew :openbank-ledger-service:quarkusBuild :openbank-pension-fund-service:quarkusBuild
python3 openbank-pension-fund-service/e2e/accounting_close.py --report /tmp/pension-accounting-e2e.json
```

Requires JDK 25, Docker with the `postgres:18.6` image, Python 3.10+, OpenSSL and OPA.
The runner starts only loopback listeners and a uniquely named temporary PostgreSQL container.
It cleans up its own processes, container, generated keys and credentials on exit.
It accepts no production address or credential. Nothing provisions a cloud resource.

The fund service, fund ledger and company ledger are real packaged applications. Each has its
own database. Real OPA policies authorize real signed fixture JWTs; the accounting HTTP client
uses mutual TLS and verifies the generated CA. The only external-source doubles are a local
OIDC issuer and a pinned catalog revision. Custody receipt and unit recognition are explicit,
attributable fixture postings through the ledger API; fee publication infers no cash settlement.

The shared ledger package chooses its chart through the registered runtime Flyway callback.
The company chart has one source: the existing infra ConfigMap. The runner extracts that exact
SQL into a temporary file and passes `openbank.ledger.book.chart-script`; it does not duplicate
the chart. A deployment must point that property at its reviewed mounted chart, alongside an
explicit book kind/id and own close declarations. Runtime Flyway location overrides alone do
not discover files in a package augmented with different locations. Existing journal history
without a bound identity requires a reviewed cutover and is refused at startup.

The scenario withholds the company ledger until the fund leg has committed, proves the source
remains pending, starts the company and recovers without duplicate fee journals. It then proves
that source completion cannot replace each book's full-chart maker/checker reconciliation,
that stable source retransmission preserves its work watermark, and that both books can close
independently. Wrong-book requests fail and a single fixture day cannot qualify a full month.
After close, a newly calculated correction must revoke source completion in both books without
claiming another posted journal. Repeated refusals preserve their receipt and the original frozen
close evidence remains unchanged. Policies consume the actual typed OPA resource (`type`, `id`).

The JSON report contains assertion names only. This is executable test evidence, not a live
operating month, chart approval, deployment approval or permission to enable enforcement.

The path-scoped `Pension accounting E2E` GitHub workflow builds the same fast jars and runs this
scenario on relevant pull requests or manual dispatch. It uploads only the assertion JSON with
an explicit running/passed/failed status, never the temporary directory containing generated keys.
This standalone check does not change branch protection or the existing `all-green` dependency set.

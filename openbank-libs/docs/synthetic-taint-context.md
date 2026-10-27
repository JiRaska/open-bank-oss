# Synthetic taint filter and request-scoped holder

`openbank-libs-runtime` owns the synthetic dimension's inbound half (ADR-0252,
issue #4348). Two small classes carry it:

- `SyntheticTaintFilter` — a Jakarta `ContainerRequestFilter` that reads
  `x-openbank-synthetic: true` and, only when the caller principal is in
  `openbank.synthetic.trusted-principals`, records the taint on the security
  identity's string map (visible to every reader on the request thread).
- `SyntheticTaintContext` — a `@RequestScoped` CDI bean with a nullable
  Boolean holder. The filter writes it whenever the identity write succeeds,
  so a Kotlin `suspend` resource that crosses the CDI suspend/resume boundary
  can still read the taint. Readers that cannot inject the holder (non-CDI
  tests, plain filters) keep using the identity property; suspend resources
  read holder first and fall back to the property.

## Why both paths

Measured defect (#14058): a ledger seed posting from a trusted synthetic
principal arrived with `synthetic = false` because `LedgerResource.postJournal`
is suspend and the identity property set by the filter did not survive the
dispatch onto the worker thread. The holder travels with the CDI request
context, which does. Writing both keeps one flag with two independent read
paths, and any future drift in either fails closed to real — never to
synthetic.

## Rules for a new synthetic caller

1. Give the caller a dedicated Keycloak client whose service account holds
   only the roles the target endpoint's `@RolesAllowed` needs.
2. Admit exactly the required actions in that service's `*_rest_ext.rego`,
   gated on the exact service-account name — never on role or principal type.
3. Add the service account to the target service's
   `OPENBANK_SYNTHETIC_TRUSTED_PRINCIPALS` deployment value, and to the
   `check-synthetic-customer-identity.py` allowlist if the trust is direct
   (the ledger seed pattern) rather than relayed through customer-edge.

`SyntheticTaintFilterTest` covers: header absent, header on an untrusted
principal, header on a trusted principal (both write paths), and the
holder-first / property-fallback read order.

# Synthetic taint filter and request-scoped holder

`openbank-libs-runtime` owns the inbound synthetic classification (ADR-0252,
issue #4348). `SyntheticTaintRequestFilter` accepts `x-openbank-synthetic: true`
only from an authenticated service-account identity whose derived principal
name is in `openbank.synthetic.trusted-principals`. It writes the decision to
the JAX-RS `ContainerRequestContext` property and to `SyntheticTaintContext`,
a `@RequestScoped` CDI bean with a non-null Boolean initially `false`.
It does not write to `SecurityIdentity`. Declined and absent claims write
`false` to both carriers.

## Why both carriers

Issue #14058 reports trusted synthetic postings recorded as real on an older
deployed image. That observation does not independently isolate the request-
property loss or coroutine-dispatch mechanism. The CDI holder gives suspended
resource methods another carrier for the filter's existing trusted decision.
Ledger combines holder and request property with OR: either trusted value
being true marks a posting synthetic. A false value in one does not override
true in the other. The property is a fallback when the holder is false, not
when its CDI context is absent or inactive.

False-real classification can include synthetic activity in financial views;
unjustified true can exclude real activity. Fail-to-real is not universally
safe for an already trusted canary. Request isolation and propagation are
correctness obligations, not guarantees established by naming a CDI scope.

## Rules for a new synthetic caller

1. Give it a dedicated Keycloak service account with only the endpoint's
   necessary roles.
2. Admit only the required actions through verified named-machine policy
   grants, not a caller-supplied principal name or broad role alone.
3. Deliberately configure the target service's trusted-principals list and
   applicable identity guard allowlist. Provisioning, activation and actual
   seed postings require their own operational authorization.

## Evidence and limits

Filter unit tests construct holders manually and check trusted and declined
decisions. Ledger HTTP/JDBC tests check persisted flags and population views.
A test-only fixture removes the legacy property after the actual trust
decision; a bounded asynchronous barrier overlaps trusted and ordinary POSTs
and a fresh ordinary follow-up checks request isolation. Removing only the
CDI read fails the sequential and concurrent synthetic-row assertions.
This establishes the read's role under controlled property loss, not the
older image's loss mechanism, absent-holder fallback, live token signatures,
enforced OPA or deployed acceptance. Issue #14058 retains that delivery tail.

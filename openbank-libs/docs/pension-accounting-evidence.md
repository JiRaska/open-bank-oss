# Pension accounting evidence and shared controls

ADR-0365 separates fund and company books. The automatic producer covers NAV management
fees; it does not infer cash settlement, unit recognition or payouts. Each book still requires
independent source references, its complete enabled chart and a different verified checker.
The proposed chart requires professional accounting approval and a reviewed opening/cutover.

`governance/rules.yaml` classifies ledger `Pension*.kt` code under
`security_sensitive_paths.auth_crypto_payment`. Payload fingerprints, durable replay identities,
book bindings and maker/checker approval therefore require security review. Retained verified
staff subjects are pseudonymous personal audit data: use `gdpr-review-required` as well as the
money-path reviews, without copying participant personal data into evidence references.

The shared workflow liveness recorder measures accepted report delivery. An accepted `REFUSED`
report is a successful delivery, while accounting completion remains refused. A partial destination
failure records no workflow success. No-op delivery or a healthy scheduler can substitute for
source completion, own-book reconciliation or the immutable close proof.

Keep delivery disabled and deployment holds until reviewed book configuration and a complete
representative live calendar month are available. The packaged OIDC/OPA/mTLS E2E uses generated
fixture credentials and isolated databases; its assertion-only report explicitly is not production
evidence or permission to activate enforcement.

# Insurance reference-pack interoperability

The `org.openbank.insurance.term-life:2` pack is a descriptive product-catalog profile. It does
not quote, underwrite, bind a policy or process claims. Those decisions remain in the insurer's
own bounded contexts.

`InsuranceTermLifeInteroperabilityAdapter` exposes two deliberately bounded projections from the
schema-governed catalog attributes:

- an ACORD-shaped term-life product profile carrying coverage, premium, insured events, exclusions,
  limits, deductibles and underwriting questions;
- a TMF620-shaped `ProductSpecification` profile whose characteristics carry the same fields.

The conformance test proves that every mandatory reference-pack concern round-trips through both
profiles and exact decimal strings remain text. Each importer is closed: unsupported fields or duplicate
characteristics fail explicitly rather than being silently dropped. These maps are adapters, not an
assertion of full ACORD or TM Forum conformance; new exchange requirements must add an adapter profile
and fixture without changing the generic kernel.

## Supported exchange profile

| Catalog attribute | ACORD-shaped field | TMF620-shaped characteristic |
| --- | --- | --- |
| Coverage | `coverage` | `coverage` |
| Term | `termYears` | `termYears` |
| Premium model and amount | `premiumModel`, `premium` | `premiumModel`, `premium` |
| Insured perils | `insuredEvents` | `perils` |
| Exclusions, limits and deductibles | matching plural fields | matching plural characteristics |
| Underwriting questions | `underwritingQuestions` | `underwritingQuestions` |

The adapter maps the schema-governed `attributes` payload only. The catalog revision envelope remains
canonical for its identity, lifecycle, effective interval and price-component intervals; integrations
must retain that envelope rather than trying to infer it from an ACORD/TMF attribute profile.

The following are intentionally unsupported by this reference adapter: quote calculation, policy binding,
claims, a general ACORD/TMF parser, external references and unknown profile fields. They fail at import or
belong in a separate, version-pinned adapter — they are never silently ignored.

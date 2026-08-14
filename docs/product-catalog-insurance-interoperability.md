# Insurance reference-pack interoperability

The `org.openbank.insurance.term-life:2` pack is a descriptive product-catalog profile. It does
not quote, underwrite, bind a policy or process claims. Those decisions remain in the insurer's
own bounded contexts.

`InsuranceTermLifeInteroperabilityAdapter` exposes two deliberately bounded projections from the
schema-governed catalog attributes:

- an ACORD-shaped term-life product profile carrying coverage, premium, insured events, exclusions,
  limits, deductibles and underwriting questions;
- a TMF620-shaped `ProductSpecification` profile whose characteristics carry the same fields.

The conformance test proves that every mandatory reference-pack concern projects and exact decimal
strings remain text. These maps are adapters, not an assertion of full ACORD or TM Forum conformance;
new exchange requirements must add an adapter profile and fixture without changing the generic kernel.

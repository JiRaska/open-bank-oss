# HoneytokenFilter: decoy paths inside a service

`com.openbank.libs.security.HoneytokenFilter` (libs-runtime) is the in-service layer of the
deception programme (ADR-0361). It complements the edge decoys on the shared gateway. It sees
requests that reach a service's namespace, whether they came through Envoy or from an
in-cluster caller that went around it.

## Behaviour

- A service turns it on by setting `OPENBANK_SECURITY_HONEY_PATHS` to a comma-separated list of
  paths. If the list is empty, the filter does nothing.
- A request whose path is on the list increments the decoy-hit counter and writes one log line.
  The Loki rule `HoneyEndpointHit` alerts on that log line.
- The filter does not answer the request. The platform answers with its usual `NOT_FOUND` 404,
  so a decoy path looks the same as any other missing path. The old empty-bodied 404 differed
  from every real 404, which told a prober the path was special.

## Why it must stay `@PreMatching`

JAX-RS calls a post-matching `ContainerRequestFilter` only after a resource method has matched.
A decoy path has no resource method, so RESTEasy answered its own routing 404 and never called
the filter. Until 2026-10-31 the filter had never counted a hit in any service.
`HoneyEndpointIT` in customer-edge reproduces this: with the filter post-matching, the counter
stays at 0.

## Enforcement

The `honeytoken-wiring` gate fails if:

- `HoneyEndpointHit` exists but no workload sets `OPENBANK_SECURITY_HONEY_PATHS`, so the alert
  has nothing to fire on;
- `HoneytokenFilter` loses `@PreMatching`.

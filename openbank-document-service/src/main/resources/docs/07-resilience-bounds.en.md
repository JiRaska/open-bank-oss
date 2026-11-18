# Resilience bounds — openbank-document-service

Operational reference for the declarative resilience configuration seeded in
`application.yaml` by the 2026-10 fleet sweep (billing #1682 convention). The
values below are held by the enforced CI gates `rest-client-timeouts` and
`datasource-pool-self-heal`; loosening them requires an explicit owner decision
in this file's service, not a silent edit.

## REST client timeouts

An unbounded wait on an upstream call is a defect in itself: one slow upstream
holds caller threads until the pool exhausts and the failure surfaces several
services away from its cause.

| Client | connect-timeout | read-timeout |
|---|---|---|
| `product-catalog-api` | 3000 | 5000 |
| `sca-service` | 2000 | 5000 |

## Reactive datasource pool self-heal

Quarkus leaves `idle-timeout` / `max-lifetime` / `max-size` off by default, so a
transient DB blip that poisons pooled connections wedges the service until a pod
restart — with every health probe green (billing #1682: ~8h unreachable against a
healthy DB). These bounds make the pool discard suspect connections on its own.

- `idle-timeout` evicts a dead idle connection within the cleaner period.
- `max-lifetime` is the hard ceiling on how long any wedge can survive.
- `max-size` bounds a reconnect storm.

Configured values:

- `idle-timeout: 5M`
- `max-lifetime: 30M`
- `max-size: 20`



## Seal certificate expiry

The institutional PAdES seal certificate is loaded once at boot from the organizational PKCS#12
keystore. Its expiry is exported as
`openbank_document_seal_certificate_expiry_timestamp_seconds{identity="keystore"|"ephemeral"}`
(`SealCertificateExpiryMetrics`). Only public certificate metadata is exported: no subject, serial
or key material.

- `identity="keystore"`: the organizational certificate. `SealCertificateExpiring` warns 45 days
  before its notAfter, because renewal is a manual ceremony
  (`docs/runbooks/0008-openbao-document-signing-pki.md`).
- `identity="ephemeral"`: the DEV-ONLY fallback. It is never alerted on, and the admin-ui
  Keys & Certificates page shows it as a development seal, not as a healthy countdown.

A renewed keystore is picked up on restart only, so roll the pods after replacing it.

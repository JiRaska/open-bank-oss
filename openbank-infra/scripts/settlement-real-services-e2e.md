# Local settlement-to-ledger-to-balance proof

`settlement-real-services-e2e.py` runs the settlement, ledger, and balance Quarkus services as
separate JVMs against isolated local dependencies. It exercises a real settlement origination,
Temporal workflow, Kafka journal projection, Keycloak OIDC, and the checked-in OPA bundles. It also
checks the cover hold lifecycle, settlement protocol, exact projected balances, duplicate
idempotency, posted journal legs, and service-account authorization log evidence.

This is a **local three-service integration proof only**. It does not exercise external payment
rails, full disaster recovery, or a sandbox deployment, and its result is not evidence for those
paths.

## Prerequisites

- A running Docker daemon and Docker CLI. The script respects the caller's `DOCKER_HOST` setting.
- Python 3.10+ with PyYAML installed (`python3 -m pip install PyYAML`).
- JDK 25 available through `JAVA_HOME` or `PATH`.
- Enough local memory for PostgreSQL, Valkey, Keycloak, Redpanda, Temporal, three OPA containers,
  and three Quarkus JVMs.

Build the service fast-jars from the repository root before running the proof:

```sh
./gradlew :openbank-ledger-service:quarkusBuild \
  :openbank-balance-service:quarkusBuild \
  :openbank-settlement-service:quarkusBuild
```

The script does not build services or pull/deploy to any shared environment. Docker pulls the
declared local dependency images when they are not already present.

## Run

From the repository root:

```sh
python3 openbank-infra/scripts/settlement-real-services-e2e.py
```

The run creates synthetic users, service clients, passwords, account IDs, and a temporary evidence
directory with mode `0700`. Published container ports and service/management listeners bind to
`127.0.0.1`. The script records service and dependency logs plus `result.json` in the printed
evidence directory. Before origination it checks that an unauthenticated balance read returns 401
and an unrelated synthetic `ROLE_API` service identity is denied a hold request with 403. It then terminates
the JVMs and removes every container it created, including on failure. Check the final
`PASS real service flow` line and `result.json`; failed runs retain their logs for diagnosis. The
result records the Git HEAD, whether the working tree was dirty, and SHA-256 digests of the three complete
fast-jar runtimes (application, launcher and libraries). This is provenance metadata only; it does not assert that the jars were built from
that checkout. Service JVMs inherit only `PATH`, `HOME`, `TMPDIR`, `LANG`, and `TZ` before the
explicit local proof configuration is applied.

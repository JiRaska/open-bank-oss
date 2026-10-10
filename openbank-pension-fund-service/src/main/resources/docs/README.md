# OpenBank Pension Fund Service API

Provider side of the pension platform: segregated funds, strategies, NAV and the unit register (ADR-0334).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

The pension service uses the fund and unit register through holdings, transactions, strategies,
and order endpoints. Provider verification tests replay its consumer contract against this service,
including holdings before a NAV is published and requests without a valid service identity.
These tests exercise the provider API; they do not establish that a fund or pension rollout is live.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

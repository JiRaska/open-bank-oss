# Standalone product catalog

This distribution runs the catalog independently of OpenBank. PostgreSQL is its only required
stateful dependency. Authentication uses any OpenID Connect issuer; the service never needs an
identity-provider client secret because it is a resource server.

## Compose quick start

1. Copy `.env.example` to `.env` and set a released image, a fresh database credential, and the
   issuer URL. The defaults map OAuth scopes `catalog:read`, `catalog:author`, and
   `catalog:publish`; the claim and scope names are configurable without provider-specific roles.
2. Select trusted packs explicitly. Leave `OPENBANK_CATALOG_PACKS` empty for a neutral catalog, use
   `insurance` for the reference term-life pack, or `banking,insurance` for the OpenBank showcase.
   Legacy `/api/v1` banking compatibility remains off unless both the banking pack and
   `OPENBANK_BANK_V1_COMPATIBILITY_ENABLED=true` are selected.
3. Run `docker compose --env-file .env up -d` from this directory.
4. Readiness is at `http://127.0.0.1:8085/q/health/ready`; the API is at
   `http://localhost:8104/api/v2`.

For the optional Product Studio, configure the `STUDIO_*` variables and run
`docker compose -f compose.yaml -f compose.studio.yaml up -d`. The shipped UI adapter supports Keycloak and the standard
catalog scopes; the backend API remains compatible with any standards-based OIDC issuer. Product
Studio consumes generated v2 operation paths and DTOs and exposes draft/live diff, contextual
preview, validation, strong-precondition editing, and independent publication.
There is deliberately no mutable public UI image default: `STUDIO_IMAGE` must name a released
immutable image from your UI delivery lane, and all NextAuth/Keycloak secrets are required. Use
HTTPS public and issuer URLs outside the explicitly local `localhost` evaluation setup.

Standalone consumers can poll `GET /api/v2/events?after=<cursor>` for durable ordered changes and
persist the returned `nextCursor` after processing. This keeps Kafka optional without weakening the
transactional outbox guarantee.

The Compose file binds management endpoints to loopback, runs the application read-only without
Linux capabilities, and persists only PostgreSQL data. Put TLS and rate limiting at your ingress.

## Production

Use the Helm chart in `helm/product-catalog`. It deliberately does not install PostgreSQL or an
identity provider. Supply a managed PostgreSQL endpoint, an existing Kubernetes Secret, and a
standards-compliant issuer. Published revisions remain immutable and maker/checker is enforced in
the application even when no OPA sidecar is present. Set `studio.enabled=true` to run the reference
Studio as a same-pod sidecar; bearer traffic to the catalog then stays on loopback.

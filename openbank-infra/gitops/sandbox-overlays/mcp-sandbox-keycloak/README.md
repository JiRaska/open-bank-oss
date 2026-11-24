# Dormant Keycloak for the private MCP sandbox

Compose this component with the `mcp-synthetic-sandbox` namespace foundation. It starts at zero
replicas with an inert image pin. Replace the placeholder with the reviewed optimized OpenBank
Keycloak image digest. Use the provider build that matches the sandbox issuer contract. Startup
uses the baked PostgreSQL and health configuration. The base exposes no Service or public route.
The site TLS overlay owns proxy settings, the private backchannel and its peer policies.

## Required PostgreSQL and bootstrap inputs

Provisioning is separate. Use an approved PostgreSQL service in `mcp-synthetic-sandbox` named
`sandbox-keycloak-postgres` on TCP 5432, selecting only pods labeled
`app.kubernetes.io/name: sandbox-keycloak-postgres`. The existing database owner must provide
`keycloak_synthetic` and a scoped login that owns only that database or its dedicated schema.
Allow ingress on 5432 from the sandbox `keycloak` pods. This component admits only the matching
database pods. The dedicated listener overlay adds cluster DNS egress. If the approved PostgreSQL target uses different selectors or a
different namespace, reconcile both network policy directions before using it.

Supply these site-owned resources in the sandbox namespace.

| Resource | Required keys or files |
| --- | --- |
| ConfigMap `sandbox-keycloak-settings` | `frontend-url` for the canonical HTTPS issuer origin. `jdbc-url` for the exact Service DNS name, port 5432 and database `keycloak_synthetic`, with `sslmode=verify-full&sslrootcert=/mnt/sandbox-keycloak-postgres/ca.crt`. |
| Secret `sandbox-keycloak-postgres-credentials` | `username`, `password` for the scoped database login. |
| Secret `sandbox-keycloak-postgres-trust` | `ca.crt` that validates the database server certificate and exact JDBC DNS name. |
| Secret `sandbox-keycloak-realm-import` | `<realm-name>-realm.json` files for both isolated sandbox realms. Import only synthetic identities and site-owned sandbox client settings. |

Keycloak imports realms at startup only when absent. The database retains realm signing keys and
state across pod recreation. Use an independently approved realm-update mechanism after bootstrap.
Keep these inputs separate from shared Keycloak and supply them before scaling or adding peer
access. Management probes use the distinct port 9000. Check readiness and realm discovery after
the attached TLS overlay and database connection are verified.

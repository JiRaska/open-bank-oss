# Settings access preview

Settings shows a read-only role × permission matrix generated directly from
`openbank-admin-ui/src/lib/auth/roles.ts` (`ROLES` and `PERMISSIONS`). It includes
roles without UI grants. Search filters permission identifiers; the role filter
reduces columns without hiding unlisted grants.

This is an incomplete UI policy catalog bundled with the application, not a live
Keycloak inventory, a user-access report, or evidence that an operation will be
allowed. A check means the role occurs in that UI permission rule; a dash means
only that it does not occur. Keycloak assignments/composites, backend checks, OPA,
resource context and separation of duties can produce a different effective result.
No role assignments or policy changes can be made here.

For future agents: extend the existing catalog rather than copying grants into
this view. Do not use the matrix to authorize requests. Claiming complete or
effective access would require independently verified identity and enforcement
sources, including their provenance and freshness. The browser regression test
is `openbank-admin-ui/e2e/settings-access-preview.spec.ts`.

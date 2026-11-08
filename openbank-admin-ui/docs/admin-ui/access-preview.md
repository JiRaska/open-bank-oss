# Settings access preview

Settings shows a read-only role × permission matrix generated directly from
`openbank-admin-ui/src/lib/auth/roles.ts` (`ROLES` and `PERMISSIONS`). It includes
roles without UI grants. Names and short descriptions in Czech and English come from the typed
`src/lib/auth/access-descriptions.ts` display dictionary. The selected interface
language controls the visible copy, while search matches identifiers, names and
descriptions in both languages, ignoring case and Czech diacritics. Technical
identifiers stay visible. Search filters permission rows; the role filter
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

When adding a role or permission, add both translations and descriptions to the
display dictionary. Type checking and catalog-coverage tests reject missing copy.
Descriptions explain the UI rule only; they must not promise unconditional access.

## Large-catalog layout

The matrix defaults to compact rows: localized names and technical identifiers
remain visible, while the display-only **Show descriptions** switch reveals the
existing bilingual descriptions. Search still includes hidden descriptions.

The single-role dropdown is a shortcut; **Choose roles to compare** supports any
subset of the catalog, with select-all and clear-selection actions. The optional
**Only grants listed for selected roles** filter keeps permissions listed for at
least one selected role. A role selection changes displayed columns only. Empty
selection and empty search results have separate messages.

The scrolling region pins column headers and the permission column; striped rows,
row hover and column separators preserve context. Narrow viewports scroll inside
the matrix rather than widening the page. Display preferences stay in component
state and never update role assignments or policy.

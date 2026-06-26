# Attic scripts — one-off migrations and ad-hoc fixes

Historical JavaScript scripts that were once committed at the repo root.
They served one-time purposes (BPMN migration, service-map regeneration,
duplicate-page cleanup, API link updates in admin-ui docs) and have no
ongoing role.

Kept here rather than deleted so the commit history that references them
stays browsable. Not run from CI, not loaded by any build.

## Contents

- `fix-api-links.js` / `fix-api-links2.js` — patched API documentation
  cross-references in admin-ui docs pages (one-shot).
- `fix-service-map.js` — patched the service-map governance page after
  a service rename (one-shot).
- `remove-dup.js` — deduplicated a list inside one of the docs pages
  (one-shot).
- `update-api-page.js` / `update-service-map.js` — bulk content updates
  triggered by the FX-rates migration (one-shot).
- `update-bpmn.js` / `update-bpmn2.js` / `update-bpmn3.js` /
  `update-bpmn4.js` — incremental edits to embedded BPMN diagram source
  in admin-ui docs (one-shot, each round added a missing piece).

## When NOT to add to this directory

Anything that might be useful again — a tool, a fixture loader, a test
helper — belongs under `scripts/` at the repo root and should be
documented there.

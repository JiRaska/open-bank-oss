# Repository root and entry-guide audit — 2026-11-26

Reviewed for #11917 against source revision `71bba8a54f`.

- The root inventory comes from the Git index. Gradle components qualify through
  their tracked build file. The policy declares the other entry points
  explicitly, including the existing shared `ci/`, `e2e/` and `integrations/`
  directories. Ignored local data is outside the inventory.
- The obsolete root scanner recipe expected a `0.1.0-SNAPSHOT` runner JAR. The
  active scanner recipe is under `openbank-security-scanner/`. The root recipe
  had no build consumer in the tracked tree. Its two validation inventory
  references were removed with it.
- README points to the roadmap milestone snapshot. Architecture and deployment
  guides use the checked-in component and Compose definitions for inventory.
  CONTRIBUTING points to the Compose file used by the local Makefile.
- The CI gate and pre-commit hook use the same index check. Its fixtures cover
  ignored, force-added, unexpected, unclassified and malformed inputs.

This is a source and Git-index audit. Sandbox rollout status and local Compose
startup were outside the verification. The roadmap retains the date of its
separate milestone evidence.

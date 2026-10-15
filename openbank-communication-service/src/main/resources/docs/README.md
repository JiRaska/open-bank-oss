# OpenBank Communication Service

Manages communication personas and versioned styles used by communication flows.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Mobile UI messages

The `customer-copilot` style supports `uiMessages`, a map of approved Czech (`cs.`) and English (`en.`) mobile status copy. The editor presents 166 supported message keys, with at most 240 plain-text characters per language and key (332 entries in total). Other personas, unknown keys, blank values, markup, placeholders and control characters are rejected. Omitted keys leave the mobile app's bundled wording in effect. Payment instructions for an unknown transfer outcome remain fixed in the app and cannot be edited here.

Messages share the style version's existing draft, lint, review, publication and retirement lifecycle. They do not change payment outcomes, retry permissions or security decisions. Editors read `/api/v2/personas/{personaKey}/editor-state`, which atomically returns the current copy and last publication generation. The additive database migration gives existing versions an empty message map. The v2 editor carries the generation into `basePublishedVersion` on each draft (`0` only when no version has ever been published). After explicit retirement the current copy is null, but the generation remains the retired version; an older draft cannot become valid again. Draft version numbers are allocated under the persona lock, so concurrent editors receive distinct versions. Publication compares the base with the last publication generation under the same lock and returns HTTP 409 if it changed; the current text stays published. Drafts created before the base-version migration have an unknown base and must be recreated before publication. To roll back the migration, drop the base-version check constraint and column after rolling back the application.

Style draft, submit, publish and retire writes use `/api/v2/personas` because requiring a draft base breaks the old write contract. Existing consumers continue reading `/api/v1/personas/{personaKey}/published` unchanged.

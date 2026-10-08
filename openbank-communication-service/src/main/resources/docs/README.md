# OpenBank Communication Service

Manages communication personas and versioned styles used by communication flows.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Mobile UI messages

The `customer-copilot` style supports `uiMessages`, a map of approved Czech (`cs.`) and English (`en.`) mobile status copy. The editor presents twelve supported message keys, with at most 240 plain-text characters each. Other personas, unknown keys, blank values, markup, placeholders and control characters are rejected. Omitted keys leave the mobile app's bundled wording in effect.

For `send.profileChanged` and `so.err.profileChanged`, editors supply only one short introductory sentence (up to 120 characters). Customer-edge appends a fixed Czech or English instruction to review and confirm the payment or standing order again. The full instruction is never editable, and an omitted override leaves the app's complete bundled fallback in place.

Messages share the style version's existing draft, lint, review, publication and retirement lifecycle. They do not change payment outcomes, retry permissions or security decisions. Editors can load the published style to preserve existing vocabulary when preparing the next draft. The additive database migration gives existing versions an empty message map.

<!-- SPDX-License-Identifier: Apache-2.0 -->
# Operator navigation

The sidebar shows one workspace area at a time. Its area picker and **All areas** button keep every permitted destination discoverable. Service destinations follow the reviewed domain ownership supplied by the server; cross-domain views, documentation, platform operations and tools remain separate areas. The current route selects its area on navigation, and persona shortcuts remain available across areas.

**Quick search** (`Cmd+K` / `Ctrl+K`) searches Czech and English page names, paths and common synonyms without diacritics. Local page results appear immediately and remain usable if party/account resolution is unavailable. The scope buttons select all results, pages or records. Arrow keys select a result, Enter opens it and Escape returns focus to the opener. Protected tool destinations retain ordinary browser navigation.

The header star saves a named page shortcut. On Accounts and Transactions it also captures the page's supported search filters. Opening a saved view restores the fields; running a search remains an explicit operator action. Switching between views on the same route and browser Back/Forward restore their filters. Other pages save their current URL. The pin dialog describes which behavior applies. Up to twelve named views are stored per operator in the current browser, alongside menu density, width, area and six recently opened pages. Record-search history remains session-only and is scoped to the operator. These preferences are convenience state, never authorization; all destinations are filtered again against current permissions.

Search can hand the query to the existing assistant either with the current page or the selected result as visible context. This opens a draft and focuses the assistant input. It does not submit a model request or execute an action. A new context clears the old conversation and ignores any older reply still in flight. Assistant policies and server-side authorization remain authoritative.

## Verification

- `npm test` runs component, permission, canonical ownership, storage-isolation and stale-assistant-reply tests.
- `npm run build` verifies the production application.
- `OPENBANK_E2E_SERVER=production npm run test:e2e -- e2e/navigation-workspace.spec.ts e2e/command-palette.spec.ts e2e/mobile-shell.spec.ts e2e/sidebar-navigation-smoke.spec.ts` exercises authenticated local browser journeys against the production build.

Browser fixtures verify UI behavior and accessibility; they do not establish live provider data, model quality or a deployed rollout.

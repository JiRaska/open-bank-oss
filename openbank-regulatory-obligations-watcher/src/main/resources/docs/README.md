# openbank-regulatory-obligations-watcher

The runtime of the `regulatory-obligations-watcher` agent (ADR-0367 decision 6, ADR-0387 S4). Once
a week it re-reads every source URL the regulatory obligations register cites and files governance
issues where a source changed, a deadline is still unverified, or a source could not be read.

## One run

1. Load the register packaged into the image at build time (`regulatory-obligations.yaml`), and
   group the obligations by the source URLs they cite.
2. For each URL, call the fetch broker's `web.fetch` (MCP `tools/call`, bearer token of the
   watcher's own Keycloak client: `sub = agent:regulatory-obligations-watcher`,
   `aud ∋ openbank-fetch-broker`). The broker is the only path to the web.
3. Compare the envelope's `sha256` with the per-URL baseline (last FETCHED read only):
   - first read: becomes the baseline, no finding;
   - unchanged: nothing to do;
   - changed: the model compares the citing rows with the text; unless it answers
     `NO_MATERIAL_CHANGE`, one issue is drafted for the URL (obligation ids, URL, quote, sha256);
   - `DENIED` / `NOT_RENDERABLE`: an UNVERIFIED finding. The last good hash is kept; a page that
     could not be read is never reported as unchanged.
4. For each `cadence_confidence: unverified` row whose spec (else law) page was read, the model
   confirms or contradicts the cadence with a quote — once per page content.
5. Before each issue, the OPA sidecar decides `draft.ticket` (tier `write_proposal`); a deny or an
   unreachable sidecar files nothing.

## Untrusted content

The page text goes only into the prompt's data section, inside delimiters with a fresh nonce. The
answer is parsed as one closed JSON object (`verdict` from a closed vocabulary, `obligation_ids`
intersected with the supplied ids, `quote` accepted only if it is verbatim in the page). Anything
else in the answer is ignored. No model output can trigger a fetch, a write or a tool call. Text
copied into an issue has `@`, `#` and `<!--` defused so a page cannot mention users, link issues
or forge the dedup marker.

## Limits and failure modes

- Charter limits: `tokens_per_run` (checked before each model call), `runs_per_day` (counted in
  the database), kill switch (`OPENBANK_REGULATORY_WATCHER_ENABLED=false`, or the fleet
  `llm-gateway.kill` event).
- At most `max-issues-per-run` (5) new issues per run. Anything that cannot be finished (cap,
  budget, no model answer, writer or policy refusal) is DEFERRED: the baseline does not advance,
  so the next run retries it.
- Dedup: a stable fingerprint per finding, checked against this service's own finding history and
  against GitHub issues (open or closed) labelled `governance` carrying the hidden marker.

## Evidence and metrics

- `watch_findings` rows carry the charter's audit fields: model id, prompt hash, tool calls,
  policy decision, obligation id, source URL, source quote.
- `openbank_regulatory_watcher_runs_total{outcome=success|blind|skipped}`, `..._sources_total`,
  `..._sources_changed_total`, `..._issues_filed_total`, `..._findings_deferred_total`,
  `..._llm_tokens_total`.
- ADR-0237 liveness gauge `workflow=regulatory-obligations-watch`: success is recorded only when
  the run read at least one source. A run in which every source failed is `blind`.

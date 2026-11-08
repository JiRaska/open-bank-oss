# Test Intelligence workspace

The default view is a component matrix grouped by the canonical domain catalogue. It initially shows logic, integration, API contracts and end-to-end evidence; operators can expose all evidence kinds. Components and tests are paged in groups of 25. Empty cells mean unverified unless the report explicitly declares the layer inapplicable.

Select a matrix cell to open that component's tests of the selected kind, then select a test to read its detail. The URL retains the section, component, evidence kind and test fingerprint, supports browser history, and can be shared with another authorized operator. A link absent from the retained report slice explains that limit without inventing a result.

The detail separates the author's declared test name, recorded outcome, retained history, and available provenance. An exact per-test run is linked only when retained for that test. Aggregate component runs are never substituted. The collector retains at most ten recent run references per test. It does not retain individual steps, input values or assertion differences; the detail states this explicitly.

Needs attention presents failures, blockers and absent verification in a bounded, searchable list. Runs and history preserves specialist evidence views, with cross-layer summaries behind an explicit disclosure. How testing works provides plain-language explanations and the existing architecture view.

## Contextual AI explanations

The operator explicitly requests an explanation from a test detail. The authenticated BFF resolves the fingerprint against its validated server-side snapshot and rejects a mismatched snapshot timestamp. The browser cannot supply its own prompt or evidence. Only bounded metadata and a redacted declared label are sent; no logs, screenshots, owner identities or assertion input values are sent.

The BFF negotiates `capabilities.toolFreeChat` with the existing agent service before sending metadata. It calls chat with `allowTools: false` and accepts a response only when `toolsRestricted` is true and no tools were called. Existing agent authorization, kill switch, model routing, audit and rate controls apply. Older agents and unavailable providers yield an unavailable explanation, preserving the original evidence. Interpretation is rendered as plain text and marked separately from the recorded verdict. Responses are cached for five minutes per authenticated operator, evidence and language.

Deploy the additive agent capability before the UI to enable explanations. During a staggered deployment the UI remains usable and explains AI unavailability. Neither an explanation nor a missing explanation changes any test verdict, release gate or approval.

The published Test Intelligence snapshot and its local explanation fallback share a 16 MiB hard read limit, allowing the retained roster and bounded run provenance to travel together. Other evidence datasets retain their existing smaller default. Digest validation, source selection and unavailable-source handling are unchanged.

# Customer MCP channel alerts (ADR-0384 D6)

Rules: `openbank-infra/gitops/components/observability/prometheus-rules-mcp-customer.yaml`.
Tests: `openbank-infra/tests/promtool/mcp_customer_alerts_test.yaml`.
Series: `openbank_mcp_customer_rejected_total{layer,reason,client,synthetic}` and
`openbank_mcp_tool_calls_total{...,channel,client,synthetic}` from `McpMetricsAdapter`.
No series carries a party, grant, key or subject; per-party history is the warehouse's (ADR-0384 D3).

## McpCustomerReplayStoreUnavailable

Critical. Customer admission refuses requests with `reason="replay_store_unavailable"`: the DPoP
replay store cannot be reached and the channel fails closed (every customer request answers 503).
The store has no readiness check on purpose, so staff and agent traffic keep working and this alert
is the only signal of the outage.

1. Check the replay store pods and their recent restarts.
2. Check mcp-service logs for store connection errors.
3. Do not disable replay protection to restore service: failing closed is the control.

## McpCustomerDpopRejectSpike

Warning. Over 20% of one client's real requests fail DPoP for 10m (nonce handshakes excluded).

1. Split by `reason`: `stale_iat` suggests clock skew, `htu_mismatch` / `bad_sig` a client release,
   `replay` / `jkt_mismatch` a replayed or stolen proof.
2. A replay or key-mismatch pattern on one client is a candidate for the single-actor client
   suspension (ADR-0384 D4). That action does not exist yet, and there is no in-service block for
   one client. Escalate to the platform on-call and the MCP channel owner, and record the client
   and the reason mix.

## McpCustomerClientErrorRate

Warning. One client's policy and downstream refusals are over three times the channel's ratio.

1. Compare the client's `reason` mix with the others'. `policy_deny` concentrated on one client is
   a client asking for tools its grants do not allow.
2. `downstream_error` concentrated on one client points at the arguments it sends; check the
   downstream service's error logs for the same window.

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

## McpCustomerChannelNoTraffic

Warning. The same hour yesterday carried at least 50 real customer-channel requests and the last
hour carried none at all: no completed tool calls and no refusals. Synthetic canary traffic is not
counted, so a running canary does not hide it. Requests are not reaching mcp-service, which also
silences every ratio alert above.

1. Check the customer route and the gateway in front of it: listener status, certificate expiry,
   recent changes to the route.
2. Fetch the protected-resource metadata document from outside the cluster. Clients that cannot
   read it never send a request.
3. Only then check mcp-service itself (`up`, restarts). A pod roll alone does not fire this alert:
   the old pod's samples stay inside the one-hour window.
4. A quiet hour that is genuinely quiet (a public holiday) clears on its own when traffic returns.

## SLO burn alerts (openbank-mcp-customer-availability / -latency)

Pyrra burn-rate alerts from `pyrra-slo-mcp-customer.yaml`. Availability counts SERVER-SIDE failures
(`openbank_mcp_tool_call_failures_total{kind="error"}`: a tool or downstream fault, or a PDP outage)
against completed customer tool calls that policy did not refuse (real traffic only). A client's
invalid params (`kind="invalid_params"`) and a tool that is not wired yet (`kind="unavailable"`) are
not failures here.
Latency is the share of those calls that finish within the 1.073741824 s histogram bucket.

1. Availability burn: split `openbank_mcp_tool_call_failures_total{channel="customer",kind="error"}`
   by `tool`. One tool points at its downstream service; all tools at once points at the PDP or the
   delegation lookup.
2. Latency burn: compare `openbank_mcp_tool_call_duration_seconds` by `tool`; the PDP round trip is
   included in every call.
3. Requests refused before a tool ran (replay store, throttles) are not in this SLI; the
   McpCustomerReplayStoreUnavailable alert covers the store outage.

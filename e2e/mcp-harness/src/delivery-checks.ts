// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Traceability: every "Delivery check" line of ADR-0383 and ADR-0385, in ADR order, mapped to the
// attack cases that realise it — or to the other test layer that owns it (`layer`). Positive-only
// lines are marked `positive` (they are twins inside a case, or journey steps). A test holds this
// table and ATTACKS to each other in both directions, so the TOTAL in `--list` traces to ADR text.

export interface DeliveryCheck { adr: '0383' | '0385'; line: string; cases?: string[]; layer?: string }

export const DELIVERY_CHECKS: DeliveryCheck[] = [
  { adr: '0383', line: 'No write grant at MCP (opa eval)', layer: 'opa eval over the built bundle (not black-box)' },
  { adr: '0383', line: 'No write grant downstream (opa eval)', layer: 'opa eval over sepa/domestic bundles' },
  { adr: '0383', line: 'Allow-list invariant (MCP_CLIENT unit test)', layer: 'delegation-service unit test' },
  { adr: '0383', line: 'DPoP binding: no proof / other key / replayed jti → 401', cases: ['dpop-proof-missing-401', 'dpop-proof-other-key-401', 'dpop-bad-signature-401', 'dpop-jti-replay-401', 'dpop-htu-mismatch-401', 'dpop-htm-mismatch-401', 'dpop-stale-iat-401', 'dpop-ath-mismatch-401'] },
  { adr: '0383', line: 'Cross-customer isolation (downstream IT + MCP)', cases: ['cross-customer-isolation-at-mcp'], layer: 'downstream ITs per service (P5)' },
  { adr: '0383', line: 'Throttle ordering: tokenless flood, zero OPA calls', layer: 'mcp-service IT (counts PDP calls; not observable black-box)' },
  { adr: '0383', line: 'Proposal approval after consumed SCA; second approval refused', cases: ['proposal-executes-only-after-consumed-sca', 'unauth-propose-payment-refused'] },
  { adr: '0383', line: 'Exposure last (no route)', layer: 'kubectl check in the rollout runbook' },
  { adr: '0385', line: 'Unauthenticated tools/list → 401, no tools', cases: ['unauth-tools-list-empty', 'unauth-read-tool-no-data', 'unauth-401-resource-metadata'] },
  { adr: '0385', line: 'An openbank-app token at MCP → 401', cases: ['openbank-app-token-401', 'forged-token-401', 'other-audience-token-401'] },
  { adr: '0385', line: 'resource ≠ the mcp-service resource → invalid_target', cases: ['resource-confusion-invalid-target'] },
  { adr: '0385', line: 'Tier L azp without cnf.jkt or proof → 401', cases: ['tier-l-without-cnf-jkt-401'] },
  { adr: '0385', line: 'Bearer token on a grant that has a jkt → 401', cases: ['bearer-on-jkt-grant-401'] },
  { adr: '0385', line: 'Tier H refresh without client authentication → invalid_client', cases: ['tier-h-refresh-without-client-auth'] },
  { adr: '0385', line: 'Rotated refresh token reused → family revoked', cases: ['refresh-reuse-revokes-family'] },
  { adr: '0385', line: 'Rotating the client key or secret → grants still refresh', cases: ['grants-survive-credential-rotation'] },
  { adr: '0385', line: 'Realm assertion over customers-realm-template.json', layer: 'realm/allowlist parity gate' },
  { adr: '0385', line: 'request_uri reused / >60 s / other client_id / differing params', cases: ['request-uri-reuse-rejected', 'request-uri-expired-rejected', 'request-uri-other-client-rejected', 'request-uri-differing-params-rejected', 'par-required'] },
  { adr: '0385', line: 'PKCE absent or plain → rejected', cases: ['pkce-absent-rejected', 'pkce-plain-rejected'] },
  { adr: '0385', line: 'iss present in the authorization response', cases: ['iss-in-authorization-response', 'code-injection-rejected'] },
  { adr: '0385', line: 'SSO cookie: prompt=none / prompt=create / kc_action / max_age / login_hint / silent renew', cases: ['sso-prompt-none-login-required', 'sso-prompt-create-refused', 'sso-kc-action-no-effect', 'sso-max-age-omitted-still-fresh', 'sso-login-hint-no-skip'] },
  { adr: '0385', line: 'Realm and allowlist parity; mcp-service refuses to boot on mismatch', layer: 'parity gate + mcp-service boot test' },
  { adr: '0385', line: 'Client secret after exception expiry → invalid_client', cases: ['client-secret-after-exception-expiry'] },
  { adr: '0385', line: 'Registration endpoint unreachable from an MCP authorize', cases: ['registration-unreachable-from-mcp-authorize'] },
  { adr: '0385', line: 'Passkey or SCA device younger than 72 h → refused', cases: ['young-passkey-or-device-refused'] },
  { adr: '0385', line: 'MCP_CLIENT_CONNECT initiate without linking → 400 (and succeeds with it)', cases: ['sca-initiate-requires-linking'] },
  { adr: '0385', line: 'Approval for request A cannot release B', cases: ['approval-a-cannot-release-b'] },
  { adr: '0385', line: 'Two concurrent flows → two challenges', cases: ['concurrent-flows-two-challenges'] },
  { adr: '0385', line: 'consent-service rejects MCP_CLIENT_CONNECT as consent evidence', cases: ['consent-rejects-mcp-client-connect'] },
  { adr: '0385', line: 'ConsumerScope.General spending MCP_CLIENT_CONNECT → refused', cases: ['general-consumer-cannot-spend-mcp-connect'] },
  { adr: '0385', line: 'Two concurrent polls → exactly one code', cases: ['concurrent-polls-one-code'] },
  { adr: '0385', line: 'Consume succeeds, issuance fails → no code, retry refused', cases: ['consume-ok-issuance-fails-no-code'] },
  { adr: '0385', line: 'Auth session older than 300 s → no code', cases: ['auth-session-older-than-300s-no-code'] },
  { adr: '0385', line: 'No code before the consume', cases: ['no-code-before-consume'] },
  { adr: '0385', line: 'App denial → no code, audited DENIED', cases: ['app-denial-no-code'] },
  { adr: '0385', line: 'Same device: handle from another session / party; no passkey; app-link claim', layer: 'layer 9 (manual device protocol)' },
  { adr: '0385', line: 'CIMD: off-allowlist / redirect / private IP; changed hash', layer: 'only if P3 enables CIMD; AS-side IT' },
  { adr: '0385', line: '4th pending authorization → refused and audited', cases: ['fourth-pending-authorization-refused'] },
  { adr: '0385', line: 'Per-client budget exceeded → 429', cases: ['per-client-budget-429'] },
  { adr: '0385', line: 'Valkey down → calls rejected', layer: 'layer 7 (chaos)' },
  { adr: '0385', line: 'Suspend → tokens revoked, credential disabled, next call denied', cases: ['suspend-revokes-client'] },
]

export function traceReport(): string {
  return DELIVERY_CHECKS.map((d) => `ADR-${d.adr}  ${d.line}\n        → ${[...(d.cases ?? []), ...(d.layer ? [`[${d.layer}]`] : [])].join(', ')}`).join('\n')
}

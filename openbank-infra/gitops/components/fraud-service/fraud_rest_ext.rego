# SPDX-License-Identifier: Apache-2.0
# Fraud-service REST extension (ADR-0034 Phase 5, ADR-0084, issue #266).
# Extends openbank.rest with fraud-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (FraudResource):
#   fraud.score       — POST /api/v1/fraud/score: score a payment intent, return a
#                       verdict (ALLOW/CHALLENGE/REVIEW/DECLINE). The only write
#                       endpoint (ADR-0084 §1).
#   fraud.review.read — GET /api/v1/fraud/review-queue: analyst work queue. Covered
#                       by base operator-read-any / compliance-read-any (*.read
#                       suffix) — no rule needed here. NOTE (#3734): that base rule
#                       also admits the M2M identities to this read, the same
#                       pre-existing fleet-wide read over-grant tracked separately;
#                       this PR tightens WRITES only.
#
# Base rest.rego contributes nothing to fraud.score (a non-read action with no
# resource path parameter), so BOTH write allow paths live in this extension.

package openbank.rest

import rego.v1

# Operators and admins may perform ANY fraud operation — the ops console path
# (manual re-score of a payment intent while investigating an alert; future
# rule-management endpoints inherit the same gate before getting their own
# narrower reasons).
#
# The `service-account-` exclusion IS the load-bearing part of this rule (#3734):
# the realm grants ROLE_OPERATOR to BOTH M2M clients — `openbank-services` (shared
# backend) and `openbank-edge` (customer-facing) — and AuthorizeInterceptor
# classifies every client_credentials principal as HUMAN, never "SERVICE". A
# role-only rule therefore hands the customer-facing edge every present and FUTURE
# fraud.* write — the same escalation class fixed for interest in #3698. The
# shared client's legitimate scoring keeps its own identity-scoped rule below
# (service-fraud-scoring); the edge has NO fraud caller at all (fleet audit: no
# fraudServiceUrl exists anywhere in customer-edge) and is vetoed from fraud.score
# by the prohibition at the bottom of this file, which beats the rules.yaml
# role_action_matrix grant that would otherwise still admit it via matrix-allows.
allowed_reasons contains "operator-fraud-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not startswith(input.principal.id, "service-account-")
	startswith(input.action, "fraud.")
}

# M2M payment surfaces calling the real-time scoring gate: fx-service invokes
# POST /score in shadow mode (FraudScoreClient → fraud.score) alongside the
# ADR-0032 sanctions/AML gate; sepa-instant & co. will use the same action once
# wired (payments-services.yaml #1827 note). Deliberately narrow: ONLY
# fraud.score — a future fraud.rules.* management surface must NOT be openable
# by any M2M client, mirroring edge-service-notification's stance that a
# blanket SERVICE allow would open every @Authorize endpoint to any M2M client.
#
# NOTE (found post-merge, issue tracked separately): AuthorizeInterceptor never
# emits principal.type == "SERVICE" — M2M callers authenticate via Keycloak
# client_credentials JWTs, which the interceptor classifies as HUMAN. fx-service
# shares the `openbank-services` client (like nearly every other backend
# service), identity `service-account-openbank-services` — gate on that
# instead. This identity is not unique to fx-service; any other backend
# service sharing the client would also match this rule for fraud.score.
allowed_reasons contains "service-fraud-scoring" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action == "fraud.score"
}

# Fail-closed veto for the customer-edge M2M identity on fraud.score (#3734). The
# service-account exclusion on operator-fraud-write is NOT sufficient by itself:
# rules.yaml's role_action_matrix grants fraud.score to ROLE_OPERATOR, and base
# matrix-allows would still admit `service-account-openbank-edge` through that
# path. Checked at the allow head, this beats ANY reason, present or future.
# Edge-scoped rather than all-service-accounts because the shared client IS the
# legitimate scoring caller (service-fraud-scoring above) — interest (#3698) could
# prohibit every service-account because no M2M writer exists there; fraud cannot.
prohibited if {
	input.principal.id == "service-account-openbank-edge"
	input.action == "fraud.score"
}

# SPDX-License-Identifier: Apache-2.0
# Card-processing-service REST extension (ADR-0283 phase 1, #8809).
# Extends openbank.rest with the card money path's allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (CardProcessingResource):
#   cardprocessing.authorize — an acquirer presents an authorisation
#   cardprocessing.clear     — an acquirer presents a clearing
#   cardprocessing.reverse   — an acquirer releases the remaining hold
#   cardprocessing.read      — read one authorisation, a card's authorisations, a token list or a
#                              dispute case
#
# Actions gated (CardTokenResource, CardDisputeResource — ADR-0283 phase 3):
#   cardprocessing.token     — provision a network token, or change its state
#   cardprocessing.dispute   — open a chargeback case, file evidence, refresh its status
#
# Actions gated (SandboxAcquirerResource):
#   cardprocessing.simulate  — drive a purchase through the sandbox acquirer
#
# WHY THE THREE WRITE ACTIONS ARE NOT OPERATOR-ONLY
#   They are scheme traffic, not console actions: the caller is the processor adapter
#   authenticating with the shared `openbank-services` client-credentials identity, which
#   AuthorizeInterceptor classifies as HUMAN holding ROLE_OPERATOR (rules.yaml
#   authz_policy.principal_type_service_unreachable — `input.principal.type == "SERVICE"` can never
#   fire and must not be written). So the grant necessarily covers a real operator holding the same
#   role; that is a documented fleet-wide limitation, not something this file can narrow.
#
#   What this file CAN do is keep the grant no broader than the resource it guards, in both
#   directions — the property card-issuance's extension learned the hard way when an omitted
#   card.block would have 403'd every lost-or-stolen block the day enforcement flipped on.
#
# NOT GRANTED HERE, DELIBERATELY
#   There is no operator force-clear, manual release or limit override action, because no such
#   endpoint exists. When one ships it needs a four-eyes verb in rules.yaml, not a line here: a
#   maker/checker pause on live scheme traffic would decline real card transactions, but an
#   operator moving money by hand is exactly what four-eyes is for.

package openbank.rest

import rego.v1

# The acquirer-facing money path. One rule for the three write actions because they are one caller
# doing one job — splitting them would suggest they can be granted separately, and no deployment
# wants an adapter that may authorise but not clear.
#
# NAMED `operator-<domain>-write`, not `acquirer-...`, and the name is load-bearing rather than
# cosmetic: `check-operator-write-naming.py` collects rules matching that shape as candidates for
# `rules.yaml: shared_m2m_write_prohibition.reasons` (GHSA-58jq-9hq3-66jr). A differently-named
# rule granting on ROLE_OPERATOR is invisible to that sweep, so the shared openbank-services
# service-account — which carries ROLE_OPERATOR — would reach these actions and nothing would ever
# propose closing it. The accurate description of the caller is in this comment; the name is what
# the sweep can see.
allowed_reasons contains "operator-cardprocessing-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {"cardprocessing.authorize", "cardprocessing.clear", "cardprocessing.reverse"}
}

# Reading an authorisation is an operator/support action as much as an acquirer one: "why was my
# card declined" is answered from these rows, and base rest.rego's operator-read-any does not cover
# an action name it has never seen.
allowed_reasons contains "operator-card-processing-read" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_VIEWER"}
	role in input.principal.roles
	input.action == "cardprocessing.read"
}

# The sandbox acquirer can move money end to end, so it is ROLE_ADMIN only — a strictly narrower
# grant than the real path above, and matching that endpoint's own @RolesAllowed. The endpoint is
# also disabled by default and answers 404 when disabled; this rule is the second lock, not the first.
allowed_reasons contains "admin-card-processing-simulate" if {
	input.principal.type == "HUMAN"
	"ROLE_ADMIN" in input.principal.roles
	input.action == "cardprocessing.simulate"
}

# The token and dispute desks. A SEPARATE reason from the money path above, even though the grant
# is identical today: these are console actions performed by a person, the money-path ones are
# scheme traffic performed by an adapter, and one reason covering both would make the two
# impossible to narrow independently. `check-operator-write-naming.py` sees this name, so the
# shared openbank-services service-account's reach into it stays visible to the GHSA-58jq-9hq3-66jr
# sweep rather than hiding behind a name that sweep cannot match.
#
# Reading a token list or a case is `cardprocessing.read`, already granted above and one role
# wider: a support agent answering "why did my watch stop paying?" needs to see, not to act.
allowed_reasons contains "operator-card-lifecycle-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {"cardprocessing.token", "cardprocessing.dispute"}
}

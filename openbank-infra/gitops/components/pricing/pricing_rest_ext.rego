# SPDX-License-Identifier: Apache-2.0
# Pricing REST authorization extension. It is mounted with OpenBank's shared rest.rego, which owns
# the final deny-by-default decision object at data.openbank.rest.allow.
package openbank.rest

import rego.v1

# Pricing's PDP adapter enriches the shared OpenBank authorization query with the verified tenant
# and OAuth scopes. The policy repeats the endpoint scope contract as an independently evaluated
# OPA fact. No blanket service-account or role grant exists: a future machine caller must receive
# a separately reviewed, action-scoped rule.
#
# Resource-tenant match mirrors the shared "operator-on-own-tenant" rule in rest.rego: a
# resource-scoped action must target the caller's OWN tenant, or a tenant-A principal holding
# the right scope could act on a tenant-B resource (IDOR) purely on scope possession. Where the
# PDP query carries no resource, only explicitly proven resourceless actions can use that
# path. Omitting a deal/quote resource must not turn a tenant-scoped read or write into a
# scope-only authorization decision.
allowed_reasons contains "pricing-oauth-scope" if {
	input.principal.type == "HUMAN"
	not principal_is_machine
	object.get(input.principal.attributes, "tenant", "") != ""
	some scope in object.get(required_scopes, input.action, set())
	scope in object.get(input.principal.attributes, "scopes", [])
	input.action in resourceless_actions
	not input.resource
}

allowed_reasons contains "pricing-oauth-scope" if {
	input.principal.type == "HUMAN"
	not principal_is_machine
	object.get(input.principal.attributes, "tenant", "") != ""
	some scope in object.get(required_scopes, input.action, set())
	scope in object.get(input.principal.attributes, "scopes", [])
	input.resource
	input.principal.attributes.tenant == object.get(object.get(input.resource, "attributes", {}), "tenant", "")
}

# Add an action here only after the PDP input contract proves it has no target resource.
resourceless_actions := {"pricingDecision.calculate"}

required_scopes := {
	"pricingDecision.calculate": {"pricing.quote"},
	"pricingDecision.read": {"pricing.quote"},
	"pricingDecision.replay": {"pricing.quote"},
	"simulation.create": {"pricing.simulate"},
	"simulation.read": {"pricing.simulate"},
	"commitmentEvaluation.create": {"pricing.commitment.evaluate"},
	"commitmentEvaluation.read": {"pricing.commitment.evaluate"},
	"commitmentEvaluation.list": {"pricing.agreement.read"},
	"agreement.create": {"pricing.agreement.activate"},
	"agreement.activate": {"pricing.agreement.activate"},
	"agreement.read": {"pricing.agreement.read"},
	"pricingInstruction.acknowledge": {"pricing.instruction.ack"},
	"publication.read": {"pricing.bundle.read"},
	"publication.submit": {"pricing.bundle.publish"},
	"publication.activate": {"pricing.bundle.publish"},
	"publication.approve": {"pricing.bundle.approve"},
	"deal.create": {"pricing.deal.write"},
	"deal.revise": {"pricing.deal.write"},
	"deal.submit": {"pricing.deal.write"},
	"deal.read": {"pricing.deal.read"},
	"pricingDecision.profitability": {"pricing.quote"},
	"companyProfile.read": {"pricing.deal.read"},
	"publicCompanySignals.read": {"pricing.deal.read"},
	"companyRegistryCoverage.read": {"pricing.deal.read"},
	"companyRegistryCoverage.write": {"pricing.deal.write"},
	"companyContractCoverage.read": {"pricing.deal.read"},
	"companyContractCoverage.write": {"pricing.deal.write"},
	"riskAssessment.read": {"pricing.deal.read"},
	"financialHealth.read": {"pricing.deal.read"},
	"vatValidation.read": {"pricing.deal.read"},
	"dealApproval.read": {"pricing.deal.read"},
	"deal.approve": {"pricing.deal.approve"},
	"priceProposal.submit": {"pricing.price-proposal.write"},
	"priceProposal.read": {"pricing.price-proposal.read"},
	"priceProposal.approve": {"pricing.price-proposal.approve"},
	"aiAdvisory.request": {"pricing.advisory.request"},
	"aiAdvisory.read": {"pricing.advisory.read"},
	# The operation reader deliberately supports any one of the scopes its HTTP handler supports.
	"controlPlaneOperation.read": {
		"pricing.simulate",
		"pricing.commitment.evaluate",
		"pricing.agreement.read",
		"pricing.bundle.read",
		"pricing.advisory.read",
	},
}

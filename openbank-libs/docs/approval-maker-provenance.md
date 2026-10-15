# Approval maker provenance

The shared approval contract records `makerActorKind` at request creation and returns that stored value on reads. Valid kinds are `HUMAN`, `AI_AGENT`, `SERVICE_ACCOUNT`, `CUSTOMER_PARTY`, and `UNKNOWN`; records written before this field existed remain `UNKNOWN` rather than being reclassified from a display ID.

Classification uses the authenticated identity, not a user-controlled display prefix. An agent kind requires a trusted token subject identifying an agent. A service-account kind requires matching authenticated client and username claims. A customer-party kind is an authenticated human carrying the customer role. When provenance cannot be established, the stored kind remains unknown. This evidence does not relax the separate maker/checker authorization check.

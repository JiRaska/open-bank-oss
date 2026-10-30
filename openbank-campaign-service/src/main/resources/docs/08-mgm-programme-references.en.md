# MGM programme references — openbank-campaign-service

A campaign may pin a published MGM programme version by id. The reference is
server-resolved: campaign-service fetches the programme from
openbank-referral-service at write time and rejects unknown, unpublished or
expired ids, so a campaign can never point at a draft or override the
programme's reward or qualification rules — money stays referral-service-owned
(ADR-0266).

## Persisted shape

The campaign row stores the resolved `referralProgramId`, `referralProgramName`
and `referralProgramVersion` (V20, additive, nullable — campaigns without a
programme are unchanged). The name/version snapshot keeps campaigns readable
even if the programme later expires; the id remains the live reference.

## Why the dependency is declared in gitops

The rest-client URL (`referral-service.url`) is explicit in
`application.yaml` so the generated NetworkPolicy discovery knows campaign
egress to the `referral` namespace is expected (issue #7203 acceptance), and
the referral side admits campaign ingress on its policy.

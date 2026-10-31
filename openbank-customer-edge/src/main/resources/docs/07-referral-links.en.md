# Referral links — openbank-customer-edge

The edge forwards the referral-service `referralLink` field so the customer app
never composes URLs itself:

- `POST /customer/v1/referrals/invites` returns it next to the one-time `token`
  (the raw token remains visible only in this response, by design).
- `GET /customer/v1/referrals/invites` includes it per invite, so the app can
  show a copyable link for every invite the customer issued.

The edge stays a whitelist projection: adding `referralLink` does not change
the privacy contract — no referee identity, no token hash, no idempotency key
ever reach the app.

A referee arriving through a shared link lands on the public `/r/<token>`
page (admin-ui), which stores the token and carries it onward as a `ref` query
param; the app posts it to `POST /customer/v1/referrals/attributions` after the
customer signs in, where the rate-limited redemption path binds the token's
party as the referee.

# Referral links — openbank-referral-service

A referrer shares an invite by link, not by raw token. At issuance the service
composes the link as `<base><token>` from the configurable
`openbank.referral.link-base-url` (default `https://app.open-bank.tech/r/`, one
trailing slash, overridable per environment) and persists it on
`referral_invite.referral_link` (V7). The link is then re-readable by the
referrer from `GET /api/v1/referrals/parties/{partyId}/invites`, so the customer
app can render a copyable link per invite at any time.

## Why the link is persisted even though the token is hash-only

The stored token remains a SHA-256 hash — that property is unchanged. The link
is persisted in full because it is a *shareable capability* by design (the same
trust level as a password-reset link): anyone holding it can attribute
themselves as the referee of that invite, nothing more. Reward payout still
requires the programme's qualifying event and is capped at one reward per
(invite, qualification event). Persisting the link is the deliberate tradeoff
that lets a referrer recover their own link without weakening attribution or
payout guards.

## What the referrer list never exposes

The referrer-side projection still carries no referee identity, no raw `token`
field and no idempotency key — only `referralLink`, status and reward
projections. See the `ReferrerInviteView` KDoc for the privacy contract.

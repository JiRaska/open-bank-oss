# MGM program self-serve for marketing — openbank-referral-service

Marketing owns the member-get-member campaigns, so proposing a new referral
program must not require IT. This document describes the propose-then-approve
loop the service exposes for that, and the boundary that keeps money terms
under operator control.

## Roles

- `ROLE_MARKETING` may list the published catalog, list drafts, create a draft
  and read one published program. It cannot issue invites, attribute invites or
  qualify events — those stay `ROLE_OPERATOR`-only, unchanged.
- Publishing a draft stays `ROLE_OPERATOR` (or `ROLE_ADMIN`) only: marketing
  proposes, an operator approves.
- The admin UI mirrors the same split in its permission matrix
  (`referral:program:create` vs `referral:program:publish`), so the role boundary
  is enforced twice — at the service and at the BFF.

## The propose-then-approve loop

1. Marketing drafts a program via `POST /api/v1/referrals/programs` (name, version,
   reward, currency, qualifying event, attribution window). The program is born
   `DRAFT`; the maker is recorded from the token.
2. The draft appears on `GET /api/v1/referrals/programs/drafts` — the approval
   work queue, visible to `ROLE_OPERATOR` and `ROLE_MARKETING`, newest first.
   It is deliberately *not* in `GET /api/v1/referrals/programs`: the published
   catalog remains the audit surface of approved commercial terms only.
3. An operator publishes via `POST /api/v1/referrals/programs/{id}/publish`.
   The service rejects `maker == checker` with 409 — a single identity cannot
   both propose and approve, regardless of how many roles it holds. The UI
   surfaces that conflict as a human explanation, not a raw error.

## Why drafts are a global queue, not per-maker

`GET /programs/drafts` returns every maker's drafts. The queue's consumer is the
approver, who needs to see the whole pending set; a per-maker filter would hide
work from exactly the identity that acts on it. Per-maker filtering can be added
as an optional query parameter later without changing the default.

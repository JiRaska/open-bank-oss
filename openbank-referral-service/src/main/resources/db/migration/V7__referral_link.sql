alter table referral_invite add column referral_link text;
-- Rollback: alter table referral_invite drop column referral_link. Existing rows keep NULL; no data backfill is possible (the token is stored only as a hash, so a link cannot be recomposed for invites issued before this column).

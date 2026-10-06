-- New invites pin the programme version at issue time; rewards inherit that pin.
-- Existing rows stay NULL: the current catalogue is not historical evidence of their version.
alter table referral_invite add column program_version integer;
alter table referral_reward add column program_version integer;
alter table referral_invite add constraint referral_invite_program_version_positive
    check (program_version is null or program_version > 0);
alter table referral_reward add constraint referral_reward_program_version_positive
    check (program_version is null or program_version > 0);
-- Rollback: drop both check constraints, then drop program_version from referral_reward and referral_invite.

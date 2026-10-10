-- Existing drafts have no trustworthy base; leave NULL so publication rejects them.
-- New drafts use 0 when no published version was observed.
alter table style_version add column base_published_version integer;
alter table style_version add constraint style_version_base_published_version_nonnegative
    check (base_published_version >= 0);
-- Rollback: drop the constraint, then drop base_published_version.

-- #12392: the first pension seed shipped eight LEGAL-REVIEW-REQUIRED draft bodies as
-- PUBLISHED. The current seed declares them DRAFT, but the every-boot seeder deliberately
-- leaves rows with existing fixed IDs unchanged. Retire only the exact original seed rows;
-- a different body, version, creator or fixed ID may represent a separately reviewed revision.
-- md5(TEXT) is built into PostgreSQL; these hashes cover the full UTF-8 body_html strings in
-- PensionTemplateBodies.kt, including their trailing newline.
-- Rollback: restore PUBLISHED only after independent legal approval of each exact body and
-- verification that no other PUBLISHED sibling exists for the same code. Do not blanket-reverse.
UPDATE document_templates AS d
SET status = 'DRAFT'
FROM (VALUES
    ('1e575a01-0000-4000-9000-000000000040'::uuid, 'pension-dps-key-information-cs', '8a217485c10d916a5f233f757a01ec2a'),
    ('1e575a01-0000-4000-9000-000000000041'::uuid, 'pension-dps-key-information-en', '7f761d61176007ddc424ffe934204061'),
    ('1e575a01-0000-4000-9000-000000000042'::uuid, 'pension-dip-kid-cs', '9df65d200c18b4474537a1d184eb70b2'),
    ('1e575a01-0000-4000-9000-000000000043'::uuid, 'pension-dip-kid-en', '8275fa1648578d091232c4b995475d74'),
    ('1e575a01-0000-4000-9000-000000000044'::uuid, 'pension-tax-certificate-cs', '4b6d6aa3de9a30d9b8c58aa96d27cac6'),
    ('1e575a01-0000-4000-9000-000000000045'::uuid, 'pension-tax-certificate-en', 'f4015563d48d46b816cd57687b77d2f7'),
    ('1e575a01-0000-4000-9000-000000000046'::uuid, 'pension-annual-statement-cs', '96390b20da45251d2fa9af968813983b'),
    ('1e575a01-0000-4000-9000-000000000047'::uuid, 'pension-annual-statement-en', '7e5fb5ab156bb1a53af4f7ab07155511')
) AS seed(id, code, body_md5)
WHERE d.id = seed.id
  AND d.code = seed.code
  AND d.version = '1.0.0'
  AND d.created_by = 'system'
  AND d.created_at = TIMESTAMPTZ '2026-01-01T00:00:00Z'
  AND d.status = 'PUBLISHED'
  AND md5(d.body_html) = seed.body_md5;

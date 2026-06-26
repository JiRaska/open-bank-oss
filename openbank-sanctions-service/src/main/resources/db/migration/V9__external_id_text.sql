-- V9: Widen external_id from VARCHAR(200) to TEXT.
-- FCDO sanctions CSV has multi-line fields in the 'sanctions' column. When
-- reader.readLine() splits at an embedded newline, the continuation line's
-- first CSV field (mapped to external_id) can exceed 200 chars.
-- TEXT has no length limit and resolves the PgException 22001.

ALTER TABLE sanctions_entries ALTER COLUMN external_id TYPE TEXT;

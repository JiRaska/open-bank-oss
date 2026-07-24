-- Synthetic PAN vault (#3). OpenBank is a sandbox bank: every card number it issues is generated
-- from a documented ISO/IEC 7812 *test* BIN and is Luhn-valid but synthetic — no real cardholder
-- data has ever existed in this service, and none may be loaded into these columns.
--
-- Both columns hold base64( 12-byte IV ‖ AES-256-GCM ciphertext ‖ tag ), never a clear value; the
-- key lives outside the database (openbank.card.pan-encryption-key, OpenBao-projected). Nullable
-- on purpose: cards issued before this migration have no stored credential, and the secure-details
-- endpoint answers CARD_SECURE_DETAILS_NOT_STORED for them rather than inventing one.
--
-- Rollback: ALTER TABLE cards DROP COLUMN pan_encrypted, DROP COLUMN cvv_encrypted;
--           (destroys the stored credentials — they are unrecoverable and must be re-issued.)
ALTER TABLE cards ADD COLUMN pan_encrypted TEXT;
ALTER TABLE cards ADD COLUMN cvv_encrypted TEXT;

COMMENT ON COLUMN cards.pan_encrypted IS
    'Synthetic test PAN, AES-256-GCM, base64(IV||ciphertext||tag). Never a real cardholder PAN.';
COMMENT ON COLUMN cards.cvv_encrypted IS
    'Synthetic CVV/CID, AES-256-GCM, base64(IV||ciphertext||tag). Never a real card verification value.';

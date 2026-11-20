-- SPDX-License-Identifier: Apache-2.0
-- Rollback: retain immutable routing evidence for signed contracts and pending recovery.
CREATE FUNCTION refuse_lending_route_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.route IS DISTINCT FROM OLD.route OR NEW.application_id IS DISTINCT FROM OLD.application_id THEN
        RAISE EXCEPTION 'Pinned lending passport route cannot be changed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER lending_fos_route_keep_pin
BEFORE UPDATE ON lending_fos_route
FOR EACH ROW EXECUTE FUNCTION refuse_lending_route_change();

ALTER TABLE swift_messages ALTER COLUMN value_date TYPE date USING value_date::date;

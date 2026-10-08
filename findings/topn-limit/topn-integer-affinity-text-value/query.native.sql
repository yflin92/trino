-- Native SQLite orders by storage class: the integer 5 sorts BEFORE the text 'zzz',
-- so the pushed-down "ORDER BY v ASC LIMIT 1" yields id=1.
SELECT id, v FROM t ORDER BY v ASC LIMIT 1;

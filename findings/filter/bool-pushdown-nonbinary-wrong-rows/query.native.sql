-- What the connector pushes to SQLite for `flag = true` is `flag = 1`:
SELECT id FROM t WHERE flag = 1;   -- {} (2 <> 1 in SQLite)
-- Trino read mapping instead uses getBoolean() == (value != 0):
--   stored 2 -> true, so `flag = true` MUST include id 1.

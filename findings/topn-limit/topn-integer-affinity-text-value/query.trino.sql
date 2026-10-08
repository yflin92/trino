-- Trino reads id=2's value as 0 (see result.*.csv). ORDER BY v ASC LIMIT 1
-- must therefore return the row Trino sees as smallest, i.e. id=2 (v=0).
SELECT id, v FROM mini.main.t ORDER BY v ASC LIMIT 1;

-- SQLite has two distinct tables: "KÖLN" (row 1) and "köln" (row 2).
-- Trino sees only one, and resolves the wrong one.
SHOW TABLES FROM unicode.main;                 -- lists only "köln"; "KÖLN" is missing
SELECT * FROM unicode.main."KÖLN";             -- returns köln's row (WRONG TABLE)
SELECT * FROM unicode.main."köln";             -- returns köln's row
SELECT table_name FROM unicode.information_schema.tables WHERE table_schema='main';  -- only köln

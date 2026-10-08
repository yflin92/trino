-- Native oracle: the two tables are distinct and both hold their own row.
SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name;  -- KÖLN, köln
SELECT * FROM "KÖLN";   -- (1, 'UPPER-umlaut')
SELECT * FROM "köln";   -- (2, 'lower-umlaut')

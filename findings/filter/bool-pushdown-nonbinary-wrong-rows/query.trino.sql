-- Projection shows flag reads as TRUE:
SELECT id, flag FROM boolbug.main.t;               -- (1, true)

-- WRONG ROWS: pushdown ON drops the row; pushdown OFF keeps it.
SELECT id FROM boolbug.main.t WHERE flag = true;   -- ON: {} ; OFF: {1}
SELECT id FROM boolbug.main.t WHERE flag <> true;  -- ON: {1}; OFF: {}

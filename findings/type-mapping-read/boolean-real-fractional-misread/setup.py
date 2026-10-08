import sqlite3, os

# Build the db NATIVELY with the declared BOOLEAN type the hypothesis needs.
# A BOOLEAN-declared column has NUMERIC affinity in SQLite, so an inserted REAL
# with absolute value in (0,1) is stored as storage-class REAL (typeof='real'),
# e.g. 0.5. SQLite's own boolean semantics treat ANY non-zero value as true.
p = '/tmp/data/boolf.db'
if os.path.exists(p):
    os.remove(p)
c = sqlite3.connect(p)
cur = c.cursor()
cur.execute("CREATE TABLE t(flag BOOLEAN)")
cur.execute("INSERT INTO t VALUES (0.5)")   # stored as REAL 0.5; SQLite truthiness = TRUE
c.commit()

# Native oracle: value, storage class, and SQLite's own truthiness
for r in cur.execute(
        "SELECT flag, typeof(flag), "
        "CASE WHEN flag THEN 'true' ELSE 'false' END AS sqlite_truth FROM t"):
    print(r)   # -> (0.5, 'real', 'true')
c.close()

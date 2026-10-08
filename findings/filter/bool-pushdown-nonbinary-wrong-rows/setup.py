import sqlite3
c = sqlite3.connect('/tmp/data/boolbug.db')
cur = c.cursor()
cur.execute("DROP TABLE IF EXISTS t")
cur.execute("CREATE TABLE t (id INTEGER, flag BOOLEAN)")
# flag stored as integer 2 (a non-0/1 truthy value). SQLite BOOLEAN has NUMERIC affinity.
cur.execute("INSERT INTO t VALUES (1, 2)")
c.commit(); c.close()
print("built /tmp/data/boolbug.db")

import sqlite3, os
p = '/tmp/data/mini.db'
if os.path.exists(p):
    os.remove(p)
c = sqlite3.connect(p)
cur = c.cursor()
# A column with INTEGER affinity. SQLite only applies INTEGER affinity to
# values that convert losslessly; a non-numeric text value is stored as TEXT.
cur.execute("CREATE TABLE t (id INTEGER, v INTEGER)")
cur.execute("INSERT INTO t VALUES (1, 5)")       # stored as integer 5
cur.execute("INSERT INTO t VALUES (2, 'zzz')")   # stays TEXT 'zzz' in an INTEGER-affinity column
c.commit()
for r in cur.execute("SELECT id, v, typeof(v) FROM t"):
    print(r)
c.close()

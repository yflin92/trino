#!/usr/bin/env python3
"""Create the native SQLite file + target table used by the repro."""
import sqlite3
c = sqlite3.connect('/tmp/data/conc.db')
# clean any previous orphan temp tables
for (name,) in c.execute("SELECT name FROM sqlite_schema WHERE name LIKE 'tmp_%'").fetchall():
    c.execute(f'DROP TABLE "{name}"')
c.execute('DROP TABLE IF EXISTS big')
c.execute('CREATE TABLE big (id INTEGER)')
c.commit()
c.close()
print('setup done; native sqlite_version =', sqlite3.sqlite_version)

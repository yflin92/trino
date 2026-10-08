#!/usr/bin/env python3
"""Build the minimal SQLite db that triggers the wrong-table resolution bug.
Two tables distinct in SQLite (ASCII-only NOCASE) but colliding under Trino's
Unicode-aware identifier lowercasing: KÖLN vs köln."""
import sqlite3, os, sys
p = sys.argv[1] if len(sys.argv) > 1 else '/tmp/data/unicode.db'
if os.path.exists(p):
    os.remove(p)
c = sqlite3.connect(p)
# SQLite's identifier dedup uses ASCII-only case folding, so Ö (U+00D6) and ö
# (U+00F6) are NOT folded: these are two DISTINCT tables.
c.execute('CREATE TABLE "KÖLN" (id INTEGER, label TEXT)')
c.execute('CREATE TABLE "köln" (id INTEGER, label TEXT)')
c.execute("INSERT INTO \"KÖLN\" VALUES (1, 'UPPER-umlaut')")
c.execute("INSERT INTO \"köln\" VALUES (2, 'lower-umlaut')")
c.commit()
print("tables:", c.execute("SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name").fetchall())
c.close()

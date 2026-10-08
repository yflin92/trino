#!/usr/bin/env bash
# Self-contained reproduction of the Unicode case-collision WRONG-TABLE bug in
# the SQLite connector (PR yflin92/trino#1 @ 7ce55e6b...).
#
# Fetches the fleet control-branch env-setup.sh, builds the plugin, creates a
# SQLite db with two tables distinct in SQLite but colliding under Trino's
# Unicode-aware identifier lowercasing, starts Trino, and prints Trino vs native.
set -eu
cd /workspace/trino
CTRL=cs_BKgcmnVOmV/fleet-sqlite-20261008-1731-control
git fetch origin "$CTRL"
git show FETCH_HEAD:env-setup.sh > /tmp/env-setup.sh
. /tmp/env-setup.sh
start_docker
prep_files

# Build the db NATIVELY: two tables distinct only by a non-ASCII case pair.
python3 - <<'PY'
import sqlite3, os
p='/tmp/data/unicode.db'
if os.path.exists(p): os.remove(p)
c=sqlite3.connect(p)
c.execute('CREATE TABLE "KÖLN" (id INTEGER, label TEXT)')
c.execute('CREATE TABLE "köln" (id INTEGER, label TEXT)')
c.execute("INSERT INTO \"KÖLN\" VALUES (1,'UPPER-umlaut')")
c.execute("INSERT INTO \"köln\" VALUES (2,'lower-umlaut')")
c.commit(); c.close()
PY
chmod 666 /tmp/data/unicode.db
make_catalog unicode unicode.db
start_trino trino
export TRINO_CONTAINER=trino

echo "=============================================================="
echo "NATIVE (oracle) — two distinct tables, each with its own row:"
python3 -c "import sqlite3;c=sqlite3.connect('/tmp/data/unicode.db');print(' tables:',c.execute(\"SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name\").fetchall());print(' KÖLN :',c.execute('SELECT * FROM \"KÖLN\"').fetchall());print(' köln :',c.execute('SELECT * FROM \"köln\"').fetchall());c.close()"
echo "=============================================================="
echo "TRINO SHOW TABLES (expected 2, bug shows 1 — KÖLN is MISSING):"
tq "SHOW TABLES FROM unicode.main"
echo "--------------------------------------------------------------"
echo 'TRINO SELECT * FROM unicode.main."KÖLN" (expected UPPER-umlaut, bug returns köln row = WRONG TABLE):'
tq 'SELECT * FROM unicode.main."KÖLN"'
echo "--------------------------------------------------------------"
echo 'TRINO SELECT * FROM unicode.main."köln":'
tq 'SELECT * FROM unicode.main."köln"'
echo "=============================================================="
echo "VERDICT: KÖLN (row 1, UPPER-umlaut) is unreachable; querying it returns"
echo "         köln (row 2, lower-umlaut). SHOW TABLES hides one table."

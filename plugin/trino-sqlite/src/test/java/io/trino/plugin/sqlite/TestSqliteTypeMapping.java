/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.sqlite;

import io.trino.sql.planner.plan.FilterNode;
import io.trino.sql.planner.plan.TopNNode;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.QueryRunner;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class TestSqliteTypeMapping
        extends AbstractTestQueryFramework
{
    private TestingSqlite sqlite;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        sqlite = closeAfterClass(new TestingSqlite());
        sqlite.execute(
                """
                CREATE TABLE "Readings" (
                    id INTEGER PRIMARY KEY,
                    sensor VARCHAR(10),
                    small_value TINYINT,
                    reading DOUBLE PRECISION,
                    price DECIMAL(10, 2),
                    wide_price DECIMAL(20, 2),
                    active BOOLEAN,
                    day DATE,
                    payload BLOB,
                    recorded_at DATETIME,
                    quantity NUMERIC,
                    anything)
                """);
        sqlite.execute(
                """
                INSERT INTO "Readings" VALUES
                    (1, 'alpha', 7, 1.5, 12.34, 12.34, 1, '2024-01-15', x'CAFE', '2024-01-15 10:00:00', 3, 'text'),
                    (2, 'beta', -3, NULL, 0.5, 1.5, 0, '2024-02-01', NULL, NULL, 2.5, 42),
                    (9007199254740993, NULL, NULL, -2.25, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                """);
        sqlite.execute("CREATE VIEW active_readings AS SELECT id, sensor, reading * 2 AS doubled FROM \"Readings\" WHERE active");
        sqlite.execute("CREATE TABLE with_sequence (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)");
        sqlite.execute("INSERT INTO with_sequence (name) VALUES ('x')");
        sqlite.execute("CREATE TABLE collated (id INTEGER, nocase TEXT COLLATE NOCASE, rtrim TEXT COLLATE RTRIM)");
        sqlite.execute("INSERT INTO collated VALUES (1, 'a', 'x'), (2, 'A', 'x  '), (3, 'b', 'y'), (4, 'B', NULL)");
        sqlite.execute("CREATE TABLE numbers (n INTEGER)");
        sqlite.execute("INSERT INTO numbers VALUES (9), (10), (100)");
        sqlite.execute("CREATE TABLE bad_dates (day DATE)");
        sqlite.execute("INSERT INTO bad_dates VALUES ('2024/01/15')");

        QueryRunner queryRunner = SqliteQueryRunner.builder(sqlite).build();
        queryRunner.createCatalog("sqlite_forced_varchar", "sqlite", Map.of(
                "connection-url", sqlite.getJdbcUrl(),
                "jdbc-types-mapped-to-varchar", "INTEGER"));
        return queryRunner;
    }

    @Test
    void testDeclaredTypesMapToAffinity()
    {
        assertThat(query("DESCRIBE readings"))
                .result().projected("Column", "Type")
                .skippingTypesCheck()
                .matches(
                        """
                        VALUES
                            ('id', 'bigint'),
                            ('sensor', 'varchar'),
                            ('small_value', 'bigint'),
                            ('reading', 'double'),
                            ('price', 'decimal(10,2)'),
                            ('wide_price', 'varchar'),
                            ('active', 'boolean'),
                            ('day', 'date'),
                            ('payload', 'varbinary'),
                            ('recorded_at', 'varchar'),
                            ('quantity', 'varchar'),
                            ('anything', 'varchar')
                        """);
    }

    @Test
    void testReadValues()
    {
        assertThat(query("SELECT id, sensor, small_value, reading, price, active, day, payload, recorded_at, quantity, anything FROM readings WHERE id <= 2"))
                .matches(
                        """
                        VALUES
                            (BIGINT '1', VARCHAR 'alpha', BIGINT '7', DOUBLE '1.5', CAST(12.34 AS decimal(10, 2)), true, DATE '2024-01-15', X'CAFE', VARCHAR '2024-01-15 10:00:00', VARCHAR '3', VARCHAR 'text'),
                            (BIGINT '2', VARCHAR 'beta', BIGINT '-3', NULL, CAST(0.5 AS decimal(10, 2)), false, DATE '2024-02-01', NULL, NULL, VARCHAR '2.5', VARCHAR '42')
                        """);
        // INTEGER PRIMARY KEY is an alias of the 64-bit rowid
        assertThat(query("SELECT id FROM readings WHERE reading < 0")).matches("VALUES BIGINT '9007199254740993'");
    }

    @Test
    void testInternalTablesAreHidden()
    {
        assertThat(query("SHOW TABLES"))
                .skippingTypesCheck()
                .matches("VALUES 'active_readings', 'bad_dates', 'collated', 'numbers', 'readings', 'with_sequence'");
        assertThat(query("SHOW SCHEMAS"))
                .skippingTypesCheck()
                .matches("VALUES 'information_schema', 'main'");
    }

    @Test
    void testView()
    {
        assertThat(query("SELECT id, sensor, doubled FROM active_readings"))
                .matches("VALUES (BIGINT '1', VARCHAR 'alpha', VARCHAR '3.0')");
    }

    @Test
    void testPredicatePushdown()
    {
        assertThat(query("SELECT id FROM readings WHERE sensor = 'beta'")).isFullyPushedDown();
        assertThat(query("SELECT id FROM readings WHERE sensor > 'alpha'")).isFullyPushedDown();
        assertThat(query("SELECT id FROM readings WHERE day BETWEEN DATE '2024-01-01' AND DATE '2024-01-31'"))
                .matches("VALUES BIGINT '1'")
                .isFullyPushedDown();
        assertThat(query("SELECT id FROM readings WHERE price > 1")).matches("VALUES BIGINT '1'").isFullyPushedDown();
        assertThat(query("SELECT id FROM readings WHERE active")).matches("VALUES BIGINT '1'").isFullyPushedDown();

        // Columns that can hold values of any storage class are filtered by Trino
        assertThat(query("SELECT id FROM readings WHERE anything = '42'"))
                .matches("VALUES BIGINT '2'")
                .isNotFullyPushedDown(FilterNode.class);
    }

    @Test
    void testTopNPushdown()
    {
        assertThat(query("SELECT id FROM readings ORDER BY reading DESC NULLS LAST LIMIT 2"))
                .ordered()
                .matches("VALUES BIGINT '1', BIGINT '9007199254740993'")
                .isFullyPushedDown();
        assertThat(query("SELECT id FROM readings ORDER BY anything LIMIT 1"))
                .isNotFullyPushedDown(TopNNode.class);
    }

    @Test
    void testPushdownIgnoresDeclaredCollation()
    {
        assertThat(query("SELECT id FROM collated WHERE nocase = 'a'"))
                .matches("VALUES BIGINT '1'")
                .isFullyPushedDown();
        assertThat(query("SELECT id FROM collated WHERE nocase IN ('a', 'b')"))
                .matches("VALUES BIGINT '1', BIGINT '3'")
                .isFullyPushedDown();
        assertThat(query("SELECT id FROM collated WHERE nocase IN ('A', 'B') OR nocase > 'a'"))
                .matches("VALUES BIGINT '2', BIGINT '3', BIGINT '4'")
                .isFullyPushedDown();
        assertThat(query("SELECT id FROM collated WHERE nocase NOT IN ('a', 'b')"))
                .matches("VALUES BIGINT '2', BIGINT '4'")
                .isFullyPushedDown();
        assertThat(query("SELECT id FROM collated WHERE nocase < 'a'"))
                .matches("VALUES BIGINT '2', BIGINT '4'")
                .isFullyPushedDown();
        assertThat(query("SELECT id FROM collated WHERE rtrim = 'x'"))
                .matches("VALUES BIGINT '1'")
                .isFullyPushedDown();
        // BINARY collation sorts upper case letters before lower case letters
        assertThat(query("SELECT nocase FROM collated ORDER BY nocase LIMIT 3"))
                .ordered()
                .matches("VALUES VARCHAR 'A', VARCHAR 'B', VARCHAR 'a'")
                .isFullyPushedDown();
    }

    @Test
    void testDatesOutsideSupportedRange()
    {
        assertThat(query("CREATE TABLE out_of_range AS SELECT DATE '10000-01-01' day"))
                .failure().hasMessage("Date must be between 0000-01-01 and 9999-12-31 in SQLite: +10000-01-01");
        assertThat(query("CREATE TABLE out_of_range AS SELECT DATE '-0001-01-01' day"))
                .failure().hasMessage("Date must be between 0000-01-01 and 9999-12-31 in SQLite: -0001-01-01");

        // Bounds outside the supported range do not have the same text format as stored dates, so Trino filters the rows
        assertThat(query("SELECT id FROM readings WHERE day < DATE '10000-01-01'"))
                .matches("VALUES BIGINT '1', BIGINT '2'")
                .isNotFullyPushedDown(FilterNode.class);
        assertThat(query("SELECT id FROM readings WHERE day > DATE '-0001-01-01'"))
                .matches("VALUES BIGINT '1', BIGINT '2'")
                .isNotFullyPushedDown(FilterNode.class);

        assertThat(query("SELECT * FROM bad_dates"))
                .failure().hasMessage("Date value is not in YYYY-MM-DD format: 2024/01/15");
    }

    @Test
    void testTopNOnColumnMappedToVarchar()
    {
        // SQLite sorts the INTEGER values numerically, but Trino sorts the mapped varchar values as text
        assertThat(query("SELECT n FROM sqlite_forced_varchar.main.numbers ORDER BY n LIMIT 2"))
                .ordered()
                .matches("VALUES VARCHAR '10', VARCHAR '100'")
                .isNotFullyPushedDown(TopNNode.class);
    }

    @Test
    void testQueryPassThrough()
    {
        assertThat(query("SELECT * FROM TABLE(system.query(query => 'SELECT sensor, count(*) AS n FROM Readings WHERE sensor IS NOT NULL GROUP BY sensor'))"))
                .matches("VALUES (VARCHAR 'alpha', VARCHAR '1'), (VARCHAR 'beta', VARCHAR '1')");
    }

    @Test
    void testRoundTripFromTrino()
    {
        assertUpdate(
                """
                CREATE TABLE round_trip AS SELECT * FROM (VALUES
                    (true, TINYINT '1', REAL '1.5', DOUBLE '2.5', DECIMAL '123.45', CHAR 'ab', VARCHAR 'text', X'0102', DATE '1970-01-01'))
                    t(b, i, r, d, dec, c, v, bin, dt)
                """,
                1);
        assertThat(query("SELECT * FROM round_trip"))
                .matches("VALUES (true, BIGINT '1', DOUBLE '1.5', DOUBLE '2.5', DECIMAL '123.45', VARCHAR 'ab', VARCHAR 'text', X'0102', DATE '1970-01-01')");
        assertUpdate("DROP TABLE round_trip");
    }

    @Test
    void testUnsupportedWriteType()
    {
        assertThat(query("CREATE TABLE unsupported AS SELECT DECIMAL '12345678901234567890' x"))
                .failure().hasMessage("Unsupported column type: decimal(20,0)");
        assertThat(query("CREATE TABLE unsupported AS SELECT TIMESTAMP '2024-01-01 00:00:00' x"))
                .failure().hasMessage("Unsupported column type: timestamp(0)");
    }
}

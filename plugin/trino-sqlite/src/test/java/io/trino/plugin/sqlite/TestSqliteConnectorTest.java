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

import io.trino.plugin.jdbc.BaseJdbcConnectorTest;
import io.trino.testing.MaterializedResult;
import io.trino.testing.QueryRunner;
import io.trino.testing.TestingConnectorBehavior;
import io.trino.testing.sql.SqlExecutor;
import io.trino.testing.sql.TestTable;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.List;
import java.util.Optional;

import static io.trino.plugin.sqlite.SqliteClient.MAIN_SCHEMA;
import static io.trino.spi.connector.ConnectorMetadata.MODIFYING_ROWS_MESSAGE;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.testing.MaterializedResult.resultBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Isolated
final class TestSqliteConnectorTest
        extends BaseJdbcConnectorTest
{
    private TestingSqlite sqlite;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        sqlite = closeAfterClass(new TestingSqlite());
        return SqliteQueryRunner.builder(sqlite)
                .setInitialTables(REQUIRED_TPCH_TABLES)
                .build();
    }

    @Override
    protected boolean hasBehavior(TestingConnectorBehavior connectorBehavior)
    {
        return switch (connectorBehavior) {
            case SUPPORTS_ADD_COLUMN_NOT_NULL_CONSTRAINT,
                 SUPPORTS_ADD_COLUMN_WITH_COMMENT,
                 SUPPORTS_ADD_COLUMN_WITH_POSITION,
                 SUPPORTS_AGGREGATION_PUSHDOWN,
                 SUPPORTS_ARRAY,
                 SUPPORTS_COMMENT_ON_COLUMN,
                 SUPPORTS_COMMENT_ON_TABLE,
                 SUPPORTS_CREATE_SCHEMA,
                 SUPPORTS_CREATE_TABLE_WITH_COLUMN_COMMENT,
                 SUPPORTS_CREATE_TABLE_WITH_TABLE_COMMENT,
                 SUPPORTS_DROP_NOT_NULL_CONSTRAINT,
                 SUPPORTS_JOIN_PUSHDOWN,
                 SUPPORTS_MAP_TYPE,
                 SUPPORTS_MERGE,
                 SUPPORTS_NEGATIVE_DATE,
                 SUPPORTS_PREDICATE_ARITHMETIC_EXPRESSION_PUSHDOWN,
                 SUPPORTS_RENAME_TABLE_ACROSS_SCHEMAS,
                 SUPPORTS_ROW_LEVEL_UPDATE,
                 SUPPORTS_ROW_TYPE,
                 SUPPORTS_SET_COLUMN_TYPE -> false;
            default -> super.hasBehavior(connectorBehavior);
        };
    }

    @Override
    protected Optional<DataMappingTestSetup> filterDataMappingSmokeTestData(DataMappingTestSetup setup)
    {
        String type = setup.getTrinoTypeName();
        if (type.equals("time") ||
                type.startsWith("time(") ||
                type.startsWith("timestamp") ||
                type.equals("decimal(38,0)") ||
                type.startsWith("array") ||
                type.startsWith("row")) {
            return Optional.of(setup.asUnsupported());
        }
        return Optional.of(setup);
    }

    @Override // Override because integer types are stored as SQLite INTEGER, which is 64-bit
    protected MaterializedResult getDescribeOrdersResult()
    {
        return resultBuilder(getSession(), VARCHAR, VARCHAR, VARCHAR, VARCHAR)
                .row("orderkey", "bigint", "", "")
                .row("custkey", "bigint", "", "")
                .row("orderstatus", "varchar", "", "")
                .row("totalprice", "double", "", "")
                .row("orderdate", "date", "", "")
                .row("orderpriority", "varchar", "", "")
                .row("clerk", "varchar", "", "")
                .row("shippriority", "bigint", "", "")
                .row("comment", "varchar", "", "")
                .build();
    }

    @Test
    @Override // Override because integer types are stored as SQLite INTEGER, which is 64-bit
    public void testShowCreateTable()
    {
        assertThat((String) computeScalar("SHOW CREATE TABLE orders"))
                .isEqualTo(
                        """
                        CREATE TABLE sqlite.main.orders (
                           orderkey bigint,
                           custkey bigint,
                           orderstatus varchar,
                           totalprice double,
                           orderdate date,
                           orderpriority varchar,
                           clerk varchar,
                           shippriority bigint,
                           comment varchar
                        )""");
    }

    @Test
    @Override // Override because integer types are stored as SQLite INTEGER, which is 64-bit
    public void testShowColumns()
    {
        assertThat(computeActual("SHOW COLUMNS FROM orders")).isEqualTo(getDescribeOrdersResult());
    }

    @Test
    void testDefaultWriteParallelism()
    {
        assertThat(computeActual("SHOW SESSION LIKE 'sqlite.write_parallelism'").getMaterializedRows().getFirst().getField(1))
                .isEqualTo("1");
    }

    @Override
    protected TestTable createTableWithDefaultColumns()
    {
        return new TestTable(
                onRemoteDatabase(),
                MAIN_SCHEMA + ".test_default_cols",
                "(col_required INTEGER NOT NULL," +
                        "col_nullable INTEGER," +
                        "col_default INTEGER DEFAULT 43," +
                        "col_nonnull_default INTEGER DEFAULT 42 NOT NULL," +
                        "col_required2 INTEGER NOT NULL)");
    }

    @Override
    protected String errorMessageForCreateTableAsSelectNegativeDate(String date)
    {
        return "Date must be between 0000-01-01 and 9999-12-31 in SQLite: " + date;
    }

    @Override
    protected String errorMessageForInsertNegativeDate(String date)
    {
        return "Date must be between 0000-01-01 and 9999-12-31 in SQLite: " + date;
    }

    @Override
    @Language("RegExp")
    protected String errorMessageForInsertIntoNotNullColumn(String columnName)
    {
        return ".*NOT NULL constraint failed: .*\\." + columnName + ".*";
    }

    @Test
    @Override // Override because the connector does not push down LIKE, which is case-insensitive in SQLite
    public void testDeleteWithLike()
    {
        assertThatThrownBy(super::testDeleteWithLike)
                .hasStackTraceContaining("TrinoException: " + MODIFYING_ROWS_MESSAGE);
    }

    @Test
    @Override // Override because SQLite executes SELECT statements
    public void testExecuteProcedureWithInvalidQuery()
    {
        assertUpdate("CALL system.execute('SELECT 1')");
        assertQueryFails("CALL system.execute('invalid')", "(?s)Failed to execute query.*");
    }

    @Test
    @Override // Override because the driver fails to describe statements that return no columns
    public void testNativeQueryCreateStatement()
    {
        assertThat(getQueryRunner().tableExists(getSession(), "numbers")).isFalse();
        assertThat(query("SELECT * FROM TABLE(system.query(query => 'CREATE TABLE numbers(n INTEGER)'))"))
                .failure().hasMessageContaining("column 1 out of bounds [1,0]");
        assertThat(getQueryRunner().tableExists(getSession(), "numbers")).isFalse();
    }

    @Test
    @Override // Override because the driver fails to describe statements that return no columns
    public void testNativeQueryInsertStatementTableExists()
    {
        try (TestTable testTable = simpleTable()) {
            assertThat(query("SELECT * FROM TABLE(system.query(query => 'INSERT INTO %s VALUES (3)'))".formatted(testTable.getName())))
                    .failure().hasMessageContaining("column 1 out of bounds [1,0]");
            assertQuery("SELECT * FROM " + testTable.getName(), "VALUES 1, 2");
        }
    }

    @Test
    @Override // Override because CHAR is stored as TEXT, and read as VARCHAR without padding
    public void testCharTrailingSpace()
    {
        try (TestTable table = new TestTable(onRemoteDatabase(), MAIN_SCHEMA + ".char_trailing_space", "(x char(10))", List.of("'test'"))) {
            String tableName = table.getName();
            assertQuery("SELECT * FROM " + tableName + " WHERE x = char 'test'", "VALUES 'test'");
            assertQuery("SELECT * FROM " + tableName + " WHERE x = char 'test  '", "VALUES 'test'");
            assertQuery("SELECT * FROM " + tableName + " WHERE x = char 'test        '", "VALUES 'test'");
            assertQueryReturnsEmptyResult("SELECT * FROM " + tableName + " WHERE x = char ' test'");
        }
    }

    @Test
    @Override // Override because CHAR is stored as TEXT, and read as VARCHAR without padding
    public void testCharVarcharComparison()
    {
        // with char->varchar coercion on table creation, this is essentially varchar/varchar comparison
        try (TestTable table = newTrinoTable(
                "test_char_varchar",
                "(k, v) AS VALUES" +
                        "   (-1, CAST(NULL AS CHAR(3))), " +
                        "   (3, CAST('   ' AS CHAR(3)))," +
                        "   (6, CAST('x  ' AS CHAR(3)))")) {
            // varchar of length shorter than column length
            assertThat(query("SELECT k, v FROM " + table.getName() + " WHERE v = CAST('  ' AS varchar(2))")).returnsEmptyResult();
            // varchar of length longer than column length
            assertThat(query("SELECT k, v FROM " + table.getName() + " WHERE v = CAST('    ' AS varchar(4))")).returnsEmptyResult();
            // value that's not all-spaces
            assertThat(query("SELECT k, v FROM " + table.getName() + " WHERE v = CAST('x ' AS varchar(2))")).returnsEmptyResult();
            // exact match
            assertQuery("SELECT k, v FROM " + table.getName() + " WHERE v = CAST('' AS varchar(3))", "VALUES (3, '')");
        }
    }

    @Override
    protected SqlExecutor onRemoteDatabase()
    {
        return sqlite::execute;
    }
}

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

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import io.trino.plugin.base.mapping.IdentifierMapping;
import io.trino.plugin.jdbc.BaseJdbcClient;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ColumnMapping;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcSortItem;
import io.trino.plugin.jdbc.JdbcTableHandle;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.LongWriteFunction;
import io.trino.plugin.jdbc.QueryBuilder;
import io.trino.plugin.jdbc.RemoteTableName;
import io.trino.plugin.jdbc.WriteMapping;
import io.trino.plugin.jdbc.logging.RemoteQueryModifier;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ColumnPosition;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.trino.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static io.trino.plugin.jdbc.PredicatePushdownController.DISABLE_PUSHDOWN;
import static io.trino.plugin.jdbc.StandardColumnMappings.bigintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.bigintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.booleanColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.booleanWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.charWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.doubleColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.doubleWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.integerWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.realWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.shortDecimalReadFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.shortDecimalWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.smallintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.tinyintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.varbinaryColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.varbinaryWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharReadFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharWriteFunction;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.lang.String.format;
import static java.math.RoundingMode.HALF_UP;
import static java.sql.DatabaseMetaData.columnNoNulls;
import static java.sql.DatabaseMetaData.columnNullable;
import static java.util.Locale.ENGLISH;
import static java.util.stream.Collectors.joining;

/**
 * SQLite has no fixed column types: a column's declared type only determines its
 * <a href="https://www.sqlite.org/datatype3.html#type_affinity">type affinity</a>, and any column can hold a value of any storage class.
 * Columns are mapped to Trino types from their declared type, and values are assumed to be stored in the storage class of the column's affinity.
 * Columns whose values cannot be mapped to a single Trino type are exposed as {@code varchar} without predicate pushdown.
 */
public final class SqliteClient
        extends BaseJdbcClient
{
    // SQLite has no schemas, so the database file is exposed as its built-in "main" schema
    public static final String MAIN_SCHEMA = "main";

    // SQLite stores decimal values as 8-byte floating point numbers, which represent at most 15 significant digits exactly
    private static final int MAX_DECIMAL_PRECISION = 15;

    private static final Pattern DECLARED_TYPE_PATTERN = Pattern.compile("(?<name>[^(]*?)\\s*(?:\\(\\s*(?<precision>\\d+)\\s*(?:,\\s*(?<scale>\\d+)\\s*)?\\))?");

    @Inject
    public SqliteClient(
            BaseJdbcConfig config,
            ConnectionFactory connectionFactory,
            QueryBuilder queryBuilder,
            IdentifierMapping identifierMapping,
            RemoteQueryModifier queryModifier)
    {
        super("\"", connectionFactory, queryBuilder, config.getJdbcTypesMappedToVarchar(), identifierMapping, queryModifier, false);
    }

    @Override
    public Connection getConnection(ConnectorSession session)
            throws SQLException
    {
        // The driver only supports opening a connection as read-only, not changing the read-only status of an open connection
        return connectionFactory.openConnection(session);
    }

    @Override
    public Collection<String> listSchemas(Connection connection)
    {
        return ImmutableList.of(MAIN_SCHEMA);
    }

    @Override
    public void createSchema(ConnectorSession session, String schemaName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support creating schemas");
    }

    @Override
    public void dropSchema(ConnectorSession session, String schemaName, boolean cascade)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping schemas");
    }

    @Override
    public void renameSchema(ConnectorSession session, String schemaName, String newSchemaName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support renaming schemas");
    }

    @Override
    public ResultSet getTables(Connection connection, Optional<String> remoteSchemaName, Optional<String> remoteTableName)
            throws SQLException
    {
        // The driver reports tables without a schema, so read the schema table directly.
        // SQLite resolves identifiers case-insensitively, so table names are matched the same way.
        PreparedStatement statement = connection.prepareStatement(
                """
                SELECT NULL AS TABLE_CAT, ? AS TABLE_SCHEM, name AS TABLE_NAME, upper(type) AS TABLE_TYPE, NULL AS REMARKS
                FROM main.sqlite_schema
                WHERE type IN ('table', 'view')
                AND lower(substr(name, 1, 7)) <> 'sqlite_'
                AND ?
                AND (? IS NULL OR name = ? COLLATE NOCASE)
                ORDER BY name
                """);
        try {
            statement.setString(1, MAIN_SCHEMA);
            statement.setBoolean(2, remoteSchemaName.map(MAIN_SCHEMA::equals).orElse(true));
            statement.setString(3, remoteTableName.orElse(null));
            statement.setString(4, remoteTableName.orElse(null));
            statement.closeOnCompletion();
            return statement.executeQuery();
        }
        catch (SQLException e) {
            statement.close();
            throw e;
        }
    }

    @Override
    protected ResultSet getColumns(RemoteTableName remoteTableName, DatabaseMetaData metadata)
            throws SQLException
    {
        // The driver reports columns without a schema, and its DATA_TYPE does not reflect declared types such as BLOB or DATE.
        // Columns are mapped from TYPE_NAME, which holds the declared type.
        PreparedStatement statement = metadata.getConnection().prepareStatement(
                """
                SELECT NULL AS TABLE_CAT, ? AS TABLE_SCHEM, ? AS TABLE_NAME, name AS COLUMN_NAME, %s AS DATA_TYPE, type AS TYPE_NAME,
                    NULL AS COLUMN_SIZE, NULL AS DECIMAL_DIGITS, CASE "notnull" WHEN 0 THEN %s ELSE %s END AS NULLABLE, NULL AS REMARKS
                FROM pragma_table_info(?, ?)
                ORDER BY cid
                """.formatted(Types.OTHER, columnNullable, columnNoNulls));
        try {
            String schemaName = remoteTableName.getSchemaName().orElse(MAIN_SCHEMA);
            statement.setString(1, schemaName);
            statement.setString(2, remoteTableName.getTableName());
            statement.setString(3, remoteTableName.getTableName());
            statement.setString(4, schemaName);
            statement.closeOnCompletion();
            return statement.executeQuery();
        }
        catch (SQLException e) {
            statement.close();
            throw e;
        }
    }

    @Override
    protected void renameTable(ConnectorSession session, Connection connection, String catalogName, String remoteSchemaName, String remoteTableName, String newRemoteSchemaName, String newRemoteTableName)
            throws SQLException
    {
        if (!remoteSchemaName.equals(newRemoteSchemaName)) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support renaming tables across schemas");
        }
        // SQLite does not accept a schema name for the new table name
        execute(session, connection, format(
                "ALTER TABLE %s RENAME TO %s",
                quoted(catalogName, remoteSchemaName, remoteTableName),
                quoted(newRemoteTableName)));
    }

    @Override
    public void addColumn(ConnectorSession session, JdbcTableHandle handle, ColumnMetadata column, ColumnPosition position)
    {
        if (!column.isNullable()) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support adding not null columns");
        }
        super.addColumn(session, handle, column, position);
    }

    @Override
    public void setColumnType(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column, Type type)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support setting column types");
    }

    @Override
    public void dropNotNullConstraint(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping a not null constraint");
    }

    @Override
    public void truncateTable(ConnectorSession session, JdbcTableHandle handle)
    {
        execute(session, "DELETE FROM " + quoted(handle.asPlainTable().getRemoteTableName()));
    }

    @Override
    protected Optional<BiFunction<String, Long, String>> limitFunction()
    {
        return Optional.of((sql, limit) -> sql + " LIMIT " + limit);
    }

    @Override
    public boolean isLimitGuaranteed(ConnectorSession session)
    {
        return true;
    }

    @Override
    public boolean supportsTopN(ConnectorSession session, JdbcTableHandle handle, List<JdbcSortItem> sortOrder)
    {
        // Values of columns without a single mapped type sort differently in SQLite, for example numbers before text
        return sortOrder.stream().allMatch(sortItem -> classify(sortItem.column().getJdbcTypeHandle()) != SqliteType.ANY);
    }

    @Override
    protected Optional<TopNFunction> topNFunction()
    {
        return Optional.of((query, sortItems, limit) -> {
            String orderBy = sortItems.stream()
                    .map(sortItem -> format(
                            "%s %s NULLS %s",
                            quoted(sortItem.column().getColumnName()),
                            sortItem.sortOrder().isAscending() ? "ASC" : "DESC",
                            sortItem.sortOrder().isNullsFirst() ? "FIRST" : "LAST"))
                    .collect(joining(", "));
            return format("%s ORDER BY %s LIMIT %d", query, orderBy, limit);
        });
    }

    @Override
    public boolean isTopNGuaranteed(ConnectorSession session)
    {
        return true;
    }

    @Override
    public Optional<ColumnMapping> toColumnMapping(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle)
    {
        Optional<ColumnMapping> mapping = getForcedMappingToVarchar(typeHandle);
        if (mapping.isPresent()) {
            return mapping;
        }
        return Optional.of(switch (classify(typeHandle)) {
            case INTEGER -> bigintColumnMapping();
            // Text is compared with the BINARY collation by default, which matches Trino's ordering of varchar values
            case TEXT -> varcharColumnMapping(VARCHAR, true);
            case BLOB -> varbinaryColumnMapping();
            case REAL -> doubleColumnMapping();
            case BOOLEAN -> booleanColumnMapping();
            case DATE -> dateColumnMapping();
            case DECIMAL -> decimalColumnMapping(decimalType(typeHandle).orElseThrow());
            case ANY -> ColumnMapping.sliceMapping(VARCHAR, varcharReadFunction(VARCHAR), varcharWriteFunction(), DISABLE_PUSHDOWN);
        });
    }

    @Override
    public WriteMapping toWriteMapping(ConnectorSession session, Type type)
    {
        if (type == BOOLEAN) {
            return WriteMapping.booleanMapping("BOOLEAN", booleanWriteFunction());
        }
        if (type == TINYINT) {
            return WriteMapping.longMapping("INTEGER", tinyintWriteFunction());
        }
        if (type == SMALLINT) {
            return WriteMapping.longMapping("INTEGER", smallintWriteFunction());
        }
        if (type == INTEGER) {
            return WriteMapping.longMapping("INTEGER", integerWriteFunction());
        }
        if (type == BIGINT) {
            return WriteMapping.longMapping("INTEGER", bigintWriteFunction());
        }
        if (type == REAL) {
            return WriteMapping.longMapping("REAL", realWriteFunction());
        }
        if (type == DOUBLE) {
            return WriteMapping.doubleMapping("REAL", doubleWriteFunction());
        }
        if (type instanceof DecimalType decimalType && decimalType.getPrecision() <= MAX_DECIMAL_PRECISION) {
            return WriteMapping.longMapping(
                    "DECIMAL(%s, %s)".formatted(decimalType.getPrecision(), decimalType.getScale()),
                    shortDecimalWriteFunction(decimalType));
        }
        if (type instanceof CharType) {
            return WriteMapping.sliceMapping("TEXT", charWriteFunction());
        }
        if (type instanceof VarcharType) {
            return WriteMapping.sliceMapping("TEXT", varcharWriteFunction());
        }
        if (type == VARBINARY) {
            return WriteMapping.sliceMapping("BLOB", varbinaryWriteFunction());
        }
        if (type == DATE) {
            return WriteMapping.longMapping("DATE", dateWriteFunction());
        }
        throw new TrinoException(NOT_SUPPORTED, "Unsupported column type: " + type.getDisplayName());
    }

    private enum SqliteType
    {
        INTEGER, TEXT, BLOB, REAL, BOOLEAN, DATE, DECIMAL, ANY
    }

    private static SqliteType classify(JdbcTypeHandle typeHandle)
    {
        String declaredType = typeHandle.jdbcTypeName().orElse("").toUpperCase(ENGLISH);
        // Affinity rules, applied in order: https://www.sqlite.org/datatype3.html#determination_of_column_affinity
        if (declaredType.contains("INT")) {
            return SqliteType.INTEGER;
        }
        if (declaredType.contains("CHAR") || declaredType.contains("CLOB") || declaredType.contains("TEXT")) {
            return SqliteType.TEXT;
        }
        if (declaredType.contains("BLOB")) {
            return SqliteType.BLOB;
        }
        if (declaredType.contains("REAL") || declaredType.contains("FLOA") || declaredType.contains("DOUB")) {
            return SqliteType.REAL;
        }
        // The remaining columns have NUMERIC affinity, or no declared type. Only the following store values of a single storage class.
        Matcher matcher = DECLARED_TYPE_PATTERN.matcher(declaredType.strip());
        String typeName = matcher.matches() ? matcher.group("name") : declaredType;
        return switch (typeName) {
            case "BOOLEAN", "BOOL" -> SqliteType.BOOLEAN;
            case "DATE" -> SqliteType.DATE;
            case "DECIMAL", "NUMERIC" -> decimalType(typeHandle).isPresent() ? SqliteType.DECIMAL : SqliteType.ANY;
            default -> SqliteType.ANY;
        };
    }

    private static Optional<DecimalType> decimalType(JdbcTypeHandle typeHandle)
    {
        String declaredType = typeHandle.jdbcTypeName().orElse("").strip();
        Matcher matcher = DECLARED_TYPE_PATTERN.matcher(declaredType);
        int precision;
        int scale;
        if (matcher.matches() && matcher.group("precision") != null) {
            precision = Integer.parseInt(matcher.group("precision"));
            scale = matcher.group("scale") == null ? 0 : Integer.parseInt(matcher.group("scale"));
        }
        else {
            // Result set metadata reports the precision and scale separately from the type name
            precision = typeHandle.columnSize().orElse(0);
            scale = typeHandle.decimalDigits().orElse(0);
        }
        if (precision < 1 || precision > MAX_DECIMAL_PRECISION || scale > precision) {
            return Optional.empty();
        }
        return Optional.of(createDecimalType(precision, scale));
    }

    private static ColumnMapping decimalColumnMapping(DecimalType decimalType)
    {
        // Values are stored as floating point numbers, so they are rounded back to the declared scale
        return ColumnMapping.longMapping(decimalType, shortDecimalReadFunction(decimalType, HALF_UP), shortDecimalWriteFunction(decimalType));
    }

    private static ColumnMapping dateColumnMapping()
    {
        // SQLite has no date storage class. Dates are stored as ISO-8601 text, whose ordering matches the ordering of dates.
        return ColumnMapping.longMapping(
                DATE,
                (resultSet, columnIndex) -> {
                    String value = resultSet.getString(columnIndex);
                    try {
                        return LocalDate.parse(value).toEpochDay();
                    }
                    catch (DateTimeParseException e) {
                        throw new TrinoException(JDBC_ERROR, "Date value is not in YYYY-MM-DD format: " + value, e);
                    }
                },
                dateWriteFunction());
    }

    private static LongWriteFunction dateWriteFunction()
    {
        return LongWriteFunction.of(Types.VARCHAR, (statement, index, day) -> statement.setString(index, LocalDate.ofEpochDay(day).toString()));
    }
}

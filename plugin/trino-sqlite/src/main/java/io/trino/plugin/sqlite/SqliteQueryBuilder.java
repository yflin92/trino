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

import com.google.inject.Inject;
import io.trino.plugin.jdbc.DefaultQueryBuilder;
import io.trino.plugin.jdbc.JdbcClient;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.QueryParameter;
import io.trino.plugin.jdbc.WriteFunction;
import io.trino.plugin.jdbc.logging.RemoteQueryModifier;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.predicate.Range;
import io.trino.spi.predicate.ValueSet;
import io.trino.spi.type.Type;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.plugin.sqlite.SqliteClient.BINARY_COLLATION;
import static io.trino.plugin.sqlite.SqliteClient.isText;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.Collections.nCopies;

/**
 * Compares text columns with the BINARY collation, which orders text the same way as Trino.
 * Otherwise SQLite compares a column with its declared collation, such as NOCASE or RTRIM.
 */
public final class SqliteQueryBuilder
        extends DefaultQueryBuilder
{
    @Inject
    public SqliteQueryBuilder(RemoteQueryModifier queryModifier)
    {
        super(queryModifier);
    }

    @Override
    protected String toPredicate(JdbcClient client, ConnectorSession session, Connection connection, JdbcColumnHandle column, ValueSet valueSet, Consumer<QueryParameter> accumulator)
    {
        if (!isText(column.getJdbcTypeHandle()) || (!valueSet.isDiscreteSet() && valueSet.complement().isDiscreteSet())) {
            // A negated discrete set is formatted by calling this method with the complement
            return super.toPredicate(client, session, connection, column, valueSet, accumulator);
        }

        List<Range> ranges = valueSet.getRanges().getOrderedRanges();
        List<Object> singleValues = ranges.stream()
                .filter(Range::isSingleValue)
                .map(Range::getSingleValue)
                .collect(toImmutableList());
        if (singleValues.size() < 2) {
            return super.toPredicate(client, session, connection, column, valueSet, accumulator);
        }

        // The base implementation formats multiple single values as an IN list, which SQLite compares with the collation of the column
        List<String> disjuncts = new ArrayList<>();
        List<Range> otherRanges = ranges.stream()
                .filter(range -> !range.isSingleValue())
                .collect(toImmutableList());
        if (!otherRanges.isEmpty()) {
            disjuncts.add(super.toPredicate(client, session, connection, column, ValueSet.ofRanges(otherRanges), accumulator));
        }
        JdbcTypeHandle jdbcType = column.getJdbcTypeHandle();
        Type type = column.getColumnType();
        WriteFunction writeFunction = client.toColumnMapping(session, connection, jdbcType).orElseThrow().getWriteFunction();
        for (Object value : singleValues) {
            accumulator.accept(new QueryParameter(jdbcType, type, Optional.of(value)));
        }
        disjuncts.add(format(
                "%s COLLATE %s IN (%s)",
                client.quoted(column.getColumnName()),
                BINARY_COLLATION,
                join(",", nCopies(singleValues.size(), writeFunction.getBindExpression()))));

        if (disjuncts.size() == 1) {
            return disjuncts.getFirst();
        }
        return "(" + join(" OR ", disjuncts) + ")";
    }

    @Override
    protected String toPredicate(JdbcClient client, ConnectorSession session, JdbcColumnHandle column, JdbcTypeHandle jdbcType, Type type, WriteFunction writeFunction, String operator, Object value, Consumer<QueryParameter> accumulator)
    {
        if (isText(jdbcType)) {
            accumulator.accept(new QueryParameter(jdbcType, type, Optional.of(value)));
            return format("%s COLLATE %s %s %s", client.quoted(column.getColumnName()), BINARY_COLLATION, operator, writeFunction.getBindExpression());
        }
        return super.toPredicate(client, session, column, jdbcType, type, writeFunction, operator, value, accumulator);
    }
}

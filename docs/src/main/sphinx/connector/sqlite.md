# SQLite connector

The SQLite connector allows querying and creating tables in a
[SQLite](https://sqlite.org/) database file. This can be used to join data
in SQLite files with data in other systems, or to copy data between them.

## Requirements

* The SQLite database file must be available at the same path on all cluster
  nodes, for example on shared storage.

## Configuration

Create a catalog properties file that specifies the SQLite connector by
setting the `connector.name` to `sqlite`.

For example, to access a database as the `example` catalog, create the file
`etc/catalog/example.properties`:

```none
connector.name=sqlite
connection-url=jdbc:sqlite:<path>
```

The `<path>` points to the SQLite database file, for example
`jdbc:sqlite:/data/example.db`. If the file does not exist, it is created.
The `connection-url` can include parameters of the [SQLite JDBC
driver](https://github.com/xerial/sqlite-jdbc), for example
`jdbc:sqlite:/data/example.db?busy_timeout=30000` to wait longer for other
writers to release their lock, or `?open_mode=1` to open the file read-only.

SQLite has no schemas. The tables and views of the database file are
available in the `main` schema. Internal SQLite tables, with names that start
with `sqlite_`, are not listed.

SQLite allows a single writer per database file at a time, so the connector
writes data with a single writer.

(sqlite-type-mapping)=
## Type mapping

SQLite has no fixed column types. The declared type of a column only
determines its [type affinity](https://www.sqlite.org/datatype3.html), and
a column can hold values of any type. The connector maps each column from its
declared type, and expects the values in the column to match the column's
affinity. For example, the connector reads a text value in an `INTEGER`
column as `0`.

### SQLite type to Trino type mapping

The connector maps declared SQLite types to Trino types following the SQLite
rules to determine affinity, applied in the order of this table:

:::{list-table} SQLite type to Trino type mapping
:widths: 30, 30, 40
:header-rows: 1

* - Declared SQLite type
  - Trino type
  - Notes
* - Contains `INT`, for example `INTEGER` or `TINYINT`
  - `BIGINT`
  - SQLite stores all integers as 64-bit values.
* - Contains `CHAR`, `CLOB`, or `TEXT`
  - `VARCHAR`
  - The length of the declared type is ignored.
* - Contains `BLOB`
  - `VARBINARY`
  -
* - Contains `REAL`, `FLOA`, or `DOUB`
  - `DOUBLE`
  -
* - `BOOLEAN`
  - `BOOLEAN`
  -
* - `DATE`
  - `DATE`
  - Values must be stored as text in `YYYY-MM-DD` format, with a year
    between 0000 and 9999.
* - `DECIMAL(p, s)` or `NUMERIC(p, s)` with `p` up to 15
  - `DECIMAL(p, s)`
  - SQLite stores decimal values as floating point numbers.
* - Any other type, or no declared type
  - `VARCHAR`
  - For example `DATETIME`, `NUMERIC`, and columns of views that are computed
    from expressions. The connector reads the text representation of the
    value, and does not push down predicates or sorting on the column.
:::

### Trino type to SQLite type mapping

The connector maps Trino types to SQLite types following this table:

:::{list-table} Trino type to SQLite type mapping
:widths: 30, 30, 40
:header-rows: 1

* - Trino type
  - SQLite type
  - Notes
* - `BOOLEAN`
  - `BOOLEAN`
  -
* - `TINYINT`, `SMALLINT`, `INTEGER`, `BIGINT`
  - `INTEGER`
  - Read back as `BIGINT`.
* - `REAL`, `DOUBLE`
  - `REAL`
  - Read back as `DOUBLE`. SQLite stores `NaN` as `NULL`.
* - `DECIMAL(p, s)` with `p` up to 15
  - `DECIMAL(p, s)`
  -
* - `CHAR`, `VARCHAR`
  - `TEXT`
  - Read back as `VARCHAR`.
* - `VARBINARY`
  - `BLOB`
  -
* - `DATE`
  - `DATE`
  - Stored as text in `YYYY-MM-DD` format. Dates must be between
    `0000-01-01` and `9999-12-31`.
:::

No other types are supported.

```{include} jdbc-type-mapping.fragment
```

(sqlite-sql-support)=
## SQL support

The connector provides read access and write access to data and metadata in
a SQLite database. In addition to the {ref}`globally available
<sql-globally-available>` and {ref}`read operation <sql-read-operations>`
statements, the connector supports the following features:

- {doc}`/sql/insert`
- {doc}`/sql/update`
- {doc}`/sql/delete`
- {doc}`/sql/truncate`
- {doc}`/sql/create-table`
- {doc}`/sql/create-table-as`
- {doc}`/sql/drop-table`
- {doc}`/sql/alter-table`

```{include} sql-update-limitation.fragment
```

```{include} sql-delete-limitation.fragment
```

### Procedures

```{include} jdbc-procedures-flush.fragment
```
```{include} procedures-execute.fragment
```

### Table functions

The connector provides specific [table functions](/functions/table) to
access SQLite.

(sqlite-query-function)=
#### `query(varchar) -> table`

The `query` function allows you to query the underlying database directly. It
requires syntax native to SQLite, because the full query is pushed down and
processed in SQLite. This can be useful for accessing native features which
are not available in Trino or for improving query performance in situations
where running a query natively may be faster.

SQLite does not report the types of computed columns, so the function returns
computed columns, such as the result of `count(*)`, as `VARCHAR`.

```{include} query-passthrough-warning.fragment
```

As a simple example, query the `example` catalog and select an entire table:

```
SELECT
  *
FROM
  TABLE(
    example.system.query(
      query => 'SELECT
        *
      FROM
        orders'
    )
  );
```

```{include} query-table-function-ordering.fragment
```

## Performance

The connector includes a number of performance improvements, detailed in the
following sections.

(sqlite-pushdown)=
### Pushdown

The connector supports pushdown for a number of operations:

- {ref}`limit-pushdown`
- {ref}`topn-pushdown`

Predicates are pushed down for all columns except columns that are mapped
to `VARCHAR` from types without a text affinity, or with the
`jdbc-types-mapped-to-varchar` catalog property. Predicates and sorting on text
columns are pushed down with the `BINARY` collation, which compares text the
same way as Trino, also for columns declared with a different collation such as
`COLLATE NOCASE`.

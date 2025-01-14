package tech.ydb.importer.source;

import tech.ydb.importer.TableDecision;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import tech.ydb.importer.config.*;
import static tech.ydb.importer.config.JdomHelper.*;

/**
 *
 * @author zinal
 */
public abstract class AnyTableLister {

    private final static org.slf4j.Logger LOG = org.slf4j.LoggerFactory
            .getLogger(AnyTableLister.class);
    
    protected final TableMapList tableMaps;

    public AnyTableLister(TableMapList tableMaps) {
        this.tableMaps = tableMaps;
    }

    protected abstract List<String> listSchemas(Connection con) throws Exception;
    protected abstract List<String> listTables(Connection con, String schema) throws Exception;
    // Safely quote the identifier
    protected abstract String safeId(String id);
    // Find the approximate row count. Returns -1 if one is not known.
    protected abstract long grabRowCount(Connection con, TableIdentity td) throws Exception;
    // Retrieve the list of columns for the table
    protected abstract List<ColumnInfo> grabColumnNames(Connection con, TableIdentity td)
            throws Exception;
    // Retrieve the primary key (if one is defined), or the "minimal" unique key
    protected abstract void grabPrimaryKey(Connection con, TableIdentity ti, TableMetadata tm)
            throws Exception;

    public List<TableDecision> selectTables(Connection con) throws Exception {
        final HashSet<SourceTableName> keys = new HashSet<>();
        final List<TableDecision> retval = new ArrayList<>();
        // Add the pre-configured table names
        for (TableRef tr : tableMaps.getRefs()) {
            if ( ! keys.add(new SourceTableName(tr)) ) {
                LOG.warn("Duplicate table reference name {}.{}", tr.getSchema(), tr.getTable());
                continue;
            }
            final TableDecision td = new TableDecision(tr);
            retval.add(td);
        }
        final List<TableMapRunner> workers = new ArrayList<>();
        for (String schema : listSchemas(con)) {
            // Decide if schema matches the filters.
            workers.clear();
            for ( TableMapRunner tmr : tableMaps.getMaps() ) {
                if (tmr.schemaMatches(schema))
                    workers.add(tmr);
            }
            if ( workers.isEmpty() )
                continue;
            // Grab the tables and filter them.
            for ( String table : listTables(con, schema) ) {
                for ( TableMapRunner tmr : workers ) {
                    if (tmr.tableMatches(table)) {
                        if ( ! keys.add(new SourceTableName(schema, table)) ) {
                            LOG.debug("Skipping duplicate table name {}.{}", schema, table);
                            continue;
                        }
                        final TableDecision td =
                                new TableDecision(schema, table, tmr.getOptions());
                        retval.add(td);
                        break;
                    }
                }
            }
        }
        return retval;
    }

    public TableMetadata readMetadata(Connection con, TableDecision td) throws Exception {
        final TableMetadata tm = new TableMetadata();
        if (td.getTableRef() == null) {
            tm.addColumns(grabColumnNames(con, td));
            grabColumnTypes(con, td, tm);
            grabPrimaryKey(con, td, tm);
        } else {
            if (! td.getTableRef().hasQueryText()) {
                // With non-custom SQL let's read the columns from the data dictionary.
                tm.addColumns(grabColumnNames(con, td));
            }
            // Columns get appended to TableMetadata if they are missing.
            grabColumnTypes(con, td, tm);
            // If the key is declared in the table reference, use it.
            declaredPrimaryKey(td, tm);
            if (tm.getKey().isEmpty()) {
                // If the key is not declared, and the table does not have an associated query,
                // try to grab the key columns from the source database.
                if (! td.getTableRef().hasQueryText())
                    grabPrimaryKey(con, td, tm);
            }
        }
        return tm;
    }

    protected void grabColumnTypes(Connection con, TableDecision td, TableMetadata tm) 
            throws Exception {
        String sql = tm.getBasicSql();
        if (isBlank(sql)) {
            sql = makeSql(td, tm.getColumns());
            tm.setBasicSql(sql);
        }
        sql = "SELECT q.* FROM (" + sql + ") q WHERE 0=1"; // retrieve zero rows
        LOG.debug("Metadata retrieval SQL: {}", sql);
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                final ResultSetMetaData rsmd = rs.getMetaData();
                final int colcount = rsmd.getColumnCount();
                for (int i=1; i<=colcount; ++i) {
                    final String colname = rsmd.getColumnName(i);
                    ColumnInfo ci = tm.findColumn(colname);
                    if (ci==null) {
                        ci = new ColumnInfo(colname);
                        tm.addColumn(ci);
                    }
                    ci.setSqlType(rsmd.getColumnType(i));
                    ci.setSqlPrecision(rsmd.getPrecision(i));
                    ci.setSqlScale(rsmd.getScale(i));
                }
            }
        }
    }

    private String makeSql(TableDecision td, List<ColumnInfo> columns) {
        if (td.getTableRef() != null && td.getTableRef().hasQueryText())
            return td.getTableRef().getQueryText();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        if (columns == null || columns.isEmpty()) {
            sql.append("*");
        } else {
            boolean comma = false;
            for (ColumnInfo ci : columns) {
                if (comma) sql.append(", "); else comma = true;
                sql.append(safeId(ci.getName()));
            }
        }
        sql.append(" FROM ");
        sql.append(safeId(td.getSchema()));
        sql.append(".");
        sql.append(safeId(td.getTable()));
        return sql.toString();
    }

    private void declaredPrimaryKey(TableDecision td, TableMetadata tm) {
        if (td.getTableRef()==null)
            return;
        tm.clearKey();
        for (String keyField : td.getTableRef().getKeyNames()) {
            ColumnInfo ci = tm.findColumn(keyField);
            if (ci==null) {
                throw new RuntimeException("Missing key field " + keyField 
                        + " in referenced table " + td.getSchema() + "." + td.getTable());
            }
            tm.addKey(ci.getName());
        }
    }

    /**
     * Build the instance of a customized table lister for a particular source database type
     * @param tableMaps
     * @param jdbc
     * @return 
     */
    public static AnyTableLister getInstance(TableMapList tableMaps) {
        final SourceType st = tableMaps.getConfig().getSource().getType();
        switch (st) {
            case ORACLE:
                return new OracleTableLister(tableMaps);
            case POSTGRESQL:
                return new PostgresTableLister(tableMaps);
            default:
                throw new RuntimeException("Unsupported source type: " + st);
        }
    }

}

package com.quasera.sqlstudiogrid.ui.grid;

import javax.swing.table.AbstractTableModel;
import java.util.List;

/** Modelo de solo lectura para mostrar un ResultSet ya leído. */
public final class ResultTableModel extends AbstractTableModel {

    private final List<String> columns;
    private final List<String> types;
    private final List<Object[]> rows;

    public ResultTableModel(List<String> columns, List<String> types, List<Object[]> rows) {
        this.columns = columns;
        this.types = types;
        this.rows = rows;
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public String getColumnName(int column) {
        return columns.get(column);
    }

    public String getColumnType(int column) {
        return column < types.size() ? types.get(column) : "";
    }

    @Override
    public Object getValueAt(int row, int column) {
        return rows.get(row)[column];
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return false;
    }
}

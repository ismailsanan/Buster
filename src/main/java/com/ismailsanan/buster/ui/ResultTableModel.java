package com.ismailsanan.buster.ui;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * table model over typed result rows, the row object IS the data, so there is
 * no second list (e.g. of request/response pairs) to keep in sync by index
 *
 * EDT only, like every Swing model
 */
final class ResultTableModel<T> extends AbstractTableModel {

    private final List<Column<T>> columns;
    private final List<T> rows = new ArrayList<>();

    ResultTableModel(List<Column<T>> columns) {
        this.columns = List.copyOf(columns);
    }

    void add(T row) {
        rows.add(row);
        int i = rows.size() - 1;
        fireTableRowsInserted(i, i);
    }

    void clear() {
        rows.clear();
        fireTableDataChanged();
    }

    T row(int modelIndex) { return rows.get(modelIndex); }

    List<Column<T>> columns() { return columns; }

    @Override public int getRowCount()                 { return rows.size(); }
    @Override public int getColumnCount()              { return columns.size(); }
    @Override public String getColumnName(int c)       { return columns.get(c).name(); }
    @Override public Class<?> getColumnClass(int c)    { return columns.get(c).type(); }
    @Override public boolean isCellEditable(int r, int c) { return false; }

    @Override
    public Object getValueAt(int r, int c) {
        return columns.get(c).value().apply(rows.get(r));
    }
}

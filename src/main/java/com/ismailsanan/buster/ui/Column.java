package com.ismailsanan.buster.ui;

import javax.swing.table.TableCellRenderer;
import java.util.function.Function;

/**
 * one column of a results table
 *
 *   type      cell class, Integer/Long columns sort numerically
 *   width     preferred width in pixels
 *   value     how to read the cell from a row
 *   renderer  optional, null uses the table's plain renderer
 */
record Column<T>(String name, Class<?> type, int width, Function<T, Object> value, TableCellRenderer renderer) {

    static <T> Column<T> of(String name, Class<?> type, int width, Function<T, Object> value) {
        return new Column<>(name, type, width, value, null);
    }

    Column<T> withRenderer(TableCellRenderer r) {
        return new Column<>(name, type, width, value, r);
    }
}

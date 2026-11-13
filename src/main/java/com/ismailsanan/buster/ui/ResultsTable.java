package com.ismailsanan.buster.ui;

import javax.swing.*;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * live results table shared by every mode
 *
 *   rows       typed result objects in a ResultTableModel, columns from Column definitions
 *   sorting    per column, numeric for Integer/Long columns
 *   bar        counter, Copy, Export CSV, Clear, all acting on the visible (filtered, sorted) rows
 *   hooks      filter, row selection, right click menu and extra components, used by
 *              HttpResultsPanel for the HTTP-only features
 *
 * addHit and status may be called from any thread, everything else is EDT only
 */
public class ResultsTable<T> {

    /** builds the right click menu for the selected rows, null for no menu */
    @FunctionalInterface
    interface PopupFactory<T> {
        JPopupMenu create(List<T> selected, T clicked);
    }

    private final ResultTableModel<T> model;
    private final JTable table;
    private final TableRowSorter<ResultTableModel<T>> sorter;
    private final String exportName;
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JPanel top = new JPanel(new BorderLayout());
    private final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JLabel counter = new JLabel("0 found");
    private final JLabel statusBanner = new JLabel("Idle");

    private Consumer<T> onSelect = row -> {};
    private Runnable onClear = () -> {};
    private PopupFactory<T> popup = null;

    ResultsTable(List<Column<T>> columns, String exportName) {
        this.exportName = exportName;
        model = new ResultTableModel<>(columns);
        table = new JTable(model);
        table.setDefaultRenderer(Object.class, new PlainCellRenderer());
        table.setDefaultRenderer(String.class, new PlainCellRenderer());
        table.setFont(new Font("Monospaced", Font.PLAIN, 12));
        table.setRowHeight(20);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        for (int c = 0; c < columns.size(); c++) {
            Column<T> col = columns.get(c);
            table.getColumnModel().getColumn(c).setPreferredWidth(col.width());
            if (col.renderer() != null) table.getColumnModel().getColumn(c).setCellRenderer(col.renderer());
        }

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int view = table.getSelectionModel().getLeadSelectionIndex();
            if (view >= 0 && view < table.getRowCount()) onSelect.accept(model.row(table.convertRowIndexToModel(view)));
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }
        });

        statusBanner.setFont(new Font("Monospaced", Font.PLAIN, 12));
        statusBanner.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        statusBanner.putClientProperty("html.disable", Boolean.TRUE);
        top.add(statusBanner, BorderLayout.NORTH);

        JButton copy   = new JButton("Copy");
        JButton export = new JButton("Export CSV");
        JButton clear  = new JButton("Clear");
        copy.addActionListener(e -> copyVisible());
        export.addActionListener(e -> CsvExporter.export(panel, exportName, columnNames(), visibleRows()));
        clear.addActionListener(e -> clear());
        bar.add(counter); bar.add(copy); bar.add(export); bar.add(clear);

        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(bar, BorderLayout.SOUTH);
    }

    public JComponent getComponent() { return panel; }

    /** any thread */
    public void status(String text) {
        SwingUtilities.invokeLater(() -> statusBanner.setText(text));
    }

    /** any thread */
    public void addHit(T row) {
        SwingUtilities.invokeLater(() -> {
            model.add(row);
            updateCounter();
        });
    }

    public void clear() {
        SwingUtilities.invokeLater(() -> {
            model.clear();
            updateCounter();
            statusBanner.setText("Idle");
            onClear.run();
        });
    }

    // ---- hooks (EDT) -------------------------------------------------------

    void setFilter(Predicate<T> keep) {
        sorter.setRowFilter(keep == null ? null : new RowFilter<>() {
            @Override public boolean include(Entry<? extends ResultTableModel<T>, ? extends Integer> e) {
                return keep.test(model.row(e.getIdentifier()));
            }
        });
        updateCounter();
    }

    void onSelect(Consumer<T> handler)  { this.onSelect = handler; }
    void onClear(Runnable handler)      { this.onClear = handler; }
    void setPopup(PopupFactory<T> f)    { this.popup = f; }
    void addToTop(JComponent c)         { top.add(c, BorderLayout.SOUTH); }
    void addToBar(JComponent c)         { bar.add(c); }

    List<T> selected() {
        List<T> out = new ArrayList<>();
        for (int view : table.getSelectedRows()) out.add(model.row(table.convertRowIndexToModel(view)));
        return out;
    }

    // ---- package-private for tests ------------------------------------------

    JTable table() { return table; }

    int foundCount() { return model.getRowCount(); }

    String counterText() { return counter.getText(); }

    /** visible rows in view order, as cell values */
    List<Object[]> visibleRows() {
        List<Object[]> rows = new ArrayList<>(table.getRowCount());
        for (int v = 0; v < table.getRowCount(); v++) {
            int m = table.convertRowIndexToModel(v);
            Object[] row = new Object[model.getColumnCount()];
            for (int c = 0; c < row.length; c++) row[c] = model.getValueAt(m, c);
            rows.add(row);
        }
        return rows;
    }

    // ---- internals ---------------------------------------------------------

    private void updateCounter() {
        int found = model.getRowCount(), shown = table.getRowCount();
        counter.setText(found + " found" + (shown != found ? ", " + shown + " shown" : ""));
    }

    private String[] columnNames() {
        String[] names = new String[model.getColumnCount()];
        for (int c = 0; c < names.length; c++) names[c] = model.getColumnName(c);
        return names;
    }

    private void copyVisible() {
        StringBuilder sb = new StringBuilder();
        for (Object[] row : visibleRows()) {
            for (int c = 0; c < row.length; c++) {
                if (c > 0) sb.append('\t');
                sb.append(CsvExporter.formulaSafe(row[c] == null ? "" : String.valueOf(row[c])));
            }
            sb.append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
    }

    private void maybePopup(MouseEvent e) {
        if (popup == null || !e.isPopupTrigger()) return;
        int view = table.rowAtPoint(e.getPoint());
        if (view < 0) return;
        if (!table.isRowSelected(view)) table.setRowSelectionInterval(view, view);
        JPopupMenu menu = popup.create(selected(), model.row(table.convertRowIndexToModel(view)));
        if (menu != null) menu.show(e.getComponent(), e.getX(), e.getY());
    }
}

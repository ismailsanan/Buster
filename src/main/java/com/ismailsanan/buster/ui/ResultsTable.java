package com.ismailsanan.buster.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import com.ismailsanan.buster.core.EnumResult;
import com.ismailsanan.buster.core.ResultFilter;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static burp.api.montoya.ui.editor.EditorOptions.READ_ONLY;

/**
 * results table for HTTP modes, columns Result / Status / Size / Type / Redirect
 *
 *   filter bar     hide statuses / sizes, regex match on result or redirect
 *   right click    Send to Repeater / Intruder, add to site map, copy URLs,
 *                  hide this size / status
 *   site map       optionally add every hit to Burp's site map as it arrives
 *
 * request/response pairs arrive already moved to Burp temp files (HttpEngine),
 * so a long scan does not pin every response body in memory
 */
public class ResultsTable {

    private static final String[] COLUMNS = {"Result", "Status", "Size", "Type", "Redirect"};
    private static final int COL_NAME = 0, COL_STATUS = 1, COL_SIZE = 2, COL_REDIRECT = 4;

    private final MontoyaApi api;
    private final JPanel panel;
    private final DefaultTableModel model;
    private final JTable table;
    private final TableRowSorter<DefaultTableModel> sorter;
    private final JLabel counter = new JLabel("0 found");
    private final JLabel statusBanner = new JLabel("Idle");
    private int count = 0;

    private final JTextField hideStatus = new JTextField(7);
    private final JTextField hideSize   = new JTextField(7);
    private final JTextField match      = new JTextField(12);
    private final JCheckBox toSiteMap   = new JCheckBox("Add hits to site map", false);
    private volatile boolean addToSiteMap = false;

    private final List<HttpRequestResponse> exchanges = new ArrayList<>();
    private final HttpRequestEditor requestViewer;
    private final HttpResponseEditor responseViewer;

    public ResultsTable(MontoyaApi api) {
        this.api = api;
        model = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        table = new JTable(model);
        table.setDefaultRenderer(Object.class, new PlainCellRenderer());
        table.setFont(new Font("Monospaced", Font.PLAIN, 12));
        table.setRowHeight(20);
        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(360);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(260);
        table.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new StatusRenderer());

        requestViewer  = api.userInterface().createHttpRequestEditor(READ_ONLY);
        responseViewer = api.userInterface().createHttpResponseEditor(READ_ONLY);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int viewRow = table.getSelectionModel().getLeadSelectionIndex();
            if (viewRow < 0 || viewRow >= table.getRowCount()) return;
            showExchange(table.convertRowIndexToModel(viewRow));
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }
        });

        statusBanner.setFont(new Font("Monospaced", Font.PLAIN, 12));
        statusBanner.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JButton copy   = new JButton("Copy");
        JButton export = new JButton("Export CSV");
        JButton clear  = new JButton("Clear");
        copy.addActionListener(e -> copyAll());
        export.addActionListener(e -> exportCsv());
        clear.addActionListener(e -> clear());
        toSiteMap.setToolTipText("Every new hit is added to Target > Site map");
        toSiteMap.addItemListener(e -> addToSiteMap = toSiteMap.isSelected());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(counter); bar.add(copy); bar.add(export); bar.add(clear); bar.add(toSiteMap);

        JPanel top = new JPanel(new BorderLayout());
        top.add(statusBanner, BorderLayout.NORTH);
        top.add(filterBar(), BorderLayout.SOUTH);

        JPanel tableSide = new JPanel(new BorderLayout());
        tableSide.add(top, BorderLayout.NORTH);
        tableSide.add(new JScrollPane(table), BorderLayout.CENTER);
        tableSide.add(bar, BorderLayout.SOUTH);

        JTabbedPane viewers = new JTabbedPane();
        viewers.addTab("Request", requestViewer.uiComponent());
        viewers.addTab("Response", responseViewer.uiComponent());

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableSide, viewers);
        split.setResizeWeight(0.6);

        panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
    }

    public JComponent getComponent() { return panel; }

    public void status(String text) {
        SwingUtilities.invokeLater(() -> statusBanner.setText(text));
    }

    /** called from scan threads */
    public void addHit(EnumResult hit) {
        if (addToSiteMap && hit.exchange() != null) {
            try {
                api.siteMap().add(hit.exchange());
            } catch (RuntimeException e) {
                api.logging().logToError("[UI] could not add hit to site map", e);
            }
        }
        SwingUtilities.invokeLater(() -> {
            exchanges.add(hit.exchange());
            model.addRow(new Object[]{
                    hit.name(),
                    hit.status() == 0 ? "" : hit.status(),
                    hit.length() == 0 ? "" : hit.length(),
                    hit.extra(),
                    hit.redirect()
            });
            count++;
            updateCounter();
        });
    }

    public void clear() {
        SwingUtilities.invokeLater(() -> {
            model.setRowCount(0);
            exchanges.clear();
            count = 0;
            updateCounter();
            statusBanner.setText("Idle");
            requestViewer.setRequest(null);
            responseViewer.setResponse(null);
        });
    }

    // ---- filtering --------------------------------------------------------

    private JComponent filterBar() {
        hideStatus.setToolTipText("Hide statuses, e.g. 404, 500-599");
        hideSize.setToolTipText("Hide response sizes, e.g. 0, 1234");
        match.setToolTipText("Only show results whose URL or redirect matches this regex");
        DocumentListener onChange = new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        };
        hideStatus.getDocument().addDocumentListener(onChange);
        hideSize.getDocument().addDocumentListener(onChange);
        match.getDocument().addDocumentListener(onChange);

        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.add(new JLabel("Hide status")); p.add(hideStatus);
        p.add(new JLabel("Hide size"));   p.add(hideSize);
        p.add(new JLabel("Match"));       p.add(match);
        return p;
    }

    private void applyFilter() {
        for (JTextField f : List.of(hideStatus, hideSize, match))
            f.setBackground(UIManager.getColor("TextField.background"));
        ResultFilter filter;
        try {
            filter = ResultFilter.parse(hideStatus.getText(), hideSize.getText(), match.getText());
        } catch (ResultFilter.InvalidFilter e) {
            JTextField bad = switch (e.field) {
                case "status" -> hideStatus;
                case "size" -> hideSize;
                default -> match;
            };
            bad.setBackground(new Color(255, 200, 200));
            return;   // keep the previous filter while the user is mid-edit
        }
        sorter.setRowFilter(filter.isEmpty() ? null : new RowFilter<>() {
            @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> e) {
                return filter.shows(e.getStringValue(COL_NAME), e.getValue(COL_STATUS),
                        e.getValue(COL_SIZE), e.getStringValue(COL_REDIRECT));
            }
        });
        updateCounter();
    }

    private void updateCounter() {
        int shown = table.getRowCount();
        counter.setText(count + " found" + (shown != count ? ", " + shown + " shown" : ""));
    }

    private static void appendToken(JTextField field, String token) {
        String cur = field.getText().trim();
        field.setText(cur.isEmpty() ? token : cur + ", " + token);
    }

    // ---- right click actions ---------------------------------------------

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) return;
        if (!table.isRowSelected(row)) table.setRowSelectionInterval(row, row);

        int modelRow = table.convertRowIndexToModel(row);
        Object status = model.getValueAt(modelRow, COL_STATUS);
        Object size = model.getValueAt(modelRow, COL_SIZE);
        String sizeToken = size == null || size.toString().isEmpty() ? "0" : size.toString();
        List<HttpRequestResponse> selected = selectedExchanges();

        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Send to Repeater", !selected.isEmpty(), () ->
                selected.forEach(rr -> api.repeater().sendToRepeater(rr.request(), "Buster"))));
        menu.add(item("Send to Intruder", !selected.isEmpty(), () ->
                selected.forEach(rr -> api.intruder().sendToIntruder(rr.request()))));
        menu.add(item("Add to site map", !selected.isEmpty(), () -> selected.forEach(api.siteMap()::add)));
        menu.addSeparator();
        menu.add(item("Copy URL" + (table.getSelectedRowCount() > 1 ? "s" : ""), true, this::copySelectedUrls));
        menu.addSeparator();
        menu.add(item("Hide size " + sizeToken, true, () -> appendToken(hideSize, sizeToken)));
        if (status != null && !status.toString().isEmpty())
            menu.add(item("Hide status " + status, true, () -> appendToken(hideStatus, status.toString())));
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private JMenuItem item(String label, boolean enabled, Runnable action) {
        JMenuItem i = new JMenuItem(label);
        i.setEnabled(enabled);
        i.addActionListener(ev -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                api.logging().logToError("[UI] " + label + " failed", ex);
                status(label + " failed: " + ex.getMessage());
            }
        });
        return i;
    }

    private List<HttpRequestResponse> selectedExchanges() {
        List<HttpRequestResponse> out = new ArrayList<>();
        for (int viewRow : table.getSelectedRows()) {
            int m = table.convertRowIndexToModel(viewRow);
            if (m < exchanges.size() && exchanges.get(m) != null) out.add(exchanges.get(m));
        }
        return out;
    }

    private void copySelectedUrls() {
        StringBuilder sb = new StringBuilder();
        for (int viewRow : table.getSelectedRows())
            sb.append(model.getValueAt(table.convertRowIndexToModel(viewRow), COL_NAME)).append('\n');
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
    }

    private void showExchange(int modelRow) {
        if (modelRow < 0 || modelRow >= exchanges.size()) return;
        HttpRequestResponse rr = exchanges.get(modelRow);
        if (rr == null) return;
        if (rr.request() != null)  requestViewer.setRequest(rr.request());
        if (rr.response() != null) responseViewer.setResponse(rr.response());
    }

    private static class StatusRenderer extends PlainCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value,
                boolean sel, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
            setHorizontalAlignment(CENTER);
            if (value == null || value.toString().isEmpty()) return c;
            int status;
            try { status = Integer.parseInt(value.toString()); }
            catch (NumberFormatException e) { return c; }
            if (!sel) {
                if (status >= 200 && status < 300)       c.setForeground(new Color(0, 150, 0));
                else if (status >= 300 && status < 400)  c.setForeground(new Color(0, 130, 160));
                else if (status == 401 || status == 403) c.setForeground(new Color(190, 150, 0));
                else if (status >= 500)                  c.setForeground(new Color(190, 0, 0));
                else                                     c.setForeground(t.getForeground());
            }
            return c;
        }
    }

    // ---- copy / export (visible rows, in view order) ----------------------

    private List<Object[]> visibleRows() {
        List<Object[]> rows = new ArrayList<>(table.getRowCount());
        for (int v = 0; v < table.getRowCount(); v++) {
            int m = table.convertRowIndexToModel(v);
            Object[] row = new Object[COLUMNS.length];
            for (int c = 0; c < COLUMNS.length; c++) row[c] = model.getValueAt(m, c);
            rows.add(row);
        }
        return rows;
    }

    private void copyAll() {
        StringBuilder sb = new StringBuilder();
        for (Object[] row : visibleRows()) {
            for (int c = 0; c < row.length; c++) {
                if (c > 0) sb.append('\t');
                sb.append(CsvExporter.formulaSafe(String.valueOf(row[c])));
            }
            sb.append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
    }

    private void exportCsv() {
        CsvExporter.export(panel, "buster_results.csv", COLUMNS, visibleRows());
    }
}

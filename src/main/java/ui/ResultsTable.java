package ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import core.EnumResult;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

import static burp.api.montoya.ui.editor.EditorOptions.READ_ONLY;

/**
 * results table for HTTP modes, columns Result / Status / Size / Type / Redirect
 *
 * request/response pairs arrive already moved to Burp temp files (HttpEngine),
 * so a long scan does not pin every response body in memory
 */
public class ResultsTable {

    private static final String[] COLUMNS = {"Result", "Status", "Size", "Type", "Redirect"};

    private final JPanel panel;
    private final DefaultTableModel model;
    private final JLabel counter = new JLabel("0 found");
    private final JLabel statusBanner = new JLabel("Idle");
    private int count = 0;

    private final List<HttpRequestResponse> exchanges = new ArrayList<>();
    private final HttpRequestEditor requestViewer;
    private final HttpResponseEditor responseViewer;

    public ResultsTable(MontoyaApi api) {
        model = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        JTable table = new JTable(model);
        table.setDefaultRenderer(Object.class, new PlainCellRenderer());
        table.setFont(new Font("Monospaced", Font.PLAIN, 12));
        table.setRowHeight(20);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(360);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setCellRenderer(new StatusRenderer());

        requestViewer  = api.userInterface().createHttpRequestEditor(READ_ONLY);
        responseViewer = api.userInterface().createHttpResponseEditor(READ_ONLY);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) return;
            showExchange(table.convertRowIndexToModel(viewRow));
        });

        statusBanner.setFont(new Font("Monospaced", Font.PLAIN, 12));
        statusBanner.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JButton copy   = new JButton("Copy");
        JButton export = new JButton("Export CSV");
        JButton clear  = new JButton("Clear");
        copy.addActionListener(e -> copyAll());
        export.addActionListener(e -> exportCsv());
        clear.addActionListener(e -> clear());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(counter); bar.add(copy); bar.add(export); bar.add(clear);

        JPanel tableSide = new JPanel(new BorderLayout());
        tableSide.add(statusBanner, BorderLayout.NORTH);
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
        SwingUtilities.invokeLater(() -> {
            exchanges.add(hit.exchange());
            model.addRow(new Object[]{
                    hit.name(),
                    hit.status() == 0 ? "" : hit.status(),
                    hit.length() == 0 ? "" : hit.length(),
                    hit.extra(),
                    hit.redirect()
            });
            counter.setText(++count + " found");
        });
    }

    public void clear() {
        SwingUtilities.invokeLater(() -> {
            model.setRowCount(0);
            exchanges.clear();
            count = 0;
            counter.setText("0 found");
            statusBanner.setText("Idle");
            requestViewer.setRequest(null);
            responseViewer.setResponse(null);
        });
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
                else                                     c.setForeground(Color.DARK_GRAY);
            }
            return c;
        }
    }

    private String tsv() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < model.getRowCount(); r++) {
            for (int c = 0; c < model.getColumnCount(); c++) {
                if (c > 0) sb.append("\t");
                sb.append(CsvExporter.formulaSafe(String.valueOf(model.getValueAt(r, c))));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void copyAll() {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(tsv()), null);
    }

    private void exportCsv() {
        List<Object[]> rows = new ArrayList<>(model.getRowCount());
        for (int r = 0; r < model.getRowCount(); r++) {
            Object[] row = new Object[COLUMNS.length];
            for (int c = 0; c < COLUMNS.length; c++) row[c] = model.getValueAt(r, c);
            rows.add(row);
        }
        CsvExporter.export(panel, "buster_results.csv", COLUMNS, rows);
    }
}

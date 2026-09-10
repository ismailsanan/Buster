package ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import core.EnumResult;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

import static burp.api.montoya.ui.editor.EditorOptions.READ_ONLY;

/**
 * results table styled after gobuster's output
 *
 * columns: Result, Status, Size, Redirect
 * a live status banner across the top shows what the scan is doing
 * status colour coded 2xx green 3xx cyan 401/403 yellow 5xx red
 *
 * clicking a row shows the full request and response in Burp's native
 * editors below the table, so a finding can be inspected or sent onward
 * exactly like anywhere else in Burp
 */
public class ResultsTable {

    private final JPanel panel;
    private final DefaultTableModel model;
    private final JLabel counter = new JLabel("0 found");
    private final JLabel statusBanner = new JLabel("Idle");
    private int count = 0;

    // the response behind each visible row, index aligned with the model
    private final List<HttpRequestResponse> exchanges = new ArrayList<>();

    private final HttpRequestEditor requestViewer;
    private final HttpResponseEditor responseViewer;

    public ResultsTable(MontoyaApi api) {
        model = new DefaultTableModel(new Object[]{"Result", "Status", "Size", "Redirect"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        JTable table = new JTable(model);
        table.setFont(new Font("Monospaced", Font.PLAIN, 12));
        table.setRowHeight(20);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(360);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(3).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setCellRenderer(new StatusRenderer());

        // Burp's own editors, read only, populated on row click
        requestViewer  = api.userInterface().createHttpRequestEditor(READ_ONLY);
        responseViewer = api.userInterface().createHttpResponseEditor(READ_ONLY);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) return;
            int modelRow = table.convertRowIndexToModel(viewRow);
            showExchange(modelRow);
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
        bar.add(counter);
        bar.add(copy);
        bar.add(export);
        bar.add(clear);

        // table on top, request/response viewers below in a split
        JTabbedPane viewers = new JTabbedPane();
        viewers.addTab("Request", requestViewer.uiComponent());
        viewers.addTab("Response", responseViewer.uiComponent());

        JPanel tableSide = new JPanel(new BorderLayout());
        tableSide.add(statusBanner, BorderLayout.NORTH);
        tableSide.add(new JScrollPane(table), BorderLayout.CENTER);
        tableSide.add(bar, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableSide, viewers);
        split.setResizeWeight(0.6);

        panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
    }

    public JComponent getComponent() { return panel; }

    public void status(String text) {
        SwingUtilities.invokeLater(() -> statusBanner.setText(text));
    }

    public void addHit(EnumResult hit) {
        SwingUtilities.invokeLater(() -> {
            exchanges.add(hit.exchange());   // aligned with the new row
            model.addRow(new Object[]{
                    hit.name(),
                    hit.status() == 0 ? "" : hit.status(),
                    hit.length() == 0 ? "" : hit.length(),
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

    // populate the request/response editors for the clicked row
    private void showExchange(int modelRow) {
        if (modelRow < 0 || modelRow >= exchanges.size()) return;
        HttpRequestResponse rr = exchanges.get(modelRow);
        if (rr == null) return;   // dns rows have no exchange
        if (rr.request() != null)  requestViewer.setRequest(rr.request());
        if (rr.response() != null) responseViewer.setResponse(rr.response());
    }

    private static class StatusRenderer extends DefaultTableCellRenderer {
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
                if (status >= 200 && status < 300)      c.setForeground(new Color(0, 150, 0));
                else if (status >= 300 && status < 400) c.setForeground(new Color(0, 130, 160));
                else if (status == 401 || status == 403) c.setForeground(new Color(190, 150, 0));
                else if (status >= 500)                 c.setForeground(new Color(190, 0, 0));
                else                                    c.setForeground(Color.DARK_GRAY);
            }
            return c;
        }
    }

    private String tsv() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < model.getRowCount(); r++) {
            for (int col = 0; col < model.getColumnCount(); col++) {
                if (col > 0) sb.append("\t");
                sb.append(model.getValueAt(r, col));
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
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("buster_results.csv"));
        if (chooser.showSaveDialog(panel) != JFileChooser.APPROVE_OPTION) return;
        try (java.io.FileWriter w = new java.io.FileWriter(chooser.getSelectedFile())) {
            w.write("Result,Status,Size,Redirect\n");
            for (int r = 0; r < model.getRowCount(); r++) {
                w.write(csv(model.getValueAt(r, 0)) + "," + model.getValueAt(r, 1) + ","
                        + model.getValueAt(r, 2) + "," + csv(model.getValueAt(r, 3)) + "\n");
            }
        } catch (Exception ignored) {}
    }

    private String csv(Object v) {
        String s = String.valueOf(v);
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
package ui;

import core.EnumResult;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 *
 *  prints lines like:
 *   /admin                (Status: 301) [Size: 178] [--> /admin/]
 *   /config.php           (Status: 200) [Size: 1204]
 *
 * we keep that shape but in a sortable table, monospaced, status colour
 * output:
 *   2xx green, 3xx cyan, 401/403 yellow, 5xx red
 *
 * shared by all three mode tabs, rows stream in as hits arrive
 */
public class ResultsTable {

    private final JPanel panel;
    private final DefaultTableModel model;
    private final JLabel counter = new JLabel("0 found");
    private int count = 0;

    public ResultsTable() {
        model = new DefaultTableModel(new Object[]{"Result", "Status", "Size", "Notes"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        JTable table = new JTable(model);
        table.setFont(new Font("Monospaced", Font.PLAIN, 12));
        table.setRowHeight(20);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(430);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);

        table.getColumnModel().getColumn(1).setCellRenderer(new StatusRenderer());

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

        panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(bar, BorderLayout.SOUTH);
    }

    public JComponent getComponent() { return panel; }

    public void addHit(EnumResult hit) {
        SwingUtilities.invokeLater(() -> {
            model.addRow(new Object[]{
                    hit.name(),
                    hit.status() == 0 ? "" : hit.status(),
                    hit.length() == 0 ? "" : hit.length(),
                    hit.extra() + (hit.detail().isBlank() ? "" : "  " + hit.detail())
            });
            counter.setText(++count + " found");
        });
    }

    public void clear() {
        SwingUtilities.invokeLater(() -> {
            model.setRowCount(0);
            count = 0;
            counter.setText("0 found");
        });
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
            w.write("Result,Status,Size,Notes\n");
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

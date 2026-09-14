package ui;

import core.DnsResult;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/** DNS specific results table: Subdomain / Type / Value / Note */
public class DnsResultsTable {

    private final JPanel panel;
    private final DefaultTableModel model;
    private final JLabel counter = new JLabel("0 found");
    private final JLabel statusBanner = new JLabel("Idle");
    private int count = 0;

    public DnsResultsTable() {
        model = new DefaultTableModel(new Object[]{"Subdomain", "Type", "Value", "Note"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        JTable table = new JTable(model);
        table.setFont(new Font("Monospaced", Font.PLAIN, 12));
        table.setRowHeight(20);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(3).setPreferredWidth(200);
        table.getColumnModel().getColumn(1).setCellRenderer(new TypeRenderer());

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

        panel = new JPanel(new BorderLayout());
        panel.add(statusBanner, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(bar, BorderLayout.SOUTH);
    }

    public JComponent getComponent() { return panel; }

    public void status(String text) {
        SwingUtilities.invokeLater(() -> statusBanner.setText(text));
    }

    public void addHit(DnsResult r) {
        SwingUtilities.invokeLater(() -> {
            model.addRow(new Object[]{ r.host(), r.type(), r.value(), r.note() });
            counter.setText(++count + " found");
        });
    }

    public void clear() {
        SwingUtilities.invokeLater(() -> {
            model.setRowCount(0);
            count = 0;
            counter.setText("0 found");
            statusBanner.setText("Idle");
        });
    }

    private static class TypeRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value,
                boolean sel, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
            setHorizontalAlignment(CENTER);
            if (!sel && value != null) {
                if ("CNAME".equals(value.toString())) c.setForeground(new Color(0, 130, 160));
                else                                  c.setForeground(Color.DARK_GRAY);
            }
            return c;
        }
    }

    private String tsv() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < model.getRowCount(); r++) {
            for (int c = 0; c < model.getColumnCount(); c++) {
                if (c > 0) sb.append("\t");
                sb.append(model.getValueAt(r, c));
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
        chooser.setSelectedFile(new java.io.File("buster_dns.csv"));
        if (chooser.showSaveDialog(panel) != JFileChooser.APPROVE_OPTION) return;
        try (java.io.FileWriter w = new java.io.FileWriter(chooser.getSelectedFile())) {
            w.write("Subdomain,Type,Value,Note\n");
            for (int r = 0; r < model.getRowCount(); r++) {
                w.write(csv(model.getValueAt(r,0)) + "," + model.getValueAt(r,1) + ","
                        + csv(model.getValueAt(r,2)) + "," + csv(model.getValueAt(r,3)) + "\n");
            }
        } catch (Exception ignored) {}
    }

    private String csv(Object v) {
        String s = String.valueOf(v);
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}

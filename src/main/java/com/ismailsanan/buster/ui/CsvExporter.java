package com.ismailsanan.buster.ui;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * CSV export shared by both result tables
 *
 * cell values come from the target (paths, Location headers, DNS answers), so
 * they are escaped against spreadsheet formula injection, and the file is
 * written off the EDT with any failure reported to the user
 */
final class CsvExporter {

    private CsvExporter() {}

    static void export(Component parent, String defaultName, String[] header, List<Object[]> rows) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(defaultName));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws IOException {
                try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                    w.write(line(header));
                    for (Object[] row : rows) w.write(line(row));
                }
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    JOptionPane.showMessageDialog(parent, "Could not write " + file.getName() + ": "
                            + cause.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static String line(Object[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(cells[i]));
        }
        return sb.append("\r\n").toString();
    }

    /** prefix a quote on values a spreadsheet would evaluate as a formula */
    static String formulaSafe(String s) {
        return !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
    }

    /** RFC 4180 quoting plus a leading quote on values a spreadsheet would treat as a formula */
    static String escape(Object v) {
        String s = formulaSafe(v == null ? "" : String.valueOf(v));
        boolean quote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        return quote ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}

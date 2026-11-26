package com.ismailsanan.buster.ui;

import javax.swing.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * a feroxbuster-style running log of one mode: the scan settings, what each
 * calibration decided to hide, per-directory totals and how the scan ended
 */
final class ScanLog {

    private static final int MAX_CHARS = 400_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JTextArea area = new JTextArea();
    private final JPanel panel = new JPanel(new BorderLayout());

    ScanLog() {
        area.setEditable(false);
        area.setFont(new Font("Monospaced", Font.PLAIN, 12));
        area.setLineWrap(false);
        JButton clear = new JButton("Clear log");
        clear.addActionListener(e -> area.setText(""));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.add(clear);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        panel.add(bar, BorderLayout.SOUTH);
    }

    JComponent getComponent() { return panel; }

    /** any thread */
    void log(String line) {
        String stamped = "[" + LocalTime.now().format(TIME) + "] " + line + "\n";
        SwingUtilities.invokeLater(() -> {
            area.append(stamped);
            int len = area.getDocument().getLength();
            if (len > MAX_CHARS) area.replaceRange("", 0, len - MAX_CHARS / 2);
            area.setCaretPosition(area.getDocument().getLength());
        });
    }

    /** tests */
    String text() { return area.getText(); }
}

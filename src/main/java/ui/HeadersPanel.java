package ui;

import core.HttpEngine;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * headers input attached to every request
 */
public class HeadersPanel {

    private final JPanel panel;
    private final JTextArea area = new JTextArea(4, 30);

    public HeadersPanel() {
        area.setFont(new Font("Monospaced", Font.PLAIN, 12));
        area.setToolTipText("One header per line, e.g.  Cookie: session=abc123");

        panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        panel.add(new JLabel("One header per line, applied to every request:"), BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
    }

    public JComponent getComponent() { return panel; }

    public List<HttpEngine.HeaderKV> resolve() {
        List<HttpEngine.HeaderKV> out = new ArrayList<>();
        for (String line : area.getText().split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int colon = t.indexOf(':');
            if (colon > 0) {
                String name = t.substring(0, colon).trim();
                String value = t.substring(colon + 1).trim();
                if (!name.isEmpty() && !value.isEmpty()) out.add(new HttpEngine.HeaderKV(name, value));
            } else if (t.contains("=")) {
                out.add(new HttpEngine.HeaderKV("Cookie", t));
            }
        }
        return out;
    }
}

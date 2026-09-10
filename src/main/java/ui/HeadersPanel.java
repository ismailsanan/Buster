package ui;

import core.Headers;
import core.HttpEngine;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * reusable headers / cookie input, shared by the HTTP modes
 *
 * paste full header lines or a bare cookie string, these are attached to
 * every request the scan sends, which is what makes authenticated content
 * discovery work
 *
 * collapsed by default so it doesnt clutter the panel, expand when you need
 * to add a session
 */
public class HeadersPanel {

    private final JPanel panel;
    private final JTextArea area = new JTextArea(4, 30);

    public HeadersPanel() {
        area.setFont(new Font("Monospaced", Font.PLAIN, 12));
        area.setToolTipText("One header per line, e.g.  Cookie: session=abc123"
                + "   or   Authorization: Bearer <token>");

        JPanel inner = new JPanel(new BorderLayout(0, 4));
        inner.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        inner.add(new JLabel("One header per line, applied to every request:"),
                BorderLayout.NORTH);
        inner.add(new JScrollPane(area), BorderLayout.CENTER);

        panel = inner;
    }

    public JComponent getComponent() { return panel; }

    public List<HttpEngine.HeaderKV> resolve() {
        return Headers.parse(area.getText());
    }
}
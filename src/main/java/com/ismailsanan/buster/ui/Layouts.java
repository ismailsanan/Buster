package com.ismailsanan.buster.ui;

import javax.swing.*;
import java.awt.*;

/** shared layout helpers so all mode panels look identical: results left, config right */
final class Layouts {

    private Layouts() {}

    static JPanel modePanel(JComponent resultsComponent, JComponent config) {
        JPanel right = new JPanel(new BorderLayout());
        right.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        right.add(config, BorderLayout.CENTER);
        right.setMinimumSize(new Dimension(320, 0));
        right.setPreferredSize(new Dimension(380, 0));

        JPanel left = new JPanel(new BorderLayout());
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 0));
        left.add(resultsComponent, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(1.0);
        split.setBorder(null);

        JPanel p = new JPanel(new BorderLayout());
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    static JComponent config(JComponent form, JComponent inputs) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(form, BorderLayout.NORTH);
        p.add(inputs, BorderLayout.CENTER);
        return p;
    }

    static final class Form {
        private final JPanel p = new JPanel(new GridBagLayout());
        private int y = 0;

        Form field(String label, JComponent field) {
            gbc(new JLabel(label));
            gbc(field);
            return this;
        }
        Form row(String l1, JComponent c1, String l2, JComponent c2) {
            JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            line.add(new JLabel(l1)); line.add(c1);
            line.add(Box.createHorizontalStrut(12));
            line.add(new JLabel(l2)); line.add(c2);
            gbc(line);
            return this;
        }
        Form add(JComponent c) {
            JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            line.add(c);
            gbc(line);
            return this;
        }
        /** bold heading that starts a group of fields */
        Form section(String title) {
            JLabel l = new JLabel(title);
            l.setFont(l.getFont().deriveFont(Font.BOLD));
            l.setBorder(BorderFactory.createEmptyBorder(y == 0 ? 0 : 8, 0, 0, 0));
            gbc(l);
            return this;
        }
        /** a plain label added as its own line, for text the panel updates live (an example of what is sent) */
        Form example(JLabel label) {
            label.putClientProperty("html.disable", Boolean.TRUE);
            label.setForeground(new Color(0, 110, 150));
            gbc(label);
            return this;
        }
        Form buttons(JComponent... buttons) {
            JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            for (JComponent b : buttons) line.add(b);
            gbc(line);
            return this;
        }
        JComponent build() { return p; }

        private void gbc(JComponent c) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = y++; g.gridwidth = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            g.weightx = 1.0;
            g.insets = new Insets(2, 0, 2, 0);
            g.anchor = GridBagConstraints.NORTHWEST;
            p.add(c, g);
        }
    }
}

package ui;

import javax.swing.*;
import java.awt.*;

/**
 * shared layout helpers so all three mode panels look identical and tight
 *
 * modePanel: results/viewer on the LEFT (main area), config on the RIGHT
 * Form:      a compact label-over-field stack that does not stretch, which
 *            is what kills the big empty gaps a raw BoxLayout leaves
 * config:    stacks the form on top of the tabbed wordlist/headers below
 */
final class Layouts {

    private Layouts() {}

    // left = results (grows), right = fixed width config column
    static JPanel modePanel(ResultsTable results, JComponent config) {
        JPanel right = new JPanel(new BorderLayout());
        right.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        right.add(config, BorderLayout.CENTER);
        right.setMinimumSize(new Dimension(320, 0));
        right.setPreferredSize(new Dimension(380, 0));

        JPanel left = new JPanel(new BorderLayout());
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 0));
        left.add(results.getComponent(), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(1.0);   // extra space goes to results
        split.setBorder(null);

        JPanel p = new JPanel(new BorderLayout());
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    // form on top (its natural height), tabbed inputs fill the rest
    static JComponent config(JComponent form, JComponent inputs) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(form, BorderLayout.NORTH);
        p.add(inputs, BorderLayout.CENTER);
        return p;
    }

    // a compact form builder, GridBag so fields stretch horizontally but the
    // stack stays top aligned with no vertical padding blowout
    static final class Form {
        private final JPanel p = new JPanel(new GridBagLayout());
        private int y = 0;

        // full width field under a label
        Form field(String label, JComponent field) {
            gbc(0, y++, 2, new JLabel(label), 0);
            gbc(0, y++, 2, field, 0);
            return this;
        }

        // two label+control pairs on one line
        Form row(String l1, JComponent c1, String l2, JComponent c2) {
            JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            line.add(new JLabel(l1)); line.add(c1);
            line.add(Box.createHorizontalStrut(12));
            line.add(new JLabel(l2)); line.add(c2);
            gbc(0, y++, 2, line, 0);
            return this;
        }

        Form add(JComponent c) {
            JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            line.add(c);
            gbc(0, y++, 2, line, 0);
            return this;
        }

        Form buttons(JComponent... buttons) {
            JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            for (JComponent b : buttons) line.add(b);
            gbc(0, y++, 2, line, 0);
            return this;
        }

        JComponent build() { return p; }

        private void gbc(int x, int y, int w, JComponent c, int weighty) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = x; g.gridy = y; g.gridwidth = w;
            g.fill = GridBagConstraints.HORIZONTAL;
            g.weightx = 1.0; g.weighty = weighty;
            g.insets = new Insets(2, 0, 2, 0);
            g.anchor = GridBagConstraints.NORTHWEST;
            p.add(c, g);
        }
    }
}
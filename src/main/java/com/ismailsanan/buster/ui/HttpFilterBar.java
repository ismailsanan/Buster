package com.ismailsanan.buster.ui;

import com.ismailsanan.buster.core.EnumResult;
import com.ismailsanan.buster.core.ResultFilter;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;
import java.util.function.Predicate;

/**
 * hide statuses / hide sizes / regex match for HTTP results
 *
 * an invalid field turns red and the previous filter stays active, so the
 * view does not flicker while the user is mid-edit
 */
final class HttpFilterBar {

    private static final Color INVALID = new Color(255, 200, 200);

    private final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JTextField hideStatus = new JTextField(7);
    private final JTextField hideSize   = new JTextField(7);
    private final JTextField match      = new JTextField(12);
    private final java.util.function.Consumer<Predicate<EnumResult>> onChange;

    /** onChange gets null when nothing is filtered */
    HttpFilterBar(java.util.function.Consumer<Predicate<EnumResult>> onChange) {
        this.onChange = onChange;
        hideStatus.setToolTipText("Hide statuses, e.g. 404, 500-599");
        hideSize.setToolTipText("Hide response sizes, e.g. 0, 1234");
        match.setToolTipText("Only show results whose URL or redirect matches this regex");
        DocumentListener listener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { apply(); }
            public void removeUpdate(DocumentEvent e)  { apply(); }
            public void changedUpdate(DocumentEvent e) { apply(); }
        };
        for (JTextField f : fields()) f.getDocument().addDocumentListener(listener);

        panel.add(new JLabel("Hide status")); panel.add(hideStatus);
        panel.add(new JLabel("Hide size"));   panel.add(hideSize);
        panel.add(new JLabel("Match"));       panel.add(match);
    }

    JComponent getComponent() { return panel; }

    void hideSize(long size)    { append(hideSize, String.valueOf(size)); }
    void hideStatus(int status) { append(hideStatus, String.valueOf(status)); }

    private void apply() {
        for (JTextField f : fields()) f.setBackground(UIManager.getColor("TextField.background"));
        ResultFilter filter;
        try {
            filter = ResultFilter.parse(hideStatus.getText(), hideSize.getText(), match.getText());
        } catch (ResultFilter.InvalidFilter e) {
            JTextField bad = switch (e.field) {
                case "status" -> hideStatus;
                case "size" -> hideSize;
                default -> match;
            };
            bad.setBackground(INVALID);
            return;
        }
        onChange.accept(filter.isEmpty() ? null
                : r -> filter.shows(r.name(), r.status(), r.length(), r.redirect()));
    }

    private List<JTextField> fields() { return List.of(hideStatus, hideSize, match); }

    private static void append(JTextField field, String token) {
        String cur = field.getText().trim();
        field.setText(cur.isEmpty() ? token : cur + ", " + token);
    }
}

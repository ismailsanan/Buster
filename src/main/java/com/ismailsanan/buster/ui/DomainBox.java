package com.ismailsanan.buster.ui;

import com.ismailsanan.buster.core.UrlUtil;

import javax.swing.*;
import java.util.List;

/**
 * editable domain field with a drop-down of every level of a host, so
 * "a.b.target.com" offers a.b.target.com, b.target.com and target.com; the
 * registered domain (the last level, target.com) is selected by default and
 * any other value can still be typed
 */
final class DomainBox {

    private final JComboBox<String> box = new JComboBox<>();

    DomainBox(String initial) {
        box.setEditable(true);
        box.setPrototypeDisplayValue("sub.subdomain.example.com");
        set(List.of(initial), initial);
    }

    JComponent component() { return box; }

    void setToolTipText(String tip) { box.setToolTipText(tip); }

    /** EDT: run whenever the value changes, typed or picked from the list */
    void onChange(Runnable r) {
        box.addActionListener(e -> r.run());
        java.awt.Component editor = box.getEditor().getEditorComponent();
        if (editor instanceof javax.swing.text.JTextComponent t) {
            t.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e)  { r.run(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e)  { r.run(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            });
        }
    }

    /** EDT: offer every level of host, select its registered domain */
    void fillFrom(String host) {
        List<String> levels = UrlUtil.domainLevels(host);
        if (levels.isEmpty()) return;
        set(levels, UrlUtil.registrableDomain(host));
    }

    /** EDT: the typed or selected value, trimmed */
    String value() {
        Object v = box.isEditable() ? box.getEditor().getItem() : box.getSelectedItem();
        return v == null ? "" : v.toString().trim();
    }

    /** EDT: replace the choices with just this value */
    void setText(String value) { set(List.of(value), value); }

    private void set(List<String> choices, String selected) {
        box.removeAllItems();
        for (String c : choices) box.addItem(c);
        box.setSelectedItem(selected);
        box.getEditor().setItem(selected);
    }
}

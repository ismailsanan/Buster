package com.ismailsanan.buster.ui;

import javax.swing.*;
import java.awt.*;

/** centred HTTP status, colour coded: 2xx green, 3xx cyan, 401/403 yellow, 5xx red */
final class StatusRenderer extends PlainCellRenderer {

    StatusRenderer() { setHorizontalAlignment(CENTER); }

    @Override
    public Component getTableCellRendererComponent(JTable t, Object value,
            boolean sel, boolean focus, int row, int col) {
        Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
        if (sel || !(value instanceof Number n)) return c;
        int status = n.intValue();
        if (status >= 200 && status < 300)       c.setForeground(new Color(0, 150, 0));
        else if (status >= 300 && status < 400)  c.setForeground(new Color(0, 130, 160));
        else if (status == 401 || status == 403) c.setForeground(new Color(190, 150, 0));
        else if (status >= 500)                  c.setForeground(new Color(190, 0, 0));
        else                                     c.setForeground(t.getForeground());
        return c;
    }
}

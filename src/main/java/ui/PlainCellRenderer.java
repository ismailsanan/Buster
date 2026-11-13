package ui;

import javax.swing.table.DefaultTableCellRenderer;

/**
 * table cell renderer with Swing's HTML rendering switched off, so a cell
 * whose text starts with "<html>" (e.g. a hostile wordlist entry) is shown
 * as plain text instead of being rendered or fetching remote images
 */
class PlainCellRenderer extends DefaultTableCellRenderer {
    PlainCellRenderer() {
        putClientProperty("html.disable", Boolean.TRUE);
    }
}

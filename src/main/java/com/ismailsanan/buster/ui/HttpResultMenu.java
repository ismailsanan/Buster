package com.ismailsanan.buster.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import com.ismailsanan.buster.core.EnumResult;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** right click menu for HTTP results: Burp tool actions, copy, and quick filters */
final class HttpResultMenu implements ResultsTable.PopupFactory<EnumResult> {

    private final MontoyaApi api;
    private final HttpFilterBar filters;
    private final Consumer<String> status;

    HttpResultMenu(MontoyaApi api, HttpFilterBar filters, Consumer<String> status) {
        this.api = api;
        this.filters = filters;
        this.status = status;
    }

    @Override
    public JPopupMenu create(List<EnumResult> selected, EnumResult clicked) {
        List<HttpRequestResponse> exchanges = selected.stream()
                .map(EnumResult::exchange).filter(Objects::nonNull).toList();
        boolean any = !exchanges.isEmpty();

        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Send to Repeater", any, () ->
                exchanges.forEach(rr -> api.repeater().sendToRepeater(rr.request(), "Buster"))));
        menu.add(item("Send to Intruder", any, () ->
                exchanges.forEach(rr -> api.intruder().sendToIntruder(rr.request()))));
        menu.add(item("Add to site map", any, () -> exchanges.forEach(api.siteMap()::add)));
        menu.addSeparator();
        menu.add(item(selected.size() > 1 ? "Copy URLs" : "Copy URL", true, () -> copyUrls(selected)));
        menu.addSeparator();
        menu.add(item("Hide size " + clicked.length(), true, () -> filters.hideSize(clicked.length())));
        if (clicked.status() > 0)
            menu.add(item("Hide status " + clicked.status(), true, () -> filters.hideStatus(clicked.status())));
        return menu;
    }

    private JMenuItem item(String label, boolean enabled, Runnable action) {
        JMenuItem i = new JMenuItem(label);
        i.setEnabled(enabled);
        i.addActionListener(e -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                api.logging().logToError("[UI] " + label + " failed", ex);
                status.accept(label + " failed: " + ex.getMessage());
            }
        });
        return i;
    }

    private static void copyUrls(List<EnumResult> rows) {
        StringBuilder sb = new StringBuilder();
        for (EnumResult r : rows) sb.append(r.name()).append('\n');
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
    }
}

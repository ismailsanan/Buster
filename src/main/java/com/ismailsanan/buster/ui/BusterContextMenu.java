package com.ismailsanan.buster.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;

import com.ismailsanan.buster.core.UrlUtil;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * "Send to Buster" entries on Burp's right click menu (Proxy, Target,
 * Repeater, Logger ...): pre-fills a mode from the selected request and
 * switches to it, nothing starts until the user presses Start
 */
public class BusterContextMenu implements ContextMenuItemsProvider {

    private final MontoyaApi api;
    private final JTabbedPane tabs;
    private final DirPanel dir;
    private final VhostPanel vhost;
    private final DnsPanel dns;

    public BusterContextMenu(MontoyaApi api, JTabbedPane tabs, DirPanel dir, VhostPanel vhost, DnsPanel dns) {
        this.api = api;
        this.tabs = tabs;
        this.dir = dir;
        this.vhost = vhost;
        this.dns = dns;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        Optional<String> url = selectedUrl(event);
        if (url.isEmpty()) return List.of();
        String u = url.get();
        return List.of(
                item("Send to Buster: Dir scan from this directory", () -> {
                    dir.prefill(UrlUtil.directoryOf(u));
                    show(dir.getComponent());
                }),
                item("Send to Buster: Vhost scan this host", () -> {
                    vhost.prefill(UrlUtil.originOf(u), UrlUtil.parentDomain(UrlUtil.hostOf(u)));
                    show(vhost.getComponent());
                }),
                item("Send to Buster: DNS scan this domain", () -> {
                    dns.prefill(UrlUtil.parentDomain(UrlUtil.hostOf(u)));
                    show(dns.getComponent());
                }));
    }

    private Optional<String> selectedUrl(ContextMenuEvent event) {
        HttpRequestResponse rr = event.messageEditorRequestResponse()
                .map(MessageEditorHttpRequestResponse::requestResponse)
                .orElseGet(() -> event.selectedRequestResponses().isEmpty()
                        ? null : event.selectedRequestResponses().get(0));
        if (rr == null || rr.request() == null) return Optional.empty();
        try {
            String u = rr.request().url();
            UrlUtil.normalizeTarget(u, false);   // only offer the menu for URLs we can scan
            return Optional.of(u);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private JMenuItem item(String label, Runnable action) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(e -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                api.logging().logToError("[UI] " + label + " failed", ex);
            }
        });
        return i;
    }

    /** select the mode tab; the Buster suite tab itself is left to the user to open */
    private void show(Component modePanel) {
        tabs.setSelectedComponent(modePanel);
    }
}

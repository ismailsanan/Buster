package com.ismailsanan.buster.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import com.ismailsanan.buster.core.EnumResult;

import javax.swing.*;
import java.util.List;

import static burp.api.montoya.ui.editor.EditorOptions.READ_ONLY;

/**
 * results for the HTTP modes (Dir, Vhost): the shared ResultsTable plus a
 * filter bar, right click menu, request/response viewers and the optional
 * "Add hits to site map"
 *
 * exchanges arrive already moved to Burp temp files (HttpEngine), so a long
 * scan does not pin every response body in memory
 */
public class HttpResultsPanel {

    static List<Column<EnumResult>> columns() {
        return List.of(
                Column.<EnumResult>of("Result", String.class, 360, EnumResult::name),
                Column.<EnumResult>of("Status", Integer.class, 60, EnumResult::status)
                        .withRenderer(new StatusRenderer()),
                Column.<EnumResult>of("Size", Long.class, 70, EnumResult::length),
                Column.<EnumResult>of("Type", String.class, 60, EnumResult::extra),
                Column.<EnumResult>of("Redirect", String.class, 260, EnumResult::redirect));
    }

    private final MontoyaApi api;
    private final ResultsTable<EnumResult> table = new ResultsTable<>(columns(), "buster_results.csv");
    private final HttpRequestEditor requestViewer;
    private final HttpResponseEditor responseViewer;
    private final JCheckBox toSiteMap = new JCheckBox("Add hits to site map", false);
    private volatile boolean addToSiteMap = false;
    private final JComponent component;

    public HttpResultsPanel(MontoyaApi api) {
        this.api = api;
        requestViewer  = api.userInterface().createHttpRequestEditor(READ_ONLY);
        responseViewer = api.userInterface().createHttpResponseEditor(READ_ONLY);

        HttpFilterBar filters = new HttpFilterBar(table::setFilter);
        table.addToTop(filters.getComponent());
        table.setPopup(new HttpResultMenu(api, filters, table::status));
        table.onSelect(this::show);
        table.onClear(() -> {
            requestViewer.setRequest(null);
            responseViewer.setResponse(null);
        });

        toSiteMap.setToolTipText("Every new hit is added to Target > Site map");
        toSiteMap.addItemListener(e -> addToSiteMap = toSiteMap.isSelected());
        table.addToBar(toSiteMap);

        JTabbedPane viewers = new JTabbedPane();
        viewers.addTab("Request", requestViewer.uiComponent());
        viewers.addTab("Response", responseViewer.uiComponent());
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, table.getComponent(), viewers);
        split.setResizeWeight(0.6);
        component = split;
    }

    public JComponent getComponent() { return component; }

    /** any thread */
    public void status(String text) { table.status(text); }

    /** any thread */
    public void addHit(EnumResult hit) {
        if (addToSiteMap && hit.exchange() != null) {
            try {
                api.siteMap().add(hit.exchange());
            } catch (RuntimeException e) {
                api.logging().logToError("[UI] could not add hit to site map", e);
            }
        }
        table.addHit(hit);
    }

    private void show(EnumResult r) {
        HttpRequestResponse rr = r.exchange();
        if (rr == null) return;
        if (rr.request() != null)  requestViewer.setRequest(rr.request());
        if (rr.response() != null) responseViewer.setResponse(rr.response());
    }
}

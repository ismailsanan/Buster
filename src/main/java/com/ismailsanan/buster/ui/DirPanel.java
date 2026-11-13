package com.ismailsanan.buster.ui;

import burp.api.montoya.MontoyaApi;

import com.ismailsanan.buster.core.UrlUtil;
import com.ismailsanan.buster.scan.DirEnumerator;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

public class DirPanel {

    private final MontoyaApi api;
    private final JPanel panel;

    private final JTextField target     = new JTextField("https://example.com");
    private final JTextField extensions = new JTextField("");
    private final JSpinner depth        = new JSpinner(new SpinnerNumberModel(1, 0, 6, 1));
    private final JSpinner threads      = new JSpinner(new SpinnerNumberModel(30, 1, 50, 1));
    private final JSpinner rps          = new JSpinner(new SpinnerNumberModel(0, 0, 1000, 5));
    private final JCheckBox extOnRec    = new JCheckBox("Extensions on recursion", false);
    private final JCheckBox collapse    = new JCheckBox("Collapse duplicates", true);
    private final JCheckBox inScope     = new JCheckBox("Only in-scope targets", true);

    private final WordlistPicker wordlist =
            new WordlistPicker("Paste paths one per line, or Load File (e.g. SecLists)");
    private final HeadersPanel headers = new HeadersPanel();
    private final ResultsTable results;

    private final JButton startBtn = new JButton("Start");
    private final JButton stopBtn  = new JButton("Stop");

    private final ScanRunner runner;

    public DirPanel(MontoyaApi api) {
        this.api = api;
        this.results = new ResultsTable(api);
        this.runner = new ScanRunner(t -> {
            results.status("error: " + t.getMessage());
            api.logging().logToError("[DIR] scan failed", t);
        });
        this.panel = build();
    }

    public JComponent getComponent() { return panel; }
    public void cancel() { runner.cancel(); }
    public void cancelAndWait(long millis) { runner.cancelAndWait(millis); }

    /** EDT only: fill the target from another Burp tool */
    public void prefill(String target) { this.target.setText(target); }

    private JPanel build() {
        Layouts.Form form = new Layouts.Form();
        form.field("Target", target);
        form.field("Extensions (blank = dirs only)", extensions);
        form.row("Depth", depth, "Threads", threads);
        form.row("Req/s (0 = no limit)", rps, " ", new JLabel());
        form.add(extOnRec);
        form.add(collapse);
        form.add(inScope);
        form.buttons(startBtn, stopBtn);

        startBtn.addActionListener(e -> start());
        stopBtn.addActionListener(e -> { cancel(); results.status("stopping..."); });

        JTabbedPane inputs = new JTabbedPane();
        inputs.addTab("Wordlist", wordlist.getComponent());
        inputs.addTab("Headers / Auth", headers.getComponent());

        return Layouts.modePanel(results.getComponent(), Layouts.config(form.build(), inputs));
    }

    /** runs on the EDT: every Swing value is read here, the worker only gets an immutable config */
    private void start() {
        if (runner.isRunning()) return;
        DirEnumerator.Config cfg;
        try {
            cfg = new DirEnumerator.Config(
                    UrlUtil.normalizeTarget(target.getText(), true),
                    wordlist.resolve(),
                    parseExtensions(extensions.getText()),
                    (int) depth.getValue(),
                    (int) threads.getValue(),
                    extOnRec.isSelected(),
                    collapse.isSelected(),
                    headers.resolve(),
                    (int) rps.getValue(),
                    inScope.isSelected());
        } catch (IllegalArgumentException ex) {
            results.status(ex.getMessage());
            return;
        }

        DirEnumerator scan = new DirEnumerator(api);
        running(true);
        runner.start("Buster-dir-scan", scan::cancel, () -> scan.scan(cfg, results::addHit, results::status), () -> running(false));
    }

    static List<String> parseExtensions(String raw) {
        List<String> exts = new ArrayList<>();
        exts.add("");
        for (String e : raw.split(",")) {
            String t = e.trim();
            if (!t.isEmpty()) exts.add(t.startsWith(".") ? t : "." + t);
        }
        return exts;
    }

    private void running(boolean on) {
        SwingUtilities.invokeLater(() -> {
            startBtn.setText(on ? "Running..." : "Start");
            startBtn.setEnabled(!on);
        });
    }
}

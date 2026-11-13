package com.ismailsanan.buster.ui;

import burp.api.montoya.MontoyaApi;

import com.ismailsanan.buster.core.UrlUtil;
import com.ismailsanan.buster.scan.VhostEnumerator;

import javax.swing.*;

public class VhostPanel {

    private final MontoyaApi api;
    private final JPanel panel;

    private final JTextField target = new JTextField("https://10.0.0.5");
    private final JTextField domain = new JTextField("target.com");
    private final JSpinner threads  = new JSpinner(new SpinnerNumberModel(10, 1, 50, 1));
    private final JSpinner rps      = new JSpinner(new SpinnerNumberModel(0, 0, 1000, 5));
    private final JCheckBox inScope = new JCheckBox("Only in-scope targets", true);

    private final WordlistPicker wordlist =
            new WordlistPicker("Paste subdomain words one per line, or Load File");
    private final HeadersPanel headers = new HeadersPanel();
    private final HttpResultsPanel results;

    private final JButton startBtn = new JButton("Start");
    private final JButton stopBtn  = new JButton("Stop");

    private final ScanRunner runner;

    public VhostPanel(MontoyaApi api) {
        this.api = api;
        this.results = new HttpResultsPanel(api);
        this.runner = new ScanRunner(t -> {
            results.status("error: " + t.getMessage());
            api.logging().logToError("[VHOST] scan failed", t);
        });
        this.panel = build();
    }

    public JComponent getComponent() { return panel; }
    public void cancel() { runner.cancel(); }
    public void cancelAndWait(long millis) { runner.cancelAndWait(millis); }

    /** EDT only: fill target and base domain from another Burp tool */
    public void prefill(String target, String domain) {
        this.target.setText(target);
        this.domain.setText(domain);
    }

    private JPanel build() {
        Layouts.Form form = new Layouts.Form();
        form.field("Target (IP/host)", target);
        form.field("Base domain", domain);
        form.row("Threads", threads, "Req/s (0 = no limit)", rps);
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
        VhostEnumerator.Config cfg;
        try {
            cfg = new VhostEnumerator.Config(
                    UrlUtil.normalizeTarget(target.getText(), false),
                    domain.getText().trim(),
                    wordlist.resolve(),
                    (int) threads.getValue(),
                    headers.resolve(),
                    (int) rps.getValue(),
                    inScope.isSelected());
        } catch (IllegalArgumentException ex) {
            results.status(ex.getMessage());
            return;
        }

        VhostEnumerator scan = new VhostEnumerator(api);
        running(true);
        runner.start("Buster-vhost-scan", scan::cancel, () -> scan.scan(cfg, results::addHit, results::status), () -> running(false));
    }

    private void running(boolean on) {
        SwingUtilities.invokeLater(() -> {
            startBtn.setText(on ? "Running..." : "Start");
            startBtn.setEnabled(!on);
        });
    }
}

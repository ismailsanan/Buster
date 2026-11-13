package com.ismailsanan.buster.ui;

import burp.api.montoya.MontoyaApi;

import com.ismailsanan.buster.scan.DnsEnumerator;

import javax.swing.*;
import java.awt.*;

public class DnsPanel {

    private final MontoyaApi api;
    private final JPanel panel;

    private final JTextField domain = new JTextField("target.com");
    private final JSpinner threads  = new JSpinner(new SpinnerNumberModel(30, 1, 100, 1));

    private final WordlistPicker wordlist =
            new WordlistPicker("Paste subdomain words one per line, or Load File");
    private final DnsResultsTable results = new DnsResultsTable();

    private final JButton startBtn = new JButton("Start");
    private final JButton stopBtn  = new JButton("Stop");

    private final ScanRunner runner;

    public DnsPanel(MontoyaApi api) {
        this.api = api;
        this.runner = new ScanRunner(t -> {
            results.status("error: " + t.getMessage());
            api.logging().logToError("[DNS] scan failed", t);
        });
        this.panel = build();
    }

    public JComponent getComponent() { return panel; }
    public void cancel() { runner.cancel(); }
    public void cancelAndWait(long millis) { runner.cancelAndWait(millis); }

    /** EDT only: fill the base domain from another Burp tool */
    public void prefill(String domain) { this.domain.setText(domain); }

    private JPanel build() {
        JLabel note = new JLabel("<html>Uses the system DNS resolver,<br>not Burp's proxy / SOCKS settings.</html>");
        note.setForeground(Color.GRAY);

        Layouts.Form form = new Layouts.Form();
        form.field("Base domain", domain);
        form.row("Threads", threads, " ", new JLabel());
        form.add(note);
        form.buttons(startBtn, stopBtn);

        startBtn.addActionListener(e -> start());
        stopBtn.addActionListener(e -> { cancel(); results.status("stopping..."); });

        JTabbedPane inputs = new JTabbedPane();
        inputs.addTab("Wordlist", wordlist.getComponent());

        return Layouts.modePanel(results.getComponent(), Layouts.config(form.build(), inputs));
    }

    /** runs on the EDT: every Swing value is read here, the worker only gets an immutable config */
    private void start() {
        if (runner.isRunning()) return;
        if (domain.getText().isBlank()) { results.status("enter a domain"); return; }
        DnsEnumerator.Config cfg;
        try {
            cfg = new DnsEnumerator.Config(domain.getText().trim(), wordlist.resolve(), (int) threads.getValue());
        } catch (IllegalArgumentException ex) {
            results.status(ex.getMessage());
            return;
        }

        DnsEnumerator scan = new DnsEnumerator(api);
        running(true);
        runner.start("Buster-dns-scan", scan::cancel, () -> scan.scan(cfg, results::addHit, results::status), () -> running(false));
    }

    private void running(boolean on) {
        SwingUtilities.invokeLater(() -> {
            startBtn.setText(on ? "Running..." : "Start");
            startBtn.setEnabled(!on);
        });
    }
}

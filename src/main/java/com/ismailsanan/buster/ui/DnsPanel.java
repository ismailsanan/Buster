package com.ismailsanan.buster.ui;

import burp.api.montoya.MontoyaApi;

import com.ismailsanan.buster.core.DnsResult;
import com.ismailsanan.buster.scan.DnsEnumerator;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class DnsPanel {

    private static final String SYSTEM_LABEL = "System DNS resolver";
    private static final String BURP_LABEL   = "Burp (HTTP probe)";
    private static final String SYSTEM_HINT  = "Lists A/AAAA records. Bypasses Burp's proxy settings.";
    private static final String BURP_HINT    = "Uses Burp's proxy / SOCKS settings. Finds hosts that answer HTTP(S).";

    private final MontoyaApi api;
    private final JPanel panel;

    private final JTextField domain = new JTextField("target.com");
    private final JSpinner threads  = new JSpinner(new SpinnerNumberModel(30, 1, 100, 1));
    private final JComboBox<String> resolveVia = new JComboBox<>(new String[]{ SYSTEM_LABEL, BURP_LABEL });
    private final JLabel resolveHint = new JLabel();

    private final WordlistPicker wordlist =
            new WordlistPicker("Paste subdomain words one per line, or Load File");
    private final ResultsTable<DnsResult> results = new ResultsTable<>(List.of(
            Column.<DnsResult>of("Subdomain", String.class, 240, DnsResult::host),
            Column.<DnsResult>of("Type", String.class, 60, DnsResult::type),
            Column.<DnsResult>of("Value", String.class, 260, DnsResult::value)), "buster_dns.csv");

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
        resolveVia.setToolTipText("System DNS: real DNS records, sent from this machine. "
                + "Burp: each name is requested over HTTP(S) through Burp, so an upstream proxy or SOCKS "
                + "for a specific network is used; reports names that answer, not IPs.");
        resolveHint.putClientProperty("html.disable", Boolean.TRUE);
        resolveHint.setForeground(Color.GRAY);
        resolveVia.addActionListener(e -> updateHint());
        updateHint();

        Layouts.Form form = new Layouts.Form();
        form.field("Base domain", domain);
        form.row("Threads", threads, " ", new JLabel());
        form.field("Resolve via", resolveVia);
        form.add(resolveHint);
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
            cfg = new DnsEnumerator.Config(domain.getText().trim(), wordlist.resolve(), (int) threads.getValue(),
                    BURP_LABEL.equals(resolveVia.getSelectedItem())
                            ? DnsEnumerator.Resolver.BURP : DnsEnumerator.Resolver.SYSTEM);
        } catch (IllegalArgumentException ex) {
            results.status(ex.getMessage());
            return;
        }

        DnsEnumerator scan = new DnsEnumerator(api);
        running(true);
        runner.start("Buster-dns-scan", scan::cancel, () -> scan.scan(cfg, results::addHit, results::status), () -> running(false));
    }

    private void updateHint() {
        resolveHint.setText(BURP_LABEL.equals(resolveVia.getSelectedItem()) ? BURP_HINT : SYSTEM_HINT);
    }

    private void running(boolean on) {
        SwingUtilities.invokeLater(() -> {
            startBtn.setText(on ? "Running..." : "Start");
            startBtn.setEnabled(!on);
        });
    }
}

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

    private final DomainBox domain  = new DomainBox("target.com");
    private final JSpinner threads  = new JSpinner(new SpinnerNumberModel(30, 1, 100, 1));
    private final JComboBox<String> resolveVia = new JComboBox<>(new String[]{ SYSTEM_LABEL, BURP_LABEL });
    private final JLabel resolveHint = new JLabel();
    private final JLabel example = new JLabel(" ");

    private final WordlistPicker wordlist =
            new WordlistPicker("Paste subdomain words one per line, or Load File");
    private final ResultsTable<DnsResult> results = new ResultsTable<>(List.of(
            Column.<DnsResult>of("Subdomain", String.class, 240, DnsResult::host),
            Column.<DnsResult>of("Type", String.class, 60, DnsResult::type),
            Column.<DnsResult>of("Value", String.class, 260, DnsResult::value)), "buster_dns.csv");
    private final ScanLog log = new ScanLog();

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

    /**
     * EDT only: fill from another Burp tool with the request's host; the base
     * domain list gets every level of it, the registered domain selected
     * (a.b.target.com -> target.com, a.b.target.com and b.target.com in the list)
     */
    public void prefill(String host) { this.domain.fillFrom(host); }

    private JPanel build() {
        resolveVia.setToolTipText("System DNS: real DNS records, sent from this machine. "
                + "Burp: each name is requested over HTTP(S) through Burp, so an upstream proxy or SOCKS "
                + "for a specific network is used; reports names that answer, not IPs.");
        resolveHint.putClientProperty("html.disable", Boolean.TRUE);
        resolveHint.setForeground(Color.GRAY);
        resolveVia.addActionListener(e -> updateHint());
        updateHint();
        domain.setToolTipText("The domain to find subdomains of, e.g. target.com");
        threads.setToolTipText("Lookups in flight at the same time");

        Layouts.Form form = new Layouts.Form();
        form.section("Target");
        form.field("Domain", domain.component());
        form.example(example);
        domain.onChange(this::updateExample);
        updateExample();
        form.section("Lookup");
        form.field("Resolve via", resolveVia);
        form.add(resolveHint);
        form.row("Threads", threads, " ", new JLabel());
        form.buttons(startBtn, stopBtn);

        startBtn.addActionListener(e -> start());
        stopBtn.addActionListener(e -> { cancel(); results.status("stopping..."); });
        stopBtn.setEnabled(false);
        results.autoFilter("Auto-filter: on start, Buster checks for wildcard DNS and hides names that only match it", null);

        JTabbedPane inputs = new JTabbedPane();
        inputs.addTab("Wordlist", wordlist.getComponent());

        JTabbedPane bottom = new JTabbedPane();
        bottom.addTab("Scan log", log.getComponent());
        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, results.getComponent(), bottom);
        left.setResizeWeight(0.75);

        return Layouts.modePanel(left, Layouts.config(form.build(), inputs));
    }

    /** runs on the EDT: every Swing value is read here, the worker only gets an immutable config */
    private void start() {
        if (runner.isRunning()) return;
        if (domain.value().isBlank()) { results.status("enter a domain"); return; }
        DnsEnumerator.Config cfg;
        try {
            cfg = new DnsEnumerator.Config(domain.value(), wordlist.resolve(), (int) threads.getValue(),
                    BURP_LABEL.equals(resolveVia.getSelectedItem())
                            ? DnsEnumerator.Resolver.BURP : DnsEnumerator.Resolver.SYSTEM);
        } catch (IllegalArgumentException ex) {
            results.status(ex.getMessage());
            return;
        }

        boolean viaBurp = cfg.resolver() == DnsEnumerator.Resolver.BURP;
        DnsEnumerator scan = new DnsEnumerator(api)
                .events(new UiScanEvents(results, log, viaBurp ? "req" : "lookups", false, false));
        running(true);
        runner.start("Buster-dns-scan", scan::cancel, () -> scan.scan(cfg, results::addHit, results::status), () -> {
            results.progressFinished(scan.finishedCleanly());
            running(false);
        });
    }

    /** EDT: the names that will be tried, so it is obvious what DNS mode enumerates */
    private void updateExample() {
        String d = domain.value().isBlank() ? "target.com" : domain.value();
        example.setText("e.g. tries admin." + d + ", dev." + d + " ...");
        example.setToolTipText(example(domain.value()));
    }

    static String example(String domain) {
        String d = domain == null || domain.isBlank() ? "target.com" : domain.trim();
        return "e.g. tries admin." + d + ", dev." + d + ", mail." + d + " ...";
    }

    private void updateHint() {
        resolveHint.setText(BURP_LABEL.equals(resolveVia.getSelectedItem()) ? BURP_HINT : SYSTEM_HINT);
    }

    private void running(boolean on) {
        SwingUtilities.invokeLater(() -> {
            startBtn.setText(on ? "Running..." : "Start");
            startBtn.setEnabled(!on);
            stopBtn.setEnabled(on);
        });
    }
}

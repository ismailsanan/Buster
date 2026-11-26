package com.ismailsanan.buster.ui;

import burp.api.montoya.MontoyaApi;

import com.ismailsanan.buster.core.UrlUtil;
import com.ismailsanan.buster.scan.VhostEnumerator;

import javax.swing.*;

public class VhostPanel {

    private final MontoyaApi api;
    private final JPanel panel;

    /** IP or host, optionally :port; a typed http(s):// still works and wins over the checkbox */
    private final JTextField target = new JTextField("10.0.0.5");
    private final JCheckBox https    = new JCheckBox("HTTPS", true);
    private final DomainBox domain   = new DomainBox("target.com");
    private final JSpinner threads   = new JSpinner(new SpinnerNumberModel(10, 1, 50, 1));
    private final JSpinner rps       = new JSpinner(new SpinnerNumberModel(0, 0, 1000, 5));
    private final JCheckBox showAll  = new JCheckBox("Show all responses (turn auto-filter off)", false);
    private final JLabel example     = new JLabel(" ");

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

    /**
     * EDT only: fill from another Burp tool. url is the request's URL: the target
     * becomes its host[:port] without the scheme (HTTPS ticked to match), the
     * base domain list gets every level of the host, its registered domain selected
     */
    public void prefill(String url) {
        String hostPort = UrlUtil.hostPortOf(url);
        String host = UrlUtil.hostOf(url);
        this.target.setText(hostPort);
        this.https.setSelected(UrlUtil.isHttps(url));
        this.domain.fillFrom(host);
        resolveToIp(host, hostPort);
    }

    /**
     * the field asks for an IP: look the host up off the EDT and swap it in,
     * keeping the port; left as the host name when it does not resolve or the
     * user already typed something else
     */
    private void resolveToIp(String host, String hostPort) {
        if (host.startsWith("[") || host.matches("\\d+(\\.\\d+){3}")) return;
        String port = hostPort.substring(host.length());   // "" or ":8443"
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                return java.net.InetAddress.getByName(host).getHostAddress();
            }
            @Override protected void done() {
                try {
                    String ip = get();
                    if (target.getText().equals(hostPort)) target.setText(ip + port);
                } catch (Exception ignored) {
                    // keep the host name, it works as a target too
                }
            }
        }.execute();
    }

    private JPanel build() {
        target.setToolTipText("The server's IP, e.g. 10.0.0.5 (add :port for a non-standard port, e.g. 10.0.0.5:8443)");
        https.setToolTipText("Connect with TLS (default port 443) or plain HTTP (default port 80)");
        domain.setToolTipText("The domain the virtual hosts belong to, e.g. target.com");
        threads.setToolTipText("Requests in flight at the same time");
        rps.setToolTipText("Hard cap on requests per second, 0 = no limit");
        showAll.setToolTipText("List every Host, including the ones that answer like an unknown host "
                + "(they get Type = baseline)");

        Layouts.Form form = new Layouts.Form();
        form.section("Target");
        JPanel targetLine = new JPanel(new java.awt.BorderLayout(6, 0));
        targetLine.add(target, java.awt.BorderLayout.CENTER);
        targetLine.add(https, java.awt.BorderLayout.EAST);
        form.field("IP address", targetLine);
        form.field("Domain", domain.component());
        form.example(example);
        form.section("Speed");
        form.row("Threads", threads, "Max req/s (0 = no limit)", rps);
        form.section("Results");
        form.add(showAll);
        form.buttons(startBtn, stopBtn);

        Runnable refresh = this::updateExample;
        target.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { refresh.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { refresh.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refresh.run(); }
        });
        https.addItemListener(e -> refresh.run());
        domain.onChange(refresh);
        updateExample();

        startBtn.addActionListener(e -> start());
        stopBtn.addActionListener(e -> { cancel(); results.status("stopping..."); });
        stopBtn.setEnabled(false);

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
                    UrlUtil.withScheme(target.getText(), https.isSelected()),
                    domain.value(),
                    wordlist.resolve(),
                    (int) threads.getValue(),
                    headers.resolve(),
                    (int) rps.getValue(),
                    showAll.isSelected());
        } catch (IllegalArgumentException ex) {
            results.status(ex.getMessage());
            return;
        }

        VhostEnumerator scan = new VhostEnumerator(api).events(results.beginScan(false, cfg.showAll()));
        running(true);
        runner.start("Buster-vhost-scan", scan::cancel, () -> scan.scan(cfg, results::addHit, results::status), () -> {
            results.endScan(scan.finishedCleanly());
            running(false);
        });
    }

    /** EDT: one concrete request, so it is obvious what the two fields are for */
    private void updateExample() {
        String host = domain.value().isBlank() ? "admin" : "admin." + domain.value();
        example.setText("e.g. sends Host: " + host);
        example.setToolTipText(example(target.getText(), https.isSelected(), domain.value()));
    }

    /** "e.g. Host: admin.target.com  ->  https://10.0.0.5" */
    static String example(String target, boolean https, String domain) {
        String host = domain == null || domain.isBlank() ? "admin" : "admin." + domain.trim();
        String where;
        try {
            where = UrlUtil.withScheme(target, https);
            if (where.endsWith("/")) where = where.substring(0, where.length() - 1);
        } catch (IllegalArgumentException e) {
            where = "the IP above";
        }
        return "e.g. Host: " + host + "  ->  " + where;
    }

    private void running(boolean on) {
        SwingUtilities.invokeLater(() -> {
            startBtn.setText(on ? "Running..." : "Start");
            startBtn.setEnabled(!on);
            stopBtn.setEnabled(on);
        });
    }
}

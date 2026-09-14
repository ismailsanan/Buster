package ui;

import burp.api.montoya.MontoyaApi;

import core.WordlistSource;
import modes.DnsEnumerator;

import javax.swing.*;

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

    private DnsEnumerator scan;

    public DnsPanel(MontoyaApi api) {
        this.api = api;
        this.panel = build();
    }

    public JComponent getComponent() { return panel; }
    public void cancel() { if (scan != null) scan.cancel(); }

    private JPanel build() {
        Layouts.Form form = new Layouts.Form();
        form.field("Base domain", domain);
        form.row("Threads", threads, " ", new JLabel());
        form.buttons(startBtn, stopBtn);

        startBtn.addActionListener(e -> start());
        stopBtn.addActionListener(e -> { cancel(); results.status("stopping..."); });

        JTabbedPane inputs = new JTabbedPane();
        inputs.addTab("Wordlist", wordlist.getComponent());

        return Layouts.modePanel(results.getComponent(), Layouts.config(form.build(), inputs));
    }

    private void start() {
        if (domain.getText().isBlank()) { results.status("enter a domain"); return; }
        running(true);
        new Thread(() -> {
            try {
                WordlistSource words = wordlist.resolve();
                scan = new DnsEnumerator(api);
                scan.scan(domain.getText().trim(), words, (int) threads.getValue(),
                        results::addHit, results::status);
            } catch (Exception ex) {
                results.status("error: " + ex.getMessage());
            } finally {
                running(false);
            }
        }).start();
    }

    private void running(boolean on) {
        SwingUtilities.invokeLater(() -> {
            startBtn.setText(on ? "Running..." : "Start");
            startBtn.setEnabled(!on);
        });
    }
}

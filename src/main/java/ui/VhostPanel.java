package ui;

import burp.api.montoya.MontoyaApi;

import modes.VhostEnumerator;

import javax.swing.*;
import java.util.List;

/** vhost mode tab, results left, config right */
public class VhostPanel {

    private final MontoyaApi api;
    private final JPanel panel;

    private final JTextField target = new JTextField("https://10.0.0.5");
    private final JTextField domain = new JTextField("target.com");
    private final JSpinner threads  = new JSpinner(new SpinnerNumberModel(10, 1, 50, 1));

    private final WordlistPicker wordlist =
            new WordlistPicker("Paste subdomain words one per line, or Load File");
    private final ResultsTable results;

    private final JButton startBtn = new JButton("Start");
    private final JButton stopBtn  = new JButton("Stop");

    private VhostEnumerator scan;

    public VhostPanel(MontoyaApi api) {
        this.api = api;
        this.results = new ResultsTable(api);
        this.panel = Layouts.modePanel(results, buildConfig());
    }

    public JComponent getComponent() { return panel; }
    public void cancel() { if (scan != null) scan.cancel(); }

    private JComponent buildConfig() {
        Layouts.Form form = new Layouts.Form();
        form.field("Target (IP/host)", target);
        form.field("Base domain", domain);
        form.row("Threads", threads, " ", new JLabel());
        form.buttons(startBtn, stopBtn);

        startBtn.addActionListener(e -> start());
        stopBtn.addActionListener(e -> { cancel(); results.status("stopping..."); });

        JTabbedPane inputs = new JTabbedPane();
        inputs.addTab("Wordlist", wordlist.getComponent());

        return Layouts.config(form.build(), inputs);
    }

    private void start() {
        if (target.getText().isBlank()) { results.status("enter a target"); return; }
        running(true);
        new Thread(() -> {
            try {
                List<String> words = wordlist.resolve();
                scan = new VhostEnumerator(api);
                scan.scan(target.getText().trim(), domain.getText().trim(),
                        words, (int) threads.getValue(), results::addHit, results::status);
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
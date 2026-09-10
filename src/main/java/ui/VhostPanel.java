package ui;

import burp.api.montoya.MontoyaApi;

import modes.VhostEnumerator;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** vhost mode tab, paste or load a subdomain wordlist */
public class VhostPanel {

    private final MontoyaApi api;
    private final JPanel panel;

    private final JTextField target = new JTextField("https://10.0.0.5", 24);
    private final JTextField domain = new JTextField("target.com", 20);
    private final JSpinner threads  = new JSpinner(new SpinnerNumberModel(20, 1, 100, 1));

    private final WordlistPicker wordlist =
            new WordlistPicker("Paste subdomain words one per line, or Load File");
    private final ResultsTable results = new ResultsTable();
    private final JLabel status = new JLabel("Idle");

    private VhostEnumerator scan;

    public VhostPanel(MontoyaApi api) { this.api = api; this.panel = build(); }

    public JComponent getComponent() { return panel; }
    public void cancel() { if (scan != null) scan.cancel(); }

    private JPanel build() {
        JPanel opts = new JPanel();
        opts.setLayout(new BoxLayout(opts, BoxLayout.Y_AXIS));
        opts.add(row(new JLabel("Target (IP/host):"), target,
                     new JLabel("Base domain:"), domain, new JLabel("Threads:"), threads));
        opts.add(controls());

        JSplitPane inputSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                opts, wordlist.getComponent());
        inputSplit.setResizeWeight(0.0);

        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                inputSplit, results.getComponent());
        main.setResizeWeight(0.35);

        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.add(main, BorderLayout.CENTER);
        return p;
    }

    private JPanel controls() {
        JButton start = new JButton("Start");
        JButton stop  = new JButton("Stop");
        start.addActionListener(e -> start());
        stop.addActionListener(e -> { cancel(); status.setText("Stopping..."); });
        return row(start, stop, status);
    }

    private void start() {
        if (target.getText().isBlank()) { status.setText("Enter a target"); return; }
        new Thread(() -> {
            try {
                List<String> words = wordlist.resolve();
                status.setText("Running (" + words.size() + " hosts)");
                scan = new VhostEnumerator(api);
                scan.scan(target.getText().trim(), domain.getText().trim(),
                        words, (int) threads.getValue(), results::addHit);
                status.setText("Done");
            } catch (Exception ex) {
                status.setText(ex.getMessage());
            }
        }).start();
    }

    private JPanel row(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (Component c : cs) p.add(c);
        return p;
    }
}

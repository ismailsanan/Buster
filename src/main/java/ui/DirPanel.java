package ui;

import burp.api.montoya.MontoyaApi;

import modes.DirEnumerator;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** dir mode tab, paste or load a path wordlist */
public class DirPanel {

    private final MontoyaApi api;
    private final JPanel panel;

    private final JTextField target     = new JTextField("https://example.com", 28);
    private final JTextField extensions = new JTextField(".php,.bak,.old,.txt,.zip", 20);
    private final JSpinner depth        = new JSpinner(new SpinnerNumberModel(1, 0, 6, 1));
    private final JSpinner threads      = new JSpinner(new SpinnerNumberModel(20, 1, 100, 1));
    private final JCheckBox extOnRec    = new JCheckBox("Extensions on recursion", false);
    private final JCheckBox collapse    = new JCheckBox("Collapse duplicates", true);

    private final WordlistPicker wordlist =
            new WordlistPicker("Paste paths one per line, or Load File (e.g. SecLists)");
    private final ResultsTable results = new ResultsTable();
    private final JLabel status = new JLabel("Idle");

    private DirEnumerator scan;

    public DirPanel(MontoyaApi api) { this.api = api; this.panel = build(); }

    public JComponent getComponent() { return panel; }
    public void cancel() { if (scan != null) scan.cancel(); }

    private JPanel build() {
        JPanel opts = new JPanel();
        opts.setLayout(new BoxLayout(opts, BoxLayout.Y_AXIS));
        opts.add(row(new JLabel("Target:"), target));
        opts.add(row(new JLabel("Extensions:"), extensions,
                     new JLabel("Depth:"), depth, new JLabel("Threads:"), threads));
        opts.add(row(extOnRec, collapse));
        opts.add(controls());

        // options on top, wordlist box in the middle, results fill the rest
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

        List<String> exts = new ArrayList<>();
        exts.add("");
        for (String e : extensions.getText().split(",")) {
            String t = e.trim();
            if (!t.isEmpty()) exts.add(t.startsWith(".") ? t : "." + t);
        }

        new Thread(() -> {
            try {
                List<String> words = wordlist.resolve();
                status.setText("Running (" + words.size() + " words)");
                scan = new DirEnumerator(api);
                scan.scan(target.getText().trim(), words, exts,
                        (int) depth.getValue(), (int) threads.getValue(),
                        extOnRec.isSelected(), collapse.isSelected(), results::addHit);
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

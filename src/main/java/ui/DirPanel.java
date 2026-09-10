package ui;

import burp.api.montoya.MontoyaApi;

import modes.DirEnumerator;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * dir mode tab
 *
 * results and the request/response viewer take the LEFT (main area),
 * a compact config column sits on the RIGHT with a tabbed Wordlist / Headers
 * section, Start turns into Running while a scan is active
 */
public class DirPanel {

    private final MontoyaApi api;
    private final JPanel panel;

    private final JTextField target     = new JTextField("https://example.com");
    private final JTextField extensions = new JTextField("");
    private final JSpinner depth        = new JSpinner(new SpinnerNumberModel(1, 0, 6, 1));
    private final JSpinner threads      = new JSpinner(new SpinnerNumberModel(10, 1, 50, 1));
    private final JCheckBox extOnRec    = new JCheckBox("Extensions on recursion", false);
    private final JCheckBox collapse    = new JCheckBox("Collapse duplicates", true);

    private final WordlistPicker wordlist =
            new WordlistPicker("Paste paths one per line, or Load File (e.g. SecLists)");
    private final HeadersPanel headers = new HeadersPanel();
    private final ResultsTable results;

    private final JButton startBtn = new JButton("Start");
    private final JButton stopBtn  = new JButton("Stop");

    private DirEnumerator scan;

    public DirPanel(MontoyaApi api) {
        this.api = api;
        this.results = new ResultsTable(api);
        this.panel = Layouts.modePanel(results, buildConfig());
    }

    public JComponent getComponent() { return panel; }
    public void cancel() { if (scan != null) scan.cancel(); }

    private JComponent buildConfig() {
        Layouts.Form form = new Layouts.Form();
        form.field("Target", target);
        form.field("Extensions (blank = dirs only)", extensions);
        form.row("Depth", depth, "Threads", threads);
        form.add(extOnRec);
        form.add(collapse);
        form.buttons(startBtn, stopBtn);

        startBtn.addActionListener(e -> start());
        stopBtn.addActionListener(e -> { cancel(); results.status("stopping..."); });

        JTabbedPane inputs = new JTabbedPane();
        inputs.addTab("Wordlist", wordlist.getComponent());
        inputs.addTab("Headers / Auth", headers.getComponent());

        return Layouts.config(form.build(), inputs);
    }

    private void start() {
        if (target.getText().isBlank()) { results.status("enter a target"); return; }

        List<String> exts = new ArrayList<>();
        exts.add("");
        for (String e : extensions.getText().split(",")) {
            String t = e.trim();
            if (!t.isEmpty()) exts.add(t.startsWith(".") ? t : "." + t);
        }

        running(true);
        new Thread(() -> {
            try {
                List<String> words = wordlist.resolve();
                scan = new DirEnumerator(api);
                scan.scan(target.getText().trim(), words, exts,
                        (int) depth.getValue(), (int) threads.getValue(),
                        extOnRec.isSelected(), collapse.isSelected(),
                        headers.resolve(), results::addHit, results::status);
            } catch (Exception ex) {
                results.status("error: " + ex.getMessage());
            } finally {
                running(false);
            }
        }).start();
    }

    // Start becomes "Running..." and disables while a scan is active
    private void running(boolean on) {
        SwingUtilities.invokeLater(() -> {
            startBtn.setText(on ? "Running..." : "Start");
            startBtn.setEnabled(!on);
        });
    }
}
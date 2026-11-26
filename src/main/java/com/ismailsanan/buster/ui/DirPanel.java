package com.ismailsanan.buster.ui;

import burp.api.montoya.MontoyaApi;

import com.ismailsanan.buster.core.UrlUtil;
import com.ismailsanan.buster.scan.DirEnumerator;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

public class DirPanel {

    private final MontoyaApi api;
    private final JPanel panel;

    private final JTextField target     = new JTextField("https://example.com/");
    private final JTextField extensions = new JTextField("");
    private final JSpinner depth        = new JSpinner(new SpinnerNumberModel(1, 0, 6, 1));
    private final JSpinner threads      = new JSpinner(new SpinnerNumberModel(30, 1, 50, 1));
    private final JSpinner rps          = new JSpinner(new SpinnerNumberModel(0, 0, 1000, 5));
    private final JCheckBox extOnRec    = new JCheckBox("Also try extensions inside found directories", false);
    private final JCheckBox collapse    = new JCheckBox("Collapse duplicates (same status + size)", true);
    private final JCheckBox showAll     = new JCheckBox("Show all responses (turn auto-filter off)", false);

    private final WordlistPicker wordlist =
            new WordlistPicker("Paste paths one per line, or Load File (e.g. SecLists)");
    private final HeadersPanel headers = new HeadersPanel();
    private final HttpResultsPanel results;

    private final JButton startBtn = new JButton("Start");
    private final JButton stopBtn  = new JButton("Stop");

    private final ScanRunner runner;

    public DirPanel(MontoyaApi api) {
        this.api = api;
        this.results = new HttpResultsPanel(api);
        this.runner = new ScanRunner(t -> {
            results.status("error: " + t.getMessage());
            api.logging().logToError("[DIR] scan failed", t);
        });
        this.panel = build();
    }

    public JComponent getComponent() { return panel; }
    public void cancel() { runner.cancel(); }
    public void cancelAndWait(long millis) { runner.cancelAndWait(millis); }

    /** EDT only: fill the target from another Burp tool */
    public void prefill(String target) { this.target.setText(target); }

    private JPanel build() {
        target.setToolTipText("URL of the directory to brute force, e.g. https://target.com/app/");
        extensions.setToolTipText("Comma separated, e.g. php, bak, txt: the word 'admin' is then sent as "
                + "admin, admin.php, admin.bak and admin.txt. Blank = only the bare words.");
        depth.setToolTipText("0 = only the target directory, 1 = also inside every directory found there, ...");
        threads.setToolTipText("Requests in flight at the same time");
        rps.setToolTipText("Hard cap on requests per second across all threads, 0 = no limit. "
                + "429 / 503 + Retry-After slow the scan down automatically either way.");
        extOnRec.setToolTipText("Off = extensions only at the top level and bare words deeper down (much faster)");
        collapse.setToolTipText("Hide repeated responses with the same status and size within one directory. "
                + "Directories are never hidden.");
        showAll.setToolTipText("List every response, including the ones that look like this directory's "
                + "'not found' answer (they get Type = baseline). Use the Hide status / Hide size filters "
                + "above the results to trim the list.");

        Layouts.Form form = new Layouts.Form();
        form.section("Target");
        form.field("URL", target);
        form.field("Extensions (e.g. php, bak; blank = bare words only)", extensions);
        form.section("Speed and depth");
        form.row("Depth", depth, "Threads", threads);
        form.row("Max req/s (0 = no limit)", rps, " ", new JLabel());
        form.section("Results");
        form.add(showAll);
        form.add(collapse);
        form.add(extOnRec);
        form.buttons(startBtn, stopBtn);

        startBtn.addActionListener(e -> start());
        stopBtn.addActionListener(e -> { cancel(); results.status("stopping..."); });
        stopBtn.setEnabled(false);
        showAll.addItemListener(e -> collapse.setEnabled(!showAll.isSelected()));

        JTabbedPane inputs = new JTabbedPane();
        inputs.addTab("Wordlist", wordlist.getComponent());
        inputs.addTab("Headers / Auth", headers.getComponent());

        return Layouts.modePanel(results.getComponent(), Layouts.config(form.build(), inputs));
    }

    /** runs on the EDT: every Swing value is read here, the worker only gets an immutable config */
    private void start() {
        if (runner.isRunning()) return;
        DirEnumerator.Config cfg;
        try {
            cfg = new DirEnumerator.Config(
                    UrlUtil.normalizeTarget(target.getText(), true),
                    wordlist.resolve(),
                    parseExtensions(extensions.getText()),
                    (int) depth.getValue(),
                    (int) threads.getValue(),
                    extOnRec.isSelected(),
                    collapse.isSelected(),
                    headers.resolve(),
                    (int) rps.getValue(),
                    showAll.isSelected());
        } catch (IllegalArgumentException ex) {
            results.status(ex.getMessage());
            return;
        }

        DirEnumerator scan = new DirEnumerator(api).events(results.beginScan(true, cfg.showAll()));
        running(true);
        runner.start("Buster-dir-scan", scan::cancel, () -> scan.scan(cfg, results::addHit, results::status), () -> {
            results.endScan(scan.finishedCleanly());
            running(false);
        });
    }

    static List<String> parseExtensions(String raw) {
        List<String> exts = new ArrayList<>();
        exts.add("");
        for (String e : raw.split(",")) {
            String t = e.trim();
            if (!t.isEmpty()) exts.add(t.startsWith(".") ? t : "." + t);
        }
        return exts;
    }

    private void running(boolean on) {
        SwingUtilities.invokeLater(() -> {
            startBtn.setText(on ? "Running..." : "Start");
            startBtn.setEnabled(!on);
            stopBtn.setEnabled(on);
        });
    }
}

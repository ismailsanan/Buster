package ui;

import core.WordlistSource;
import core.Wordlists;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * wordlist input paste small lists or Load File for big ones
 * a loaded file is NOT read into the box, only its path and line count are
 * kept the scan streams it so large file can be loaded
 */
public class WordlistPicker {

    private final JPanel panel;
    private final JTextArea area = new JTextArea(8, 30);
    private final JLabel count = new JLabel("0 words");
    private final JButton load;

    private Path loadedFile = null;
    private long loadedLines = 0;

    public WordlistPicker(String placeholder) {
        area.setFont(new Font("Monospaced", Font.PLAIN, 12));
        area.setToolTipText(placeholder);

        area.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { onEdit(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { onEdit(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { onEdit(); }
        });

        load          = new JButton("Load File");
        JButton clear = new JButton("Clear");
        load.addActionListener(e -> loadFile((Component) e.getSource()));
        clear.addActionListener(e -> { loadedFile = null; area.setText(""); });

        JLabel hint = new JLabel("(paste with Ctrl+V)");
        hint.setForeground(Color.GRAY);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(new JLabel("Wordlist:"));
        buttons.add(load);
        buttons.add(clear);
        buttons.add(hint);
        buttons.add(count);

        panel = new JPanel(new BorderLayout());
        panel.add(buttons, BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
    }

    public JComponent getComponent() { return panel; }

    public WordlistSource resolve() {
        if (loadedFile != null) return WordlistSource.ofFile(loadedFile, loadedLines);
        List<String> words = Wordlists.parse(area.getText());
        if (words.isEmpty())
            throw new IllegalArgumentException("wordlist is empty, paste words or load a file");
        return WordlistSource.ofList(words);
    }

    private void loadFile(Component parent) {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;

        File file = chooser.getSelectedFile();
        load.setEnabled(false);
        load.setText("Loading...");

        new SwingWorker<Long, Void>() {
            @Override protected Long doInBackground() {
                return WordlistSource.countLines(file.toPath());
            }
            @Override protected void done() {
                load.setEnabled(true);
                load.setText("Load File");
                try {
                    long lines = get();
                    if (lines < 0) {
                        JOptionPane.showMessageDialog(parent, "Could not read the file",
                                "Load failed", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    loadedFile = file.toPath();
                    loadedLines = lines;
                    area.setText("# loaded " + file.getName() + "\n"
                            + "# " + lines + " lines, streamed at scan time\n"
                            + "# (type here to use a pasted list instead)\n");
                    area.setCaretPosition(0);
                    count.setText(lines + " words (file)");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parent, "Could not read the file: " + ex.getMessage(),
                            "Load failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void onEdit() {
        String text = area.getText();
        boolean justNote = text.lines().allMatch(l -> l.isBlank() || l.startsWith("#"));
        if (!justNote) {
            loadedFile = null;
            int n = Wordlists.parse(text).size();
            count.setText(n + (n == 1 ? " word" : " words"));
        }
    }
}

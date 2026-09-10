package ui;

import core.Wordlists;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

/**
 * Intruder style wordlist input, reused by every mode
 * resolve() hands back the parsed words from whatever is currently in the box
 */
public class WordlistPicker {

    private final JPanel panel;
    private final JTextArea area = new JTextArea(6, 30);
    private final JLabel count = new JLabel("0 words");

    public WordlistPicker(String placeholder) {
        area.setLineWrap(false);
        area.setFont(new Font("Monospaced", Font.PLAIN, 12));
        area.setToolTipText(placeholder);

        // live word count as the user pastes or edits
        area.getDocument().addUndoableEditListener(e -> updateCount());
        area.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyReleased(java.awt.event.KeyEvent e) { updateCount(); }
        });

        JButton load  = new JButton("Load File");
        JButton clear = new JButton("Clear");

        load.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog((Component) e.getSource()) == JFileChooser.APPROVE_OPTION) {
                File f = chooser.getSelectedFile();
                area.setText(Wordlists.readFile(f.toPath()));
                updateCount();
            }
        });

        clear.addActionListener(e -> { area.setText(""); updateCount(); });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(new JLabel("Wordlist:"));
        buttons.add(load);
        buttons.add(clear);
        buttons.add(count);

        panel = new JPanel(new BorderLayout());
        panel.add(buttons, BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
    }

    public JComponent getComponent() { return panel; }

    public List<String> resolve() {
        List<String> words = Wordlists.parse(area.getText());
        if (words.isEmpty())
            throw new IllegalArgumentException("wordlist is empty, paste words or load a file");
        return words;
    }

    private void updateCount() {
        int n = Wordlists.parse(area.getText()).size();
        count.setText(n + (n == 1 ? " word" : " words"));
    }
}
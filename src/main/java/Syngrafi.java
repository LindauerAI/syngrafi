import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import api.APIProvider;
import api.GeminiProvider;
import api.OpenAIProvider;

public class Syngrafi extends JFrame {

    // UI Components
    private ImprovedTextEditor textEditor;
    private JLabel statusBar;
    private PreferencesManager preferencesManager;
    private APIProvider currentProvider;
    private File currentFile = null;

    // We'll store a reference to the "Recent Files" menu
    private JMenu recentFilesMenu;

    public Syngrafi() {
        super("Syngrafi");
        preferencesManager = new PreferencesManager();
        initUI();
    }

    private void initUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10,10));

        // Create status bar
        statusBar = new JLabel("Ready");
        statusBar.setBorder(BorderFactory.createEmptyBorder(5,10,5,10));
        add(statusBar, BorderLayout.SOUTH);

        // Create a left-hand sidebar
        JPanel sidebar = new JPanel();
        sidebar.setPreferredSize(new Dimension(200, 600));
        sidebar.setLayout(new BorderLayout());
        sidebar.setBorder(BorderFactory.createTitledBorder("Sidebar"));
        JLabel placeholderLabel = new JLabel("<html><i>Reserved for future features...</i></html>");
        placeholderLabel.setHorizontalAlignment(JLabel.CENTER);
        sidebar.add(placeholderLabel, BorderLayout.CENTER);
        add(sidebar, BorderLayout.WEST);

        createMenuBar();
        createTopPanel();
        createEditorPanel();

        setSize(1200, 800);
        setLocationRelativeTo(null);
    }

    private void createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");

        JMenuItem newItem = new JMenuItem("New");
        newItem.setAccelerator(KeyStroke.getKeyStroke("control N"));
        newItem.addActionListener(e -> handleNewDocument());
        fileMenu.add(newItem);

        JMenuItem openItem = new JMenuItem("Open");
        openItem.setAccelerator(KeyStroke.getKeyStroke("control O"));
        openItem.addActionListener(e -> handleOpenDocument());
        fileMenu.add(openItem);

        JMenuItem saveItem = new JMenuItem("Save");
        saveItem.setAccelerator(KeyStroke.getKeyStroke("control S"));
        saveItem.addActionListener(e -> saveDocument());
        fileMenu.add(saveItem);

        // We'll create the "Recent Files" menu as a sub-menu of File
        recentFilesMenu = new JMenu("Recent Files");
        updateRecentFilesMenu();
        fileMenu.add(recentFilesMenu);

        JMenuItem commitVersionItem = new JMenuItem("Commit Version");
        commitVersionItem.addActionListener(e -> commitVersion());
        fileMenu.add(commitVersionItem);

        menuBar.add(fileMenu);

        // Settings Menu
        JMenu settingsMenu = new JMenu("Settings");
        JMenuItem apiSettingsItem = new JMenuItem("API Settings");
        apiSettingsItem.addActionListener(e -> {
            new SettingsDialog(this, preferencesManager).setVisible(true);
        });
        settingsMenu.add(apiSettingsItem);
        menuBar.add(settingsMenu);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem aboutItem = new JMenuItem("About Syngrafi");
        aboutItem.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Syngrafi\nVersion 1.0\n\n" +
                        "A professional writing tool combining rich text editing with AI assistance\n" +
                        "for composition, rewriting, and content generation.",
                "About Syngrafi", JOptionPane.INFORMATION_MESSAGE));
        helpMenu.add(aboutItem);
        menuBar.add(helpMenu);

        setJMenuBar(menuBar);
    }

    private void updateRecentFilesMenu() {
        recentFilesMenu.removeAll();
        for (String filePath : preferencesManager.getRecentFiles()) {
            JMenuItem item = new JMenuItem(filePath);
            item.addActionListener(e -> {
                if (checkUnsavedChanges()) {
                    currentFile = new File(filePath);
                    openDocument(currentFile);
                    preferencesManager.addRecentFile(currentFile.getAbsolutePath());
                    updateRecentFilesMenu();
                }
            });
            recentFilesMenu.add(item);
        }
    }

    private void createTopPanel() {
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        topPanel.setBorder(BorderFactory.createEmptyBorder(5,5,5,5));

        // Formatting toolbar
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);

        // B, I, U
        JButton boldButton = new JButton("B");
        boldButton.setFont(boldButton.getFont().deriveFont(Font.BOLD));
        boldButton.setToolTipText("Ctrl+B toggles bold");
        boldButton.addActionListener(e -> textEditor.applyBoldToSelectionOrToggle());
        toolBar.add(boldButton);

        JButton italicButton = new JButton("I");
        italicButton.setFont(italicButton.getFont().deriveFont(Font.ITALIC));
        italicButton.setToolTipText("Ctrl+I toggles italic");
        italicButton.addActionListener(e -> textEditor.applyItalicToSelectionOrToggle());
        toolBar.add(italicButton);

        JButton underlineButton = new JButton("U");
        underlineButton.setToolTipText("Ctrl+U toggles underline");
        underlineButton.addActionListener(e -> textEditor.applyUnderlineToSelectionOrToggle());
        toolBar.add(underlineButton);

        // Headings
        toolBar.addSeparator();

        JButton h1Button = new JButton("H1");
        h1Button.setToolTipText("Heading 1");
        h1Button.addActionListener(e -> textEditor.setHeadingLevel(1));
        toolBar.add(h1Button);

        JButton h2Button = new JButton("H2");
        h2Button.setToolTipText("Heading 2");
        h2Button.addActionListener(e -> textEditor.setHeadingLevel(2));
        toolBar.add(h2Button);

        // Normal
        JButton normalButton = new JButton("Normal");
        normalButton.setToolTipText("Revert selection to normal text");
        normalButton.addActionListener(e -> textEditor.setNormalText());
        toolBar.add(normalButton);

        toolBar.addSeparator();

        // Font family
        JLabel fontLabel = new JLabel("Font:");
        toolBar.add(fontLabel);
        JComboBox<String> fontCombo = new JComboBox<>(new String[]{"Serif","SansSerif","Monospaced"});
        fontCombo.setSelectedItem("Serif");
        fontCombo.addActionListener(e -> {
            String chosen = (String) fontCombo.getSelectedItem();
            textEditor.setFontFamily(chosen);
        });
        toolBar.add(fontCombo);

        // Font size
        toolBar.addSeparator();
        JLabel sizeLabel = new JLabel("Size:");
        toolBar.add(sizeLabel);
        JComboBox<Integer> sizeCombo = new JComboBox<>(new Integer[]{12,14,16,18,24,36});
        sizeCombo.setSelectedItem(12); // default normal 12
        sizeCombo.addActionListener(e -> {
            Integer chosenSize = (Integer) sizeCombo.getSelectedItem();
            textEditor.setFontSize(chosenSize);
        });
        toolBar.add(sizeCombo);

        toolBar.addSeparator();

        // Manual autocomplete button
        JButton manualAutocompleteButton = new JButton("Suggest (Ctrl+D)");
        manualAutocompleteButton.setToolTipText("Manually trigger autocomplete suggestions");
        manualAutocompleteButton.addActionListener(e -> textEditor.triggerAutocomplete());
        toolBar.add(manualAutocompleteButton);

        topPanel.add(toolBar);
        add(topPanel, BorderLayout.NORTH);
    }

    private void createEditorPanel() {
        textEditor = new ImprovedTextEditor(statusBar, preferencesManager);
        // normal text is size=12
        textEditor.setFont(new Font("Serif", Font.PLAIN, 12));

        // Key bindings
        InputMap im = textEditor.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = textEditor.getActionMap();

        // Ctrl+D => trigger suggestions
        KeyStroke ctrlD = KeyStroke.getKeyStroke("control D");
        im.put(ctrlD, "triggerAutocompleteImmediate");
        am.put("triggerAutocompleteImmediate", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                textEditor.triggerAutocomplete();
            }
        });

        // Ctrl+1..Ctrl+9 => accept that suggestion
        for (int i = 1; i <= 9; i++) {
            final int suggestionIndex = i - 1;
            KeyStroke ks = KeyStroke.getKeyStroke("control " + i);
            im.put(ks, "insertSuggestion" + i);
            am.put("insertSuggestion" + i, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    textEditor.insertSuggestionByIndex(suggestionIndex);
                }
            });
        }

        // Ctrl+B => Bold
        KeyStroke ctrlB = KeyStroke.getKeyStroke("control B");
        im.put(ctrlB, "boldSelection");
        am.put("boldSelection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                textEditor.applyBoldToSelectionOrToggle();
            }
        });

        // Ctrl+I => Italic
        KeyStroke ctrlI = KeyStroke.getKeyStroke("control I");
        im.put(ctrlI, "italicSelection");
        am.put("italicSelection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                textEditor.applyItalicToSelectionOrToggle();
            }
        });

        // Ctrl+U => Underline
        KeyStroke ctrlU = KeyStroke.getKeyStroke("control U");
        im.put(ctrlU, "underlineSelection");
        am.put("underlineSelection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                textEditor.applyUnderlineToSelectionOrToggle();
            }
        });

        JScrollPane scrollPane = new JScrollPane(textEditor);
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Called by SettingsDialog to update the currentProvider.
     * Also sets it in textEditor immediately so suggestions can work
     * without needing to reopen settings.
     */
    public void updateAPIProvider(String openAIKey, String geminiKey,
                                  String provider, String model) {
        if (provider.equals("OpenAI")) {
            currentProvider = new OpenAIProvider(openAIKey, model);
        } else {
            currentProvider = new GeminiProvider(geminiKey, model);
        }
        if (textEditor != null) {
            textEditor.setAPIProvider(currentProvider);
        }
        statusBar.setText("Provider changed to " + provider + " | Model: " + model);
    }

    private File getDefaultDirectory() {
        String defPath = preferencesManager.getPreference("defaultPath", System.getProperty("user.dir"));
        return new File(defPath);
    }

    /**
     * Prompt to save if unsaved changes exist, then create new doc.
     */
    private void handleNewDocument() {
        if (checkUnsavedChanges()) {
            newDocument();
        }
    }

    private void newDocument() {
        textEditor.resetCharacterCounts();
        textEditor.setText("");
        currentFile = null;
        statusBar.setText("New document created.");
    }

    private void handleOpenDocument() {
        if (!checkUnsavedChanges()) {
            return; // user canceled
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setCurrentDirectory(getDefaultDirectory());
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            currentFile = chooser.getSelectedFile();
            openDocument(currentFile);
            preferencesManager.addRecentFile(currentFile.getAbsolutePath());
            updateRecentFilesMenu();
        }
    }

    /**
     * Return false if user chooses "Cancel" in the "Save changes?" dialog.
     * Return true if user chooses "Yes" or "No".
     */
    private boolean checkUnsavedChanges() {
        if (textEditor.isDirty()) {
            int option = JOptionPane.showConfirmDialog(this,
                    "You have unsaved changes. Save now?",
                    "Unsaved Changes",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (option == JOptionPane.CANCEL_OPTION) {
                return false;
            } else if (option == JOptionPane.YES_OPTION) {
                saveDocument();
            }
        }
        return true;
    }

    public void openDocument(File file) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            statusBar.setText("Error opening file.");
            return;
        }
        String entireText = sb.toString();

        // parse out AI/HUMAN
        Pattern p = Pattern.compile("<!--\\s*AI_CHARS=(\\d+)\\s+HUMAN_CHARS=(\\d+)\\s*-->");
        Matcher m = p.matcher(entireText);
        int aiChars = 0, humanChars = 0;
        if (m.find()) {
            try {
                aiChars = Integer.parseInt(m.group(1));
                humanChars = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {}
            entireText = m.replaceFirst("");
        }
        textEditor.setAICharCount(aiChars);
        textEditor.setHumanCharCount(humanChars);

        // Load the HTML into the editor
        textEditor.setText(entireText);
        textEditor.markClean();

        statusBar.setText("Opened: " + file.getName()
                + " [AI=" + aiChars + ", Human=" + humanChars + "]");
    }

    public void saveDocument() {
        if (currentFile == null) {
            JFileChooser chooser = new JFileChooser();
            chooser.setCurrentDirectory(getDefaultDirectory());
            int result = chooser.showSaveDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                currentFile = chooser.getSelectedFile();
                if (!currentFile.getName().toLowerCase().endsWith(".html")) {
                    currentFile = new File(currentFile.getAbsolutePath() + ".html");
                }
            } else {
                return;
            }
        }

        if (currentFile != null) {
            String textToSave = textEditor.getText();
            int aiCount = textEditor.getAICharCount();
            int humanCount = textEditor.getHumanCharCount();
            textToSave += "\n<!-- AI_CHARS=" + aiCount + " HUMAN_CHARS=" + humanCount + " -->\n";

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(currentFile))) {
                writer.write(textToSave);
                statusBar.setText("Saved: " + currentFile.getName()
                        + " [AI=" + aiCount + ", Human=" + humanCount + "]");
                preferencesManager.addRecentFile(currentFile.getAbsolutePath());
                updateRecentFilesMenu();
                textEditor.markClean();
            } catch (IOException ex) {
                ex.printStackTrace();
                statusBar.setText("Error saving file.");
            }
        }
    }

    private void commitVersion() {
        String text = textEditor.getText();
        if (text.isEmpty()) {
            statusBar.setText("Nothing to commit.");
            return;
        }
        int aiCount = textEditor.getAICharCount();
        int humanCount = textEditor.getHumanCharCount();

        File versionDir = new File("versions");
        if (!versionDir.exists()) {
            versionDir.mkdir();
        }

        String fileName = "version_" + System.currentTimeMillis() + ".html";
        File versionFile = new File(versionDir, fileName);

        text += "\n<!-- AI_CHARS=" + aiCount + " HUMAN_CHARS=" + humanCount + " -->\n";

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(versionFile))) {
            writer.write(text);
            statusBar.setText("Version committed: " + fileName +
                    " [AI=" + aiCount + ", Human=" + humanCount + "]");
            JOptionPane.showMessageDialog(this,
                    "Version committed successfully!\nSaved to: " + versionFile.getAbsolutePath(),
                    "Version Control", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            ex.printStackTrace();
            statusBar.setText("Error committing version.");
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> {
            Syngrafi app = new Syngrafi();
            app.setVisible(true);
        });
    }
}

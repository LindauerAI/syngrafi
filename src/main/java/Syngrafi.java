import javax.swing.*;
import javax.swing.text.Document;
import javax.swing.text.StyleConstants;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import api.APIProvider;
import api.GeminiProvider;
import api.OpenAIProvider;
import com.itextpdf.html2pdf.HtmlConverter;


public class Syngrafi extends JFrame {
    private TextEditor textEditor;
    private JLabel statusBar;
    private PreferencesManager preferencesManager;
    private APIProvider currentProvider;
    private File currentFile = null;

    private JMenu recentFilesMenu;

    public Syngrafi() {
        super("Syngrafi");
        preferencesManager = new PreferencesManager();
        preferencesManager.loadPreferences();

        String openAIKey = preferencesManager.getPreference("apiKeyOpenAI", "");
        String geminiKey = preferencesManager.getPreference("apiKeyGemini", "");
        String provider = preferencesManager.getPreference("provider", "OpenAI");
        String model = preferencesManager.getPreference("model",
                provider.equals("OpenAI") ? "gpt-4o" : "gemini-2.0-flash");
        initStatusBar();
        initUI();
        updateAPIProvider(openAIKey, geminiKey, provider, model);
    }

    public void initStatusBar() {
        statusBar = new JLabel("Ready");
    }

    private void initUI() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (checkUnsavedChanges()) {
                    // Optionally call dispose() and exit
                    dispose();
                    System.exit(0);
                }
            }
        });
        setLayout(new BorderLayout(10, 10));

        createMenuBar();

        // Status bar
        statusBar.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        add(statusBar, BorderLayout.SOUTH);

        // A sidebar with advanced features (just placeholders / partial)
        SidebarPanel sidebarPanel = new SidebarPanel(this);
        sidebarPanel.setPreferredSize(new Dimension(250, 600));
        add(sidebarPanel, BorderLayout.WEST);

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

        JMenuItem exportPdfItem = new JMenuItem("Export as PDF");
        exportPdfItem.addActionListener(e -> exportAsPDF());
        fileMenu.add(exportPdfItem);


        recentFilesMenu = new JMenu("Recent Files");
        updateRecentFilesMenu();
        fileMenu.add(recentFilesMenu);

        JMenuItem commitVersionItem = new JMenuItem("Commit Version");
        commitVersionItem.addActionListener(e -> commitVersion());
        fileMenu.add(commitVersionItem);

        menuBar.add(fileMenu);

        JMenuItem settingsItem = new JMenuItem("Settings");
        settingsItem.addActionListener(_ -> new SettingsDialog(this, preferencesManager).setVisible(true));
        fileMenu.add(settingsItem);

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

    private void createTopPanel() {
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        topPanel.setBorder(BorderFactory.createEmptyBorder(5,5,5,5));

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);

        // Normal
        JButton normalButton = new JButton("Normal");
        normalButton.setToolTipText("Normal text");
        normalButton.addActionListener(e -> textEditor.setNormalText());
        normalButton.setFocusable(false);
        toolBar.add(normalButton);

        // Bold
        JButton boldButton = new JButton("B");
        boldButton.setFont(boldButton.getFont().deriveFont(Font.BOLD));
        boldButton.setToolTipText("Ctrl+B toggles bold");
        boldButton.addActionListener(e -> textEditor.applyBoldToSelectionOrToggle());
        boldButton.setFocusable(false);
        toolBar.add(boldButton);

        // Italic
        JButton italicButton = new JButton("I");
        italicButton.setFont(italicButton.getFont().deriveFont(Font.ITALIC));
        italicButton.setToolTipText("Ctrl+I toggles italic");
        italicButton.addActionListener(e -> textEditor.applyItalicToSelectionOrToggle());
        italicButton.setFocusable(false);
        toolBar.add(italicButton);

        // Underline
        JButton underlineButton = new JButton("U");
        underlineButton.setToolTipText("Ctrl+U toggles underline");
        underlineButton.addActionListener(e -> textEditor.applyUnderlineToSelectionOrToggle());
        underlineButton.setFocusable(false);
        toolBar.add(underlineButton);

        // Strikethrough
        JButton strikeButton = new JButton("S");
        strikeButton.setToolTipText("Strikethrough");
        strikeButton.addActionListener(e -> textEditor.applyStrikethrough());
        strikeButton.setFocusable(false);
        toolBar.add(strikeButton);

        toolBar.addSeparator();

        JButton h1Button = new JButton("H1");
        h1Button.setToolTipText("Heading 1");
        h1Button.addActionListener(e -> textEditor.setHeadingLevel(1));
        h1Button.setFocusable(false);
        toolBar.add(h1Button);

        JButton h2Button = new JButton("H2");
        h2Button.setToolTipText("Heading 2");
        h2Button.addActionListener(e -> textEditor.setHeadingLevel(2));
        h2Button.setFocusable(false);
        toolBar.add(h2Button);
        toolBar.addSeparator();

        // Ordered List (Numbered)
        JButton olButton = new JButton("OL");
        olButton.setToolTipText("Convert selection to numbered list");
        olButton.addActionListener(e -> textEditor.applyOrderedList());
        olButton.setFocusable(false);
        toolBar.add(olButton);

        // Unordered List (Bulleted)
        JButton ulButton = new JButton("UL");
        ulButton.setToolTipText("Convert selection to bulleted list");
        ulButton.addActionListener(e -> textEditor.applyUnorderedList());
        ulButton.setFocusable(false);
        toolBar.add(ulButton);

        toolBar.addSeparator();

        // Alignment: Left
        JButton alignLeftButton = new JButton("Left");
        alignLeftButton.addActionListener(e -> textEditor.applyAlignment(StyleConstants.ALIGN_LEFT));
        alignLeftButton.setToolTipText("Align left");
        alignLeftButton.setFocusable(false);
        toolBar.add(alignLeftButton);

        // Alignment: Center
        JButton alignCenterButton = new JButton("Center");
        alignCenterButton.addActionListener(e -> textEditor.applyAlignment(StyleConstants.ALIGN_CENTER));
        alignCenterButton.setToolTipText("Align center");
        alignCenterButton.setFocusable(false);
        toolBar.add(alignCenterButton);

        // Alignment: Right
        JButton alignRightButton = new JButton("Right");
        alignRightButton.addActionListener(e -> textEditor.applyAlignment(StyleConstants.ALIGN_RIGHT));
        alignRightButton.setToolTipText("Align right");
        alignRightButton.setFocusable(false);
        toolBar.add(alignRightButton);

        toolBar.addSeparator();

        // Existing combo for fonts
        JLabel fontLabel = new JLabel("Font:");
        toolBar.add(fontLabel);
        String[] basicFonts = new String[] { "Serif", "SansSerif", "Monospaced", "Georgia" };
        JComboBox<String> fontCombo = new JComboBox<>(basicFonts);
        fontCombo.setSelectedItem("Georgia");
        fontCombo.addActionListener(e -> {
            String chosen = (String) fontCombo.getSelectedItem();
            textEditor.setFontFamily(chosen);
        });
        toolBar.add(fontCombo);

        toolBar.addSeparator();
        JLabel sizeLabel = new JLabel("Size:");
        toolBar.add(sizeLabel);
        JComboBox<Integer> sizeCombo = new JComboBox<>(new Integer[]{12,14,16,18,24,36});
        sizeCombo.setSelectedItem(12);
        sizeCombo.addActionListener(e -> {
            Integer chosenSize = (Integer) sizeCombo.getSelectedItem();
            textEditor.setFontSize(chosenSize);
        });
        toolBar.add(sizeCombo);

        toolBar.addSeparator();

        topPanel.add(toolBar);
        add(topPanel, BorderLayout.NORTH);
    }

    private void createEditorPanel() {
        textEditor = new TextEditor(statusBar, preferencesManager);
        textEditor.setFont(new Font("Serif", Font.PLAIN, 12));

        // Key bindings
        InputMap im = textEditor.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = textEditor.getActionMap();

        KeyStroke escape = KeyStroke.getKeyStroke("ESCAPE");
        im.put(escape, "cancelAutoComplete");
        am.put("cancelAutoComplete", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                textEditor.cancelAutoComplete();
            }
        });

        // Ctrl+1..9 => insert that suggestion
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

        // B, I, U
        KeyStroke ctrlB = KeyStroke.getKeyStroke("control B");
        im.put(ctrlB, "boldSelection");
        am.put("boldSelection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                textEditor.applyBoldToSelectionOrToggle();
            }
        });

        KeyStroke ctrlI = KeyStroke.getKeyStroke("control I");
        im.put(ctrlI, "italicSelection");
        am.put("italicSelection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                textEditor.applyItalicToSelectionOrToggle();
            }
        });

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

        // Undo (Ctrl+Z)
        KeyStroke ctrlZ = KeyStroke.getKeyStroke("control Z");
        im.put(ctrlZ, "undoAction");
        am.put("undoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                textEditor.undo();
            }
        });

        // Redo (Ctrl+Shift+Z)
        KeyStroke ctrlShiftZ = KeyStroke.getKeyStroke("control shift Z");
        im.put(ctrlShiftZ, "redoAction");
        am.put("redoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                textEditor.redo();
            }
        });

    }

    public TextEditor getTextEditor() {
        return textEditor;
    }

    /**
     * Called by SettingsDialog to update the currentProvider
     * so suggestions can work immediately.
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

    File getDefaultDirectory() {
        String defPath = preferencesManager.getPreference("defaultPath", System.getProperty("user.dir"));
        return new File(defPath);
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

    private void handleNewDocument() {
        if (checkUnsavedChanges()) {
            newDocument();
        }
    }

    private void newDocument() {
        textEditor.resetCharacterCounts();
        textEditor.setText("");
        textEditor.markClean();
        currentFile = null;
        statusBar.setText("New document created.");
    }

    private void handleOpenDocument() {
        if (!checkUnsavedChanges()) {
            return;
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

    public void publicHandleOpenDocument(File file) {
        if (!checkUnsavedChanges()) {
            return;
        }
        currentFile = file;
        openDocument(currentFile);
        preferencesManager.addRecentFile(currentFile.getAbsolutePath());
        updateRecentFilesMenu();
    }

    /**
     * Return false if user chooses "Cancel" in the "Save changes?" dialog.
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

        // Parse out AI/HUMAN counts if present
        Pattern p = Pattern.compile("<!--\\s*AI_CHARS=(\\d+)\\s+HUMAN_CHARS=(\\d+)\\s*-->");
        Matcher m = p.matcher(entireText);
        int aiChars = 0, humanChars = 0;
        if (m.find()) {
            try {
                aiChars = Integer.parseInt(m.group(1));
                humanChars = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
            }
            entireText = m.replaceFirst("");
        }
        textEditor.setAICharCount(aiChars);
        textEditor.setHumanCharCount(humanChars);
        textEditor.setText(entireText);
        textEditor.markClean();

        // Count words from rendered text (strip HTML tags)
        int wordCount = countWordsFromHTML(entireText);

        statusBar.setText("Opened: " + file.getName() + " [AI=" + aiChars
                + ", Human=" + humanChars + ", Words=" + wordCount + "]");
    }

    public void saveDocument() {
        // If there is no file associated (new file), then prompt for a file
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
            // Insert the head snippet if not already present
            if (true) {
                int htmlIndex = textToSave.indexOf("<html");
                if (htmlIndex >= 0) {
                    int headInsertion = textToSave.indexOf(">", htmlIndex) + 1;
                    textToSave = textToSave.substring(0, headInsertion) + textToSave.substring(headInsertion);
                }
            }

            int aiCount = textEditor.getAICharCount();
            int humanCount = textEditor.getHumanCharCount();
            textToSave += "\n<!-- AI_CHARS=" + aiCount + " HUMAN_CHARS=" + humanCount + " -->\n";

            // Use the helper to count words from the rendered (tag-stripped) text.
            int wordCount = countWordsFromHTML(textToSave);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(currentFile))) {
                writer.write(textToSave);
                statusBar.setText("Saved: " + currentFile.getName()
                        + " [AI=" + aiCount + ", Human=" + humanCount + ", Words=" + wordCount + "]");
                preferencesManager.addRecentFile(currentFile.getAbsolutePath());
                updateRecentFilesMenu();
                textEditor.markClean();
            } catch (IOException ex) {
                ex.printStackTrace();
                statusBar.setText("Error saving file.");
            }
        }
    }

    private int countWordsFromHTML(String htmlText) {
        // Remove HTML tags
        String textOnly = htmlText.replaceAll("<[^>]+>", " ");
        textOnly = textOnly.replaceAll("&nbsp;", " ");
        textOnly = textOnly.trim();
        if (textOnly.isEmpty()) return 0;
        return textOnly.split("\\s+").length;
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

    private void exportAsPDF() {
        // Ensure we have text to export
        if (textEditor == null || textEditor.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No document to export.", "Export as PDF", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Prompt user for PDF filename
        JFileChooser chooser = new JFileChooser();
        chooser.setCurrentDirectory(getDefaultDirectory());
        chooser.setSelectedFile(new File("document.pdf"));
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File pdfFile = chooser.getSelectedFile();
        if (!pdfFile.getName().toLowerCase().endsWith(".pdf")) {
            pdfFile = new File(pdfFile.getAbsolutePath() + ".pdf");
        }

        try {
            // Grab the HTML content from the editor
            String htmlContent = textEditor.getText();

            // Convert the HTML string to PDF in-memory, writing to the chosen file
            HtmlConverter.convertToPdf(
                    new ByteArrayInputStream(htmlContent.getBytes(StandardCharsets.UTF_8)),
                    new FileOutputStream(pdfFile)
            );

            JOptionPane.showMessageDialog(this,
                    "Exported to PDF:\n" + pdfFile.getAbsolutePath(),
                    "Export Successful",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this,
                    "Error exporting PDF: " + ex.getMessage(),
                    "Export Failed",
                    JOptionPane.ERROR_MESSAGE);
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

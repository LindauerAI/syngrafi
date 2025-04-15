import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.concurrent.CompletableFuture;

import api.APIProvider;
import api.OpenAIProvider;
import api.GeminiProvider;

public class AIWritingAssistant extends JFrame {

    // UI Components
    private ImprovedTextEditor textEditor;
    private JLabel statusBar;
    private PreferencesManager preferencesManager;
    private APIProvider currentProvider;
    private File currentFile = null;
    
    public AIWritingAssistant() {
        super("AI Writing Assistant");
        preferencesManager = new PreferencesManager();
        initUI();
        loadPreferences();
    }
    
    private void initUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10,10));
        
        // Create status bar first so we can pass it to the text editor
        statusBar = new JLabel("Ready");
        statusBar.setBorder(BorderFactory.createEmptyBorder(5,10,5,10));
        add(statusBar, BorderLayout.SOUTH);
        
        createMenuBar();
        createTopPanel();
        createEditorPanel();
        
        setSize(1000,750);
        setLocationRelativeTo(null);
    }
    
    private void createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        
        JMenu fileMenu = new JMenu("File");
        
        JMenuItem newItem = new JMenuItem("New");
        newItem.addActionListener(e -> newDocument());
        fileMenu.add(newItem);
        
        JMenuItem openItem = new JMenuItem("Open");
        openItem.addActionListener(e -> openDocument());
        fileMenu.add(openItem);
        
        JMenuItem saveItem = new JMenuItem("Save");
        // Note: because we mapped Ctrl+S to suggestions, the user can still do File -> Save
        saveItem.addActionListener(e -> saveDocument());
        fileMenu.add(saveItem);
        
        JMenu recentMenu = new JMenu("Recent Files");
        updateRecentFilesMenu(recentMenu);
        fileMenu.add(recentMenu);
        
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
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> JOptionPane.showMessageDialog(this, 
                "AI Writing Assistant\nVersion 1.0\n\n" +
                "A professional writing tool that combines rich text editing with AI assistance\n" +
                "for composition, rewriting, and content generation.", 
                "About", JOptionPane.INFORMATION_MESSAGE));
        helpMenu.add(aboutItem);
        menuBar.add(helpMenu);
        
        setJMenuBar(menuBar);
    }
    
    private void updateRecentFilesMenu(JMenu recentMenu) {
        recentMenu.removeAll();
        for (String filePath : preferencesManager.getRecentFiles()) {
            JMenuItem item = new JMenuItem(filePath);
            item.addActionListener(e -> {
                currentFile = new File(filePath);
                openDocument(currentFile);
            });
            recentMenu.add(item);
        }
    }
    
    private void createTopPanel() {
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        topPanel.setBorder(BorderFactory.createEmptyBorder(5,5,5,5));
        
        // Formatting toolbar
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        
        JButton boldButton = new JButton("B");
        boldButton.setFont(boldButton.getFont().deriveFont(Font.BOLD));
        boldButton.addActionListener(e -> textEditor.toggleBold());
        toolBar.add(boldButton);
        
        JButton italicButton = new JButton("I");
        italicButton.setFont(italicButton.getFont().deriveFont(Font.ITALIC));
        italicButton.addActionListener(e -> textEditor.toggleItalic());
        toolBar.add(italicButton);
        
        JButton underlineButton = new JButton("U");
        underlineButton.addActionListener(e -> textEditor.toggleUnderline());
        toolBar.add(underlineButton);
        
        JButton h1Button = new JButton("H1");
        h1Button.addActionListener(e -> textEditor.setHeadingLevel(1));
        toolBar.add(h1Button);
        
        JButton h2Button = new JButton("H2");
        h2Button.addActionListener(e -> textEditor.setHeadingLevel(2));
        toolBar.add(h2Button);

        // A "Normal" button to revert selected text back to standard paragraph style
        JButton normalButton = new JButton("Normal");
        normalButton.addActionListener(e -> textEditor.setNormalText());
        toolBar.add(normalButton);
        
        topPanel.add(toolBar);
        
        // Manual autocomplete button as backup
        JButton manualAutocompleteButton = new JButton("Suggest");
        manualAutocompleteButton.setToolTipText("Manually trigger autocomplete suggestions");
        manualAutocompleteButton.addActionListener(e -> textEditor.triggerAutocomplete());
        topPanel.add(manualAutocompleteButton);
        
        add(topPanel, BorderLayout.NORTH);
    }
    
    private void createEditorPanel() {
        textEditor = new ImprovedTextEditor(statusBar);
        textEditor.setFont(new Font("Serif", Font.PLAIN, 16));
        
        // Key bindings
        InputMap im = textEditor.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = textEditor.getActionMap();
        
        // Let Ctrl+S trigger suggestions
        KeyStroke ctrlS = KeyStroke.getKeyStroke("control S");
        im.put(ctrlS, "triggerAutocompleteImmediate");
        am.put("triggerAutocompleteImmediate", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                textEditor.triggerAutocomplete();
            }
        });

        // For now, we remove the old Ctrl+1/2/3 approach that started new suggestions
        // If you want them to select suggestions 1,2,3, you can implement that logic
        // separately in ImprovedTextEditor with direct references to suggestions.
        
        JScrollPane scrollPane = new JScrollPane(textEditor);
        add(scrollPane, BorderLayout.CENTER);
    }
    
    /**
     * Called by SettingsDialog to update the currentProvider based on whichever
     * provider is selected. We pick the correct API key from the user’s settings.
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
    
    private void loadPreferences() {
        // We no longer store "apiKey" but store separate keys
        String openAIKey = preferencesManager.getPreference("apiKeyOpenAI", "");
        String geminiKey = preferencesManager.getPreference("apiKeyGemini", "");
        String provider = preferencesManager.getPreference("provider", "OpenAI");
        String model = preferencesManager.getPreference("model",
                provider.equals("OpenAI") ? "gpt-4o" : "gemini-2.0-flash");

        // Initialize the correct provider
        if ("OpenAI".equals(provider)) {
            currentProvider = new OpenAIProvider(openAIKey, model);
        } else {
            currentProvider = new GeminiProvider(geminiKey, model);
        }
        
        // Set the provider in the text editor
        // But only if we indeed have an editor
        if (textEditor != null) {
            textEditor.setAPIProvider(currentProvider);
        }
    }
    
    private void newDocument() {
        textEditor.setText(""); 
        currentFile = null;
        statusBar.setText("New document created.");
    }
    
    private void openDocument() {
        JFileChooser chooser = new JFileChooser();
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            currentFile = chooser.getSelectedFile();
            openDocument(currentFile);
            preferencesManager.addRecentFile(currentFile.getAbsolutePath());
            updateRecentFilesMenu((JMenu) getJMenuBar().getMenu(0).getMenuComponent(3));
        }
    }
    
    private void openDocument(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            textEditor.read(reader, null);
            statusBar.setText("Opened: " + file.getName());
        } catch (IOException ex) {
            ex.printStackTrace();
            statusBar.setText("Error opening file.");
        }
    }
    
    private void saveDocument() {
        if (currentFile == null) {
            JFileChooser chooser = new JFileChooser();
            int result = chooser.showSaveDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                currentFile = chooser.getSelectedFile();
                // Add .html extension if not present
                if (!currentFile.getName().toLowerCase().endsWith(".html")) {
                    currentFile = new File(currentFile.getAbsolutePath() + ".html");
                }
            } else {
                return;
            }
        }
        
        if (currentFile != null) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(currentFile))) {
                textEditor.write(writer);
                statusBar.setText("Saved: " + currentFile.getName());
                preferencesManager.addRecentFile(currentFile.getAbsolutePath());
                updateRecentFilesMenu((JMenu) getJMenuBar().getMenu(0).getMenuComponent(3));
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
        
        File versionDir = new File("versions");
        if (!versionDir.exists()) {
            versionDir.mkdir();
        }
        
        String fileName = "version_" + System.currentTimeMillis() + ".html";
        File versionFile = new File(versionDir, fileName);
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(versionFile))) {
            writer.write(text);
            statusBar.setText("Version committed: " + fileName);
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
            AIWritingAssistant assistant = new AIWritingAssistant();
            assistant.setVisible(true);
        });
    }
}

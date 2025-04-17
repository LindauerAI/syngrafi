import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class SettingsDialog extends JDialog {
    private PreferencesManager preferencesManager;

    // General tab components
    private JTextField openAIKeyField;
    private JTextField geminiKeyField;
    private JComboBox<String> providerComboBox;
    private JComboBox<String> modelComboBox;
    private JTextField numSuggestionsField;
    private JTextField defaultPathField;
    private JTextField autocompleteDelayField;
    private JComboBox<String> themeComboBox;

    // Autocomplete tab panel
    private JPanel autocompletePanel;
    private JScrollPane autocompleteScroll;

    // AI Settings tab components
    private JTextArea generalStylePromptArea;
    private JTextField maxLengthField;
    private JTextArea aiReferencesArea;
    private String initialThemeValue;

    // Rewrite Tab components
    private JTextArea defaultRewritePromptArea;

    public SettingsDialog(JFrame parent, PreferencesManager preferencesManager) {
        super(parent, "Settings", true);
        this.preferencesManager = preferencesManager;
        setSize(600, 450);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout());

        JTabbedPane tabbedPane = new JTabbedPane();
        JPanel generalTab = createGeneralTab();
        tabbedPane.addTab("General", generalTab);

        autocompletePanel = new JPanel();
        autocompletePanel.setLayout(new BoxLayout(autocompletePanel, BoxLayout.Y_AXIS));
        autocompleteScroll = new JScrollPane(autocompletePanel);
        tabbedPane.addTab("Autocomplete", autocompleteScroll);

        JPanel aiSettingsTab = createAISettingsTab();
        tabbedPane.addTab("AI Settings", aiSettingsTab);

        // Add Rewrite Tab
        JPanel rewriteTab = createRewriteTab();
        tabbedPane.addTab("Rewrite", rewriteTab);

        add(tabbedPane, BorderLayout.CENTER);

        // --- Bottom panel ---
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton applyButton = new JButton("Apply");
        applyButton.addActionListener(e -> saveSettings(false));
        bottomPanel.add(applyButton);
        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(e -> saveSettings(true));
        bottomPanel.add(saveButton);
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());
        bottomPanel.add(closeButton);
        add(bottomPanel, BorderLayout.SOUTH);

        loadPreferences();
        refreshAutocompletePrompts();
    }


    private JPanel createGeneralTab() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // OpenAI key
        panel.add(new JLabel("OpenAI API Key:"));
        openAIKeyField = new JTextField(20);
        panel.add(openAIKeyField);

        // Gemini key
        panel.add(new JLabel("Gemini API Key:"));
        geminiKeyField = new JTextField(20);
        panel.add(geminiKeyField);

        // Provider
        panel.add(new JLabel("Provider:"));
        providerComboBox = new JComboBox<>(new String[]{"OpenAI", "Google Gemini"});
        panel.add(providerComboBox);

        // Model
        panel.add(new JLabel("Model:"));
        modelComboBox = new JComboBox<>();
        panel.add(modelComboBox);

        // Number of suggestions
        panel.add(new JLabel("Num Suggestions:"));
        numSuggestionsField = new JTextField(4);
        panel.add(numSuggestionsField);

        // Default file path
        panel.add(new JLabel("Default Path:"));
        defaultPathField = new JTextField(20);
        panel.add(defaultPathField);

        // Autocomplete Delay
        panel.add(new JLabel("Autocomplete Delay (ms):"));
        autocompleteDelayField = new JTextField(6);
        panel.add(autocompleteDelayField);

        // Theme selection
        panel.add(new JLabel("Theme:"));
        themeComboBox = new JComboBox<>(new String[]{"System", "Light", "Dark"});
        panel.add(themeComboBox);

        providerComboBox.addActionListener(e -> updateModelComboBox());
        return panel;
    }

    private void updateModelComboBox() {
        String provider = (String) providerComboBox.getSelectedItem();
        modelComboBox.removeAllItems();
        if ("OpenAI".equals(provider)) {
            modelComboBox.addItem("gpt-4o");
            modelComboBox.addItem("gpt-3.5-turbo");
        } else {
            modelComboBox.addItem("gemini-2.0-flash");
            modelComboBox.addItem("gemini-2.0-flash-lite");
            modelComboBox.addItem("gemini-pro");
        }
    }

    private void loadPreferences() {
        String openAIKey = preferencesManager.getApiKey("apiKeyOpenAI");
        String geminiKey = preferencesManager.getApiKey("apiKeyGemini");
        String provider = preferencesManager.getPreference("provider", "OpenAI");
        String model = preferencesManager.getPreference("model",
                provider.equals("OpenAI") ? "gpt-4o" : "gemini-2.0-flash");
        String numSuggestions = preferencesManager.getPreference("numSuggestions", "3");
        String defaultPath = preferencesManager.getDefaultPath();
        String delay = preferencesManager.getPreference("autocompleteDelay", "1000");
        String theme = preferencesManager.getPreference("theme", "System");

        openAIKeyField.setText(openAIKey);
        geminiKeyField.setText(geminiKey);
        providerComboBox.setSelectedItem(provider);
        updateModelComboBox();
        modelComboBox.setSelectedItem(model);
        numSuggestionsField.setText(numSuggestions);
        defaultPathField.setText(defaultPath);
        autocompleteDelayField.setText(delay);
        themeComboBox.setSelectedItem(theme);
        initialThemeValue = theme;

        // Load AI settings (into fields created in createAISettingsTab)
        generalStylePromptArea.setText(preferencesManager.getPreference("generalStylePrompt", ""));
        maxLengthField.setText(preferencesManager.getPreference("autocompleteMaxLength", "200"));
        aiReferencesArea.setText(preferencesManager.getAIReferences());

        // Load Rewrite setting (into the new text area)
        defaultRewritePromptArea.setText(preferencesManager.getDefaultRewritePrompt());
    }

    /**
     * Builds the "Autocomplete" tab UI, showing text fields for each prompt.
     * We read numSuggestions from the user, then create that many text fields.
     */
    private void refreshAutocompletePrompts() {
        autocompletePanel.removeAll();

        int n;
        try {
            n = Integer.parseInt(numSuggestionsField.getText().trim());
        } catch (NumberFormatException ex) {
            n = 3;
        }
        if (n < 1) n = 1;
        if (n > 10) n = 10; // let's cap it at 10

        // For each suggestion index i, load from preferences
        for (int i = 1; i <= n; i++) {
            String storedPrompt = preferencesManager.getPreference(
                    "autocompletePrompt" + i,
                    defaultPromptForIndex(i)
            );
            JPanel row = new JPanel(new BorderLayout(5, 5));
            row.setBorder(BorderFactory.createEmptyBorder(5,5,5,5));
            row.add(new JLabel("Prompt #" + i + ":"), BorderLayout.WEST);

            JTextField promptField = new JTextField(storedPrompt);
            row.add(promptField, BorderLayout.CENTER);

            // store in the component's client property so we can fetch it later
            promptField.putClientProperty("promptIndex", i);
            autocompletePanel.add(row);
        }
        // Repack the panel
        autocompletePanel.revalidate();
        autocompletePanel.repaint();
    }

    /**
     * Default fallback prompts for each index, matching the old switch(variation) style
     */
    private String defaultPromptForIndex(int i) {
        switch (i) {
            case 1: return "Provide the most likely next phrase.";
            case 2: return "Provide an alternative direction.";
            default: return "Expand on this with additional detail.";
        }
    }

    private void saveSettings(boolean disposeAfter) {
        // Save general settings
        String openAIKey = openAIKeyField.getText().trim();
        String geminiKey = geminiKeyField.getText().trim();
        // Use setPreference for API keys as it handles routing to secureProperties
        preferencesManager.setPreference("apiKeyOpenAI", openAIKey);
        preferencesManager.setPreference("apiKeyGemini", geminiKey);

        String provider = (String) providerComboBox.getSelectedItem();
        String model = (String) modelComboBox.getSelectedItem();
        preferencesManager.setPreference("provider", provider);
        preferencesManager.setPreference("model", model);

        String numSuggestionsVal = numSuggestionsField.getText().trim();
        int n = 3;
        try { n = Math.max(1, Math.min(10, Integer.parseInt(numSuggestionsVal))); } catch (NumberFormatException ex) { n = 3; }
        preferencesManager.setPreference("numSuggestions", String.valueOf(n));

        String defaultPath = defaultPathField.getText().trim();
        if (defaultPath.isEmpty()) { defaultPath = System.getProperty("user.dir"); }
        // Use setPreference for default path
        preferencesManager.setPreference("defaultPath", defaultPath);

        String delayVal = autocompleteDelayField.getText().trim();
        int delay = 1000;
        try { delay = Integer.parseInt(delayVal); if (delay < 0) delay = 1000; } catch (NumberFormatException ex) { delay = 1000; }
        preferencesManager.setPreference("autocompleteDelay", String.valueOf(delay));

        String theme = (String) themeComboBox.getSelectedItem();
        preferencesManager.setPreference("theme", theme);

        // Save AI settings
        preferencesManager.setPreference("generalStylePrompt", generalStylePromptArea.getText().trim());
        String maxLengthStr = maxLengthField.getText().trim();
        int maxLengthVal = 200;
        try { maxLengthVal = Integer.parseInt(maxLengthStr); if (maxLengthVal < 10) maxLengthVal = 10; if (maxLengthVal > 1000) maxLengthVal = 1000; } catch (NumberFormatException ex) { maxLengthVal = 200; }
        preferencesManager.setPreference("autocompleteMaxLength", String.valueOf(maxLengthVal));
        preferencesManager.setAIReferences(aiReferencesArea.getText().trim()); // Uses specific setter

        // Save Rewrite setting (from the new text area)
        preferencesManager.setDefaultRewritePrompt(defaultRewritePromptArea.getText().trim());

        // Save autocomplete prompts
        Component[] rows = autocompletePanel.getComponents();
        for (Component comp : rows) {
            if (comp instanceof JPanel) {
                JPanel row = (JPanel) comp;
                Component[] rowComps = row.getComponents();
                for (Component c : rowComps) {
                    if (c instanceof JTextField) {
                        JTextField promptField = (JTextField) c;
                        Object idxObj = promptField.getClientProperty("promptIndex");
                        if (idxObj instanceof Integer) {
                            int idx = (Integer) idxObj;
                            preferencesManager.setPreference("autocompletePrompt" + idx,
                                    promptField.getText().trim());
                        }
                    }
                }
            }
        }

        preferencesManager.savePreferences();

        // --- Theme Change Check ---
        String newTheme = (String) themeComboBox.getSelectedItem();
        if (initialThemeValue != null && !initialThemeValue.equals(newTheme)) {
            JOptionPane.showMessageDialog(this,
                "Theme changes will take effect after restarting Syngrafi.",
                "Restart Required",
                JOptionPane.INFORMATION_MESSAGE);
            initialThemeValue = newTheme;
        }

        // Update the main frame's API provider
        if (getParent() instanceof Syngrafi) {
            ((Syngrafi) getParent()).updateAPIProvider(
                preferencesManager.getApiKey("apiKeyOpenAI"), // Fetch updated keys
                preferencesManager.getApiKey("apiKeyGemini"),
                provider,
                model
            );
        }

        if (disposeAfter) {
            dispose();
        }
    }

    private JPanel createAISettingsTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridwidth = 2;
        panel.add(new JLabel("General Style Prompt (optional):"), gbc);
        gbc.gridy++;
        gbc.weighty = 0.4;
        gbc.fill = GridBagConstraints.BOTH;
        generalStylePromptArea = new JTextArea(5, 40);
        generalStylePromptArea.setLineWrap(true);
        generalStylePromptArea.setWrapStyleWord(true);
        JScrollPane styleScrollPane = new JScrollPane(generalStylePromptArea);
        panel.add(styleScrollPane, gbc);
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridy++;
        gbc.gridwidth = 1;
        panel.add(new JLabel("Max Suggestion Length (chars):"), gbc);
        gbc.gridx = 1;
        maxLengthField = new JTextField(5);
        panel.add(maxLengthField, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        panel.add(new JLabel("AI Reference Examples (one per line):", SwingConstants.LEFT), gbc);
        gbc.gridy++;
        gbc.weighty = 0.6;
        gbc.fill = GridBagConstraints.BOTH;
        aiReferencesArea = new JTextArea(8, 40);
        aiReferencesArea.setLineWrap(true);
        aiReferencesArea.setWrapStyleWord(true);
        JScrollPane referenceScrollPane = new JScrollPane(aiReferencesArea);
        panel.add(referenceScrollPane, gbc);

        return panel;
    }

    private JPanel createRewriteTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        panel.add(new JLabel("Default Rewrite Prompt:", SwingConstants.LEFT), BorderLayout.NORTH);

        defaultRewritePromptArea = new JTextArea(10, 40);
        defaultRewritePromptArea.setLineWrap(true);
        defaultRewritePromptArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(defaultRewritePromptArea);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

}

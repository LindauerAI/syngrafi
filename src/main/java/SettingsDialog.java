import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.*;

public class SettingsDialog extends JDialog {
    private PreferencesManager preferencesManager;

    // General tab components
    private JTextField openAIKeyField;
    private JTextField geminiKeyField;
    private JComboBox<String> providerComboBox;
    private JComboBox<String> modelComboBox;
    private JTextField numAutocompleteSuggestionsField;
    private JTextField defaultPathField;
    private JTextField autocompleteDelayField;
    private JComboBox<String> themeComboBox;
    private JCheckBox disableAiCheckbox;

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
    private JTextField numRewriteSuggestionsField;

    // AI Control tab components
    private JPanel aiControlPanel;
    private JScrollPane aiControlScroll;

    public SettingsDialog(JFrame parent, PreferencesManager preferencesManager) {
        super(parent, "Settings", true);
        this.preferencesManager = preferencesManager;
        setSize(600, 500);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout());

        JTabbedPane tabbedPane = new JTabbedPane();
        JPanel generalTab = createGeneralTab();
        tabbedPane.addTab("General", generalTab);

        JPanel autocompleteTab = createAutocompleteTab();
        tabbedPane.addTab("Autocomplete", autocompleteTab);

        JPanel aiSettingsTab = createAISettingsTab();
        tabbedPane.addTab("AI Settings", aiSettingsTab);

        JPanel rewriteTab = createRewriteTab();
        tabbedPane.addTab("Rewrite", rewriteTab);

        JPanel aiControlTab = createAIControlTab();
        tabbedPane.addTab("AI Routing", aiControlTab);

        tabbedPane.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                JTabbedPane source = (JTabbedPane) e.getSource();
                if ("AI Control".equals(source.getTitleAt(source.getSelectedIndex()))) {
                    refreshAIControlPanel();
                }
            }
        });

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
    }

    private JPanel createGeneralTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST; gbc.insets = new Insets(2, 5, 2, 5);

        // Row 0: OpenAI Key
        panel.add(new JLabel("OpenAI API Key:"), gbc);
        gbc.gridx++; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        openAIKeyField = new JTextField(20);
        panel.add(openAIKeyField, gbc);

        // Row 1: Gemini Key
        gbc.gridx = 0; gbc.gridy++; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
        panel.add(new JLabel("Gemini API Key:"), gbc);
        gbc.gridx++; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        geminiKeyField = new JTextField(20);
        panel.add(geminiKeyField, gbc);

        // Row 2: Provider
        gbc.gridx = 0; gbc.gridy++; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
        panel.add(new JLabel("API Provider:"), gbc);
        gbc.gridx++; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        providerComboBox = new JComboBox<>(new String[]{"OpenAI", "Google Gemini"});
        providerComboBox.addActionListener(e -> updateModelComboBox());
        panel.add(providerComboBox, gbc);

        // Row 3: Model
        gbc.gridx = 0; gbc.gridy++; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
        panel.add(new JLabel("Model:"), gbc);
        gbc.gridx++; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        modelComboBox = new JComboBox<>();
        panel.add(modelComboBox, gbc);

        // Row 4: Default Path
        gbc.gridx = 0; gbc.gridy++; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
        panel.add(new JLabel("Default Path:"), gbc);
        gbc.gridx++; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        defaultPathField = new JTextField(20);
        panel.add(defaultPathField, gbc);
        // Add browse button? (Optional)

        // Row 5: Theme
        gbc.gridx = 0; gbc.gridy++; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
        panel.add(new JLabel("Theme:"), gbc);
        gbc.gridx++; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        themeComboBox = new JComboBox<>(new String[]{"System", "Light", "Dark"});
        panel.add(themeComboBox, gbc);
        
        // Row 6: AI Cutoff Checkbox
        gbc.gridx = 0; gbc.gridy++; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        disableAiCheckbox = new JCheckBox("Disable ALL AI Features (Autocomplete, Rewrite, etc.)");
        panel.add(disableAiCheckbox, gbc);

        return panel;
    }

    private JPanel createAutocompleteTab() {
        JPanel containerPanel = new JPanel(new BorderLayout(10, 10));
        containerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Panel for settings at the top
        JPanel topSettingsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topSettingsPanel.add(new JLabel("Autocomplete Delay (ms):"));
        autocompleteDelayField = new JTextField(6);
        topSettingsPanel.add(autocompleteDelayField);
        topSettingsPanel.add(Box.createHorizontalStrut(20)); // Spacer
        topSettingsPanel.add(new JLabel("Number of Suggestions:"));
        numAutocompleteSuggestionsField = new JTextField(4);
        numAutocompleteSuggestionsField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                refreshAutocompletePrompts();
            }
        });
        topSettingsPanel.add(numAutocompleteSuggestionsField);
        containerPanel.add(topSettingsPanel, BorderLayout.NORTH);

        // Panel for the dynamic prompts list
        autocompletePanel = new JPanel();
        autocompletePanel.setLayout(new BoxLayout(autocompletePanel, BoxLayout.Y_AXIS));
        autocompleteScroll = new JScrollPane(autocompletePanel);
        containerPanel.add(autocompleteScroll, BorderLayout.CENTER);

        return containerPanel;
    }

    private void updateModelComboBox() {
        String provider = (String) providerComboBox.getSelectedItem();
        modelComboBox.removeAllItems();
        if ("OpenAI".equals(provider)) {
            modelComboBox.addItem("gpt-4o");
            modelComboBox.addItem("gpt-4.1");
            modelComboBox.addItem("gpt-4.1-mini");
            modelComboBox.addItem("gpt-4.1-nano");
        } else {
            modelComboBox.addItem("gemini-2.0-flash-lite");
            modelComboBox.addItem("gemini-2.0-flash");
        }
    }

    private void loadPreferences() {
        openAIKeyField.setText(preferencesManager.getApiKey("apiKeyOpenAI"));
        geminiKeyField.setText(preferencesManager.getApiKey("apiKeyGemini"));
        String provider = preferencesManager.getPreference("provider", "OpenAI");
        providerComboBox.setSelectedItem(provider);
        updateModelComboBox();
        String model = preferencesManager.getPreference("model", provider.equals("OpenAI") ? "gpt-4o" : "gemini-1.5-flash");
        modelComboBox.setSelectedItem(model);
        defaultPathField.setText(preferencesManager.getDefaultPath());
        String theme = preferencesManager.getPreference("theme", "System");
        themeComboBox.setSelectedItem(theme);
        initialThemeValue = theme;
        disableAiCheckbox.setSelected(preferencesManager.isAiFeaturesDisabled());

        autocompleteDelayField.setText(preferencesManager.getPreference("autocompleteDelay", "1000"));
        numAutocompleteSuggestionsField.setText(preferencesManager.getPreference("numSuggestions", "3"));
        refreshAutocompletePrompts();

        generalStylePromptArea.setText(preferencesManager.getPreference("generalStylePrompt", ""));
        maxLengthField.setText(preferencesManager.getPreference("autocompleteMaxLength", "200"));
        aiReferencesArea.setText(preferencesManager.getAIReferences());

        numRewriteSuggestionsField.setText(String.valueOf(preferencesManager.getNumRewriteSuggestions()));
        defaultRewritePromptArea.setText(preferencesManager.getDefaultRewritePrompt());
    }

    private void refreshAutocompletePrompts() {
        if (autocompletePanel == null) return;
        autocompletePanel.removeAll();
        int n;
        try {
            n = Integer.parseInt(numAutocompleteSuggestionsField.getText().trim());
        } catch (NumberFormatException | NullPointerException ex) {
            n = 3;
        }
        n = Math.max(1, Math.min(10, n));
        numAutocompleteSuggestionsField.setText(String.valueOf(n));

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
            promptField.putClientProperty("promptIndex", i);
            autocompletePanel.add(row);
        }
        autocompletePanel.revalidate();
        autocompletePanel.repaint();
        if (autocompleteScroll != null) {
            autocompleteScroll.revalidate();
            autocompleteScroll.repaint();
        }
    }

    private String defaultPromptForIndex(int i) {
        switch (i) {
            case 1: return "Provide the most likely next phrase.";
            case 2: return "Provide an alternative direction.";
            default: return "Expand on this with additional detail.";
        }
    }

    private void saveSettings(boolean disposeAfter) {
        preferencesManager.setPreference("apiKeyOpenAI", openAIKeyField.getText().trim());
        preferencesManager.setPreference("apiKeyGemini", geminiKeyField.getText().trim());
        String provider = (String) providerComboBox.getSelectedItem();
        preferencesManager.setPreference("provider", provider);
        String model = (String) modelComboBox.getSelectedItem();
        preferencesManager.setPreference("model", model);
        String defaultPath = defaultPathField.getText().trim();
        if (defaultPath.isEmpty()) { defaultPath = System.getProperty("user.dir"); }
        preferencesManager.setPreference("defaultPath", defaultPath);
        String theme = (String) themeComboBox.getSelectedItem();
        preferencesManager.setPreference("theme", theme);
        preferencesManager.setAiFeaturesDisabled(disableAiCheckbox.isSelected());

        int delay = 1000;
        try { delay = Integer.parseInt(autocompleteDelayField.getText().trim()); if (delay < 0) delay = 1000; } catch (NumberFormatException ex) { delay = 1000; }
        preferencesManager.setPreference("autocompleteDelay", String.valueOf(delay));
        int numAutoSuggest = 3;
        try { numAutoSuggest = Math.max(1, Math.min(10, Integer.parseInt(numAutocompleteSuggestionsField.getText().trim()))); } catch (NumberFormatException ex) { numAutoSuggest = 3; }
        preferencesManager.setPreference("numSuggestions", String.valueOf(numAutoSuggest));
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

        preferencesManager.setPreference("generalStylePrompt", generalStylePromptArea.getText().trim());
        int maxLengthVal = 200;
        try { maxLengthVal = Integer.parseInt(maxLengthField.getText().trim()); if (maxLengthVal < 10) maxLengthVal = 10; if (maxLengthVal > 1000) maxLengthVal = 1000; } catch (NumberFormatException ex) { maxLengthVal = 200; }
        preferencesManager.setPreference("autocompleteMaxLength", String.valueOf(maxLengthVal));
        preferencesManager.setAIReferences(aiReferencesArea.getText().trim());

        int numRewriteSuggest = 2;
        try { numRewriteSuggest = Math.max(1, Math.min(5, Integer.parseInt(numRewriteSuggestionsField.getText().trim()))); } catch (NumberFormatException ex) { numRewriteSuggest = 2; }
        preferencesManager.setNumRewriteSuggestions(numRewriteSuggest);
        preferencesManager.setDefaultRewritePrompt(defaultRewritePromptArea.getText().trim());

        // Save AI Control models
        if (aiControlPanel != null) {
            for (Component comp : aiControlPanel.getComponents()) {
                if (comp instanceof JPanel) {
                    JPanel row = (JPanel) comp;
                    for (Component c : row.getComponents()) {
                        if (c instanceof JComboBox) {
                            JComboBox<?> combo = (JComboBox<?>) c;
                            Object feature = combo.getClientProperty("feature");
                            Object idxObj = combo.getClientProperty("index");
                            if (feature instanceof String && idxObj instanceof Integer) {
                                String feat = (String) feature;
                                int idx = (Integer) idxObj;
                                String val = (String) combo.getSelectedItem();
                                if ("autocomplete".equals(feat)) {
                                    preferencesManager.setPreference("autocompleteModel" + idx, val);
                                } else if ("rewrite".equals(feat)) {
                                    preferencesManager.setPreference("rewriteModel" + idx, val);
                                }
                            }
                        }
                    }
                }
            }
        }

        preferencesManager.savePreferences();

        String newTheme = (String) themeComboBox.getSelectedItem();
        if (initialThemeValue != null && !initialThemeValue.equals(newTheme)) {
            JOptionPane.showMessageDialog(this,
                "Theme changes will take effect after restarting Syngrafi.",
                "Restart Required",
                JOptionPane.INFORMATION_MESSAGE);
            initialThemeValue = newTheme;
        }

        if (getParent() instanceof Syngrafi) {
            Syngrafi mainFrame = (Syngrafi) getParent();
            mainFrame.updateAPIProvider(
                preferencesManager.getApiKey("apiKeyOpenAI"),
                preferencesManager.getApiKey("apiKeyGemini"),
                provider,
                model
            );
            mainFrame.applySettings();
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
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST; gbc.insets = new Insets(5, 5, 5, 5);

        panel.add(new JLabel("Number of Rewrite Suggestions:"), gbc);
        gbc.gridx++; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        numRewriteSuggestionsField = new JTextField(4);
        panel.add(numRewriteSuggestionsField, gbc);
        gbc.weightx = 0.0;

        gbc.gridx = 0; gbc.gridy++; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel("Default Rewrite Prompt:", SwingConstants.LEFT), gbc);
        gbc.gridy++; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        defaultRewritePromptArea = new JTextArea(10, 40);
        defaultRewritePromptArea.setLineWrap(true);
        defaultRewritePromptArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(defaultRewritePromptArea);
        panel.add(scrollPane, gbc);

        return panel;
    }

    private JPanel createAIControlTab() {
        JPanel container = new JPanel(new BorderLayout(10, 10));
        container.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        aiControlPanel = new JPanel();
        aiControlPanel.setLayout(new BoxLayout(aiControlPanel, BoxLayout.Y_AXIS));
        aiControlScroll = new JScrollPane(aiControlPanel);
        container.add(aiControlScroll, BorderLayout.CENTER);
        refreshAIControlPanel();
        return container;
    }

    private void refreshAIControlPanel() {
        if (aiControlPanel == null) return;
        aiControlPanel.removeAll();
        // Autocomplete Models section
        JLabel autoHeader = new JLabel("Autocomplete Models:");
        autoHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        aiControlPanel.add(autoHeader);
        int numAuto;
        try {
            numAuto = Math.max(1, Math.min(10, Integer.parseInt(numAutocompleteSuggestionsField.getText().trim())));
        } catch (Exception ex) {
            numAuto = 3;
        }
        for (int i = 1; i <= numAuto; i++) {
            String storedModel = preferencesManager.getPreference("autocompleteModel" + i,
                    (String) modelComboBox.getSelectedItem());
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
            row.add(new JLabel("Suggestion #" + i + ":"));
            JComboBox<String> modelSelect = new JComboBox<>(new String[]{
                    "gpt-4o", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano",
                    "gemini-2.0-flash-lite", "gemini-2.0-flash"});
            modelSelect.setSelectedItem(storedModel);
            modelSelect.putClientProperty("feature", "autocomplete");
            modelSelect.putClientProperty("index", i);
            row.add(modelSelect);
            aiControlPanel.add(row);
        }
        // Rewrite Model section
        aiControlPanel.add(Box.createVerticalStrut(10));
        JLabel rewriteHeader = new JLabel("Rewrite Models:");
        rewriteHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        aiControlPanel.add(rewriteHeader);
        String storedRewriteModel = preferencesManager.getPreference("rewriteModel1",
                (String) modelComboBox.getSelectedItem());
        JPanel rewriteRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        rewriteRow.add(new JLabel("Rewrite Model:"));
        JComboBox<String> rewriteSelect = new JComboBox<>(new String[]{
                "gpt-4o", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano",
                "gemini-2.0-flash-lite", "gemini-2.0-flash"});
        rewriteSelect.setSelectedItem(storedRewriteModel);
        rewriteSelect.putClientProperty("feature", "rewrite");
        rewriteSelect.putClientProperty("index", 1);
        rewriteRow.add(rewriteSelect);
        aiControlPanel.add(rewriteRow);
        aiControlPanel.revalidate();
        aiControlPanel.repaint();
    }
}

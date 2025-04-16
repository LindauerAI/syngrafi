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
    private String initialThemeValue; // Store initial theme value

    public SettingsDialog(JFrame parent, PreferencesManager preferencesManager) {
        super(parent, "Settings", true);
        this.preferencesManager = preferencesManager;
        setSize(600, 400);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout());

        JTabbedPane tabbedPane = new JTabbedPane();
        JPanel generalTab = createGeneralTab();
        tabbedPane.addTab("General", generalTab);

        autocompletePanel = new JPanel();
        autocompletePanel.setLayout(new BoxLayout(autocompletePanel, BoxLayout.Y_AXIS));
        autocompleteScroll = new JScrollPane(autocompletePanel);
        tabbedPane.addTab("Autocomplete", autocompleteScroll);

        // Add AI Settings Tab
        JPanel aiSettingsTab = createAISettingsTab();
        tabbedPane.addTab("AI Settings", aiSettingsTab);

        add(tabbedPane, BorderLayout.CENTER);

        // --- New bottom panel with Apply + Close ---
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton applyButton = new JButton("Apply");
        applyButton.addActionListener(e -> {
            // Save but keep dialog open
            saveSettings(false); // pass false to not dispose
        });
        bottomPanel.add(applyButton);

        // Save and close
        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(e -> {
            // Save and then close
            saveSettings(true); // pass true to close after saving
        });
        bottomPanel.add(saveButton);

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> {
            // Save and then dispose
            saveSettings(true); // pass true to close after saving
        });
        bottomPanel.add(closeButton);

        add(bottomPanel, BorderLayout.SOUTH);

        loadPreferences();
        refreshAutocompletePrompts();

        saveSettings(true);
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
        String openAIKey = preferencesManager.getPreference("apiKeyOpenAI", "");
        String geminiKey = preferencesManager.getPreference("apiKeyGemini", "");
        String provider = preferencesManager.getPreference("provider", "OpenAI");
        String model = preferencesManager.getPreference("model",
                provider.equals("OpenAI") ? "gpt-4o" : "gemini-2.0-flash");
        String numSuggestions = preferencesManager.getPreference("numSuggestions", "3");
        String defaultPath = preferencesManager.getPreference("defaultPath", System.getProperty("user.dir"));
        String delay = preferencesManager.getPreference("autocompleteDelay", "600");
        String theme = preferencesManager.getPreference("theme", "System");
        String stylePrompt = preferencesManager.getPreference("generalStylePrompt", "");
        String maxLength = preferencesManager.getPreference("autocompleteMaxLength", "100");

        openAIKeyField.setText(openAIKey);
        geminiKeyField.setText(geminiKey);
        providerComboBox.setSelectedItem(provider);
        updateModelComboBox();
        modelComboBox.setSelectedItem(model);
        numSuggestionsField.setText(numSuggestions);
        defaultPathField.setText(defaultPath);
        autocompleteDelayField.setText(delay);
        themeComboBox.setSelectedItem(theme);
        generalStylePromptArea.setText(stylePrompt);
        maxLengthField.setText(maxLength);
        initialThemeValue = theme; // Store the initially loaded theme
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
        String openAIKey = openAIKeyField.getText().trim();
        String geminiKey = geminiKeyField.getText().trim();
        String provider = (String) providerComboBox.getSelectedItem();
        String model = (String) modelComboBox.getSelectedItem();
        String numSuggestionsVal = numSuggestionsField.getText().trim();
        if (numSuggestionsVal.isEmpty()) {
            numSuggestionsVal = "3";
        }
        int n;
        try {
            n = Math.max(1, Math.min(10, Integer.parseInt(numSuggestionsVal)));
        } catch (NumberFormatException ex) {
            n = 3;
        }

        String defaultPath = defaultPathField.getText().trim();
        if (defaultPath.isEmpty()) {
            defaultPath = System.getProperty("user.dir");
        }

        String delayVal = autocompleteDelayField.getText().trim();
        int delay;
        try {
            delay = Integer.parseInt(delayVal);
            if (delay < 0) delay = 600;
        } catch (NumberFormatException ex) {
            delay = 600;
        }

        String theme = (String) themeComboBox.getSelectedItem();

        preferencesManager.setPreference("apiKeyOpenAI", openAIKey);
        preferencesManager.setPreference("apiKeyGemini", geminiKey);
        preferencesManager.setPreference("provider", provider);
        preferencesManager.setPreference("model", model);
        preferencesManager.setPreference("numSuggestions", String.valueOf(n));
        preferencesManager.setPreference("defaultPath", defaultPath);
        preferencesManager.setPreference("autocompleteDelay", String.valueOf(delay));
        preferencesManager.setPreference("theme", theme);
        preferencesManager.setPreference("generalStylePrompt", generalStylePromptArea.getText().trim());

        // Validate and save max length
        String maxLengthStr = maxLengthField.getText().trim();
        int maxLengthVal = 100; // default
        try {
            maxLengthVal = Integer.parseInt(maxLengthStr);
            if (maxLengthVal < 10) maxLengthVal = 10; // Ensure a minimum reasonable length
            if (maxLengthVal > 1000) maxLengthVal = 1000; // Set a reasonable upper limit
        } catch (NumberFormatException ex) {
            // Keep default if input is invalid
        }
        preferencesManager.setPreference("autocompleteMaxLength", String.valueOf(maxLengthVal)); // Save max length

        // Save user-defined prompts from the Autocomplete tab
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
            initialThemeValue = newTheme; // Update initial value to prevent re-prompting unless changed again
        }
        // --- End Theme Change Check ---

        // Update the main frame's API provider in case keys or model changed
        if (getParent() instanceof Syngrafi) {
            ((Syngrafi) getParent()).updateAPIProvider(openAIKey, geminiKey, provider, model);
        }

        if (disposeAfter) {
            dispose();
        }
    }

    private JPanel createAISettingsTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        panel.add(new JLabel("General Style Prompt (optional):"), BorderLayout.NORTH);

        generalStylePromptArea = new JTextArea(5, 40);
        generalStylePromptArea.setLineWrap(true);
        generalStylePromptArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(generalStylePromptArea);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Add max length setting
        JPanel bottomAISettings = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottomAISettings.add(new JLabel("Max Suggestion Length (chars):"));
        maxLengthField = new JTextField(5);
        bottomAISettings.add(maxLengthField);
        panel.add(bottomAISettings, BorderLayout.SOUTH);

        return panel;
    }

}

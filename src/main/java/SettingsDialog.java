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

    // Autocomplete tab panel
    private JPanel autocompletePanel;
    private JScrollPane autocompleteScroll;

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

        openAIKeyField.setText(openAIKey);
        geminiKeyField.setText(geminiKey);
        providerComboBox.setSelectedItem(provider);
        updateModelComboBox();
        modelComboBox.setSelectedItem(model);
        numSuggestionsField.setText(numSuggestions);
        defaultPathField.setText(defaultPath);
        autocompleteDelayField.setText(delay);
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

        preferencesManager.setPreference("apiKeyOpenAI", openAIKey);
        preferencesManager.setPreference("apiKeyGemini", geminiKey);
        preferencesManager.setPreference("provider", provider);
        preferencesManager.setPreference("model", model);
        preferencesManager.setPreference("numSuggestions", String.valueOf(n));
        preferencesManager.setPreference("defaultPath", defaultPath);
        preferencesManager.setPreference("autocompleteDelay", String.valueOf(delay));

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

        // Update the main frame’s API provider in case keys or model changed
        if (getParent() instanceof Syngrafi) {
            ((Syngrafi) getParent()).updateAPIProvider(openAIKey, geminiKey, provider, model);
        }

        if (disposeAfter) {
            dispose();
        }
    }

}

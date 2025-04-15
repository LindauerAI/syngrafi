import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class SettingsDialog extends JDialog {
    private JTextField openAIKeyField;
    private JTextField geminiKeyField;
    private JComboBox<String> providerComboBox;
    private JComboBox<String> modelComboBox;
    private JTextField numSuggestionsField;
    private JTextField defaultPathField;

    private JTextField autocompleteDelayField; // NEW

    private PreferencesManager preferencesManager;

    public SettingsDialog(JFrame parent, PreferencesManager preferencesManager) {
        super(parent, "Settings", true);
        this.preferencesManager = preferencesManager;
        initUI();
        loadPreferences();
        pack();
        setLocationRelativeTo(parent);
    }

    private void initUI() {
        setLayout(new BorderLayout());

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
        add(panel, BorderLayout.CENTER);

        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(e -> saveSettings());
        add(saveButton, BorderLayout.SOUTH);
    }

    private void loadPreferences() {
        String openAIKey = preferencesManager.getPreference("apiKeyOpenAI", "");
        String geminiKey = preferencesManager.getPreference("apiKeyGemini", "");
        String provider = preferencesManager.getPreference("provider", "OpenAI");
        String model = preferencesManager.getPreference("model",
                provider.equals("OpenAI") ? "gpt-4o" : "gemini-2.0-flash");
        String numSuggestions = preferencesManager.getPreference("numSuggestions", "3");
        String defaultPath = preferencesManager.getPreference("defaultPath", System.getProperty("user.dir"));
        String delay = preferencesManager.getPreference("autocompleteDelay", "1000");

        openAIKeyField.setText(openAIKey);
        geminiKeyField.setText(geminiKey);
        providerComboBox.setSelectedItem(provider);
        updateModelComboBox();
        modelComboBox.setSelectedItem(model);
        numSuggestionsField.setText(numSuggestions);
        defaultPathField.setText(defaultPath);
        autocompleteDelayField.setText(delay);
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

    private void saveSettings() {
        String openAIKey = openAIKeyField.getText().trim();
        String geminiKey = geminiKeyField.getText().trim();
        String provider = (String) providerComboBox.getSelectedItem();
        String model = (String) modelComboBox.getSelectedItem();
        String numSuggestionsVal = numSuggestionsField.getText().trim();
        if (numSuggestionsVal.isEmpty()) {
            numSuggestionsVal = "3";
        }
        int n = 3;
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
        int delay = 100;
        try {
            delay = Integer.parseInt(delayVal);
            if (delay < 0) delay = 100;
        } catch (NumberFormatException ex) {
            delay = 100;
        }

        preferencesManager.setPreference("apiKeyOpenAI", openAIKey);
        preferencesManager.setPreference("apiKeyGemini", geminiKey);
        preferencesManager.setPreference("provider", provider);
        preferencesManager.setPreference("model", model);
        preferencesManager.setPreference("numSuggestions", String.valueOf(n));
        preferencesManager.setPreference("defaultPath", defaultPath);
        preferencesManager.setPreference("autocompleteDelay", String.valueOf(delay));
        preferencesManager.savePreferences();

        if (getParent() instanceof Syngrafi) {
            ((Syngrafi) getParent()).updateAPIProvider(
                    openAIKey, geminiKey, provider, model
            );
        }
        dispose();
    }
}

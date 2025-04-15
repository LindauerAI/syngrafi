import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class SettingsDialog extends JDialog {
    private JTextField openAIKeyField;
    private JTextField geminiKeyField;
    private JComboBox<String> providerComboBox;
    private JComboBox<String> modelComboBox;
    private PreferencesManager preferencesManager;

    public SettingsDialog(JFrame parent, PreferencesManager preferencesManager) {
        super(parent, "API Settings", true);
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

        providerComboBox.addActionListener(e -> updateModelComboBox());

        add(panel, BorderLayout.CENTER);

        JButton saveButton = new JButton("Save Settings");
        saveButton.addActionListener(e -> saveSettings());
        add(saveButton, BorderLayout.SOUTH);
    }

    private void loadPreferences() {
        String openAIKey = preferencesManager.getPreference("apiKeyOpenAI", "");
        String geminiKey = preferencesManager.getPreference("apiKeyGemini", "");
        String provider = preferencesManager.getPreference("provider", "OpenAI");
        String model = preferencesManager.getPreference("model",
                provider.equals("OpenAI") ? "gpt-4o" : "gemini-2.0-flash");

        openAIKeyField.setText(openAIKey);
        geminiKeyField.setText(geminiKey);
        providerComboBox.setSelectedItem(provider);
        updateModelComboBox();
        modelComboBox.setSelectedItem(model);
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

        // Save them all:
        preferencesManager.setPreference("apiKeyOpenAI", openAIKey);
        preferencesManager.setPreference("apiKeyGemini", geminiKey);
        preferencesManager.setPreference("provider", provider);
        preferencesManager.setPreference("model", model);
        preferencesManager.savePreferences();

        // Inform the main UI so that it updates the active provider
        if (getParent() instanceof AIWritingAssistant) {
            ((AIWritingAssistant) getParent()).updateAPIProvider(
                    openAIKey, geminiKey, provider, model
            );
        }
        dispose();
    }
}

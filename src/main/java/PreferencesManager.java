import java.io.*;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PreferencesManager {
    private static final String PREFS_FILE_NAME = "syngrafi_prefs.properties";
    private Properties properties = new Properties();
    private Path prefsFilePath;

    public PreferencesManager() {
        // Store preferences in user's home directory under .syngrafi
        String userHome = System.getProperty("user.home");
        Path prefsDir = Paths.get(userHome, ".syngrafi");
        try {
            Files.createDirectories(prefsDir);
        } catch (IOException e) {
            System.err.println("Could not create preferences directory: " + prefsDir);
            // Fallback or handle error appropriately
        }
        prefsFilePath = prefsDir.resolve(PREFS_FILE_NAME);
    }

    public void loadPreferences() {
        if (Files.exists(prefsFilePath)) {
            try (FileInputStream fis = new FileInputStream(prefsFilePath.toFile())) {
                properties.load(fis);
            } catch (IOException e) {
                System.err.println("Error loading preferences from " + prefsFilePath);
            }
        }
        // Ensure default values if keys are missing
        properties.putIfAbsent("apiKeyOpenAI", "");
        properties.putIfAbsent("apiKeyGemini", "");
        properties.putIfAbsent("provider", "OpenAI");
        properties.putIfAbsent("model", "gpt-4o"); // Default depends on provider, adjust if needed
        properties.putIfAbsent("autocompleteDelay", "600");
        properties.putIfAbsent("numSuggestions", "3");
        properties.putIfAbsent("theme", "System"); // Add default theme preference
        properties.putIfAbsent("generalStylePrompt", ""); // Add default for general style prompt
        properties.putIfAbsent("autocompleteMaxLength", "200"); // Add default for max length
    }

    public void savePreferences() {
        try (FileOutputStream fos = new FileOutputStream(prefsFilePath.toFile())) {
            properties.store(fos, "Syngrafi Application Preferences");
        } catch (IOException e) {
            System.err.println("Error saving preferences to " + prefsFilePath);
        }
    }

    public String getPreference(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public void setPreference(String key, String value) {
        properties.setProperty(key, value);
    }

    public List<String> getRecentFiles() {
        String files = properties.getProperty("recentFiles", "");
        if (files.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(files.split(";")));
    }

    public void addRecentFile(String filePath) {
        List<String> recent = getRecentFiles();
        recent.remove(filePath); // remove duplicate if exists
        recent.add(0, filePath); // add new file to the beginning
        if (recent.size() > 5) {
            recent = recent.subList(0, 5);
        }
        properties.setProperty("recentFiles", String.join(";", recent));
        savePreferences();
    }

    // Helper for boolean prefs if needed later
    // public boolean getBooleanPreference(String key, boolean defaultValue) {
    //     return Boolean.parseBoolean(getPreference(key, Boolean.toString(defaultValue)));
    // }
    //
    // public void setBooleanPreference(String key, boolean value) {
    //     setPreference(key, Boolean.toString(value));
    // }
}

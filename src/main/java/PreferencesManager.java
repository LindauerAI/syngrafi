import java.io.*;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.prefs.Preferences;
import java.util.prefs.BackingStoreException;

public class PreferencesManager {
    private static final String PREFS_NODE_PATH = "/org/tlind/syngrafi";
    private Preferences prefsNode;

    private static final String PROPS_FILE_NAME = "syngrafi_props.properties";
    private Properties properties = new Properties();
    private Path propsFilePath;

    private static final String SECURE_PROPS_FILE_NAME = "syngrafi_secure.properties";
    private static final String KEY_FILE_NAME = "syngrafi.key";
    private Properties secureProperties = new Properties();
    private Path securePropsFilePath;
    private Path keyFilePath;
    private ApiKeyEncryptor encryptor;

    public PreferencesManager() {
        prefsNode = Preferences.userRoot().node(PREFS_NODE_PATH);

        String userHome = System.getProperty("user.home");
        Path prefsDir = Paths.get(userHome, ".syngrafi");
        try {
            Files.createDirectories(prefsDir);
        } catch (IOException e) {
            System.err.println("Could not create preferences directory: " + prefsDir);
        }
        propsFilePath = prefsDir.resolve(PROPS_FILE_NAME);
        securePropsFilePath = prefsDir.resolve(SECURE_PROPS_FILE_NAME);
        keyFilePath = prefsDir.resolve(KEY_FILE_NAME);

        try {
             encryptor = new ApiKeyEncryptor(keyFilePath);
        } catch (RuntimeException e) {
             System.err.println("FATAL: Could not initialize API key encryption: " + e.getMessage());
             encryptor = null;
        }
    }

    public void loadPreferences() {
        if (Files.exists(propsFilePath)) {
            try (FileInputStream fis = new FileInputStream(propsFilePath.toFile())) {
                properties.load(fis);
            } catch (IOException e) {
                System.err.println("Error loading properties from " + propsFilePath);
            }
        }
        properties.putIfAbsent("provider", "OpenAI");
        properties.putIfAbsent("model", "gpt-4o");
        properties.putIfAbsent("autocompleteDelay", "600");
        properties.putIfAbsent("numSuggestions", "3");
        properties.putIfAbsent("theme", "System");
        properties.putIfAbsent("generalStylePrompt", "");
        properties.putIfAbsent("autocompleteMaxLength", "200");
        properties.putIfAbsent("recentFiles", "");
        properties.putIfAbsent("defaultPath", System.getProperty("user.dir"));
        properties.putIfAbsent("aiReferences", "");

        if (Files.exists(securePropsFilePath)) {
            try (FileInputStream fis = new FileInputStream(securePropsFilePath.toFile())) {
                secureProperties.load(fis);
            } catch (IOException e) {
                System.err.println("Error loading secure properties from " + securePropsFilePath);
                secureProperties.clear();
            }
        }
    }

    public void savePreferences() {
        try (FileOutputStream fos = new FileOutputStream(propsFilePath.toFile())) {
            properties.store(fos, "Syngrafi Application Properties (Non-sensitive)");
        } catch (IOException e) {
            System.err.println("Error saving properties to " + propsFilePath);
        }
        
        try (FileOutputStream fos = new FileOutputStream(securePropsFilePath.toFile())) {
            secureProperties.store(fos, "Syngrafi API Keys");
        } catch (IOException e) {
            System.err.println("Error saving secure properties to " + securePropsFilePath);
        }
    }

    public String getPreference(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public String getApiKey(String key) {
        if (encryptor == null) return "";
        String encryptedValue = secureProperties.getProperty(key, "");
        return encryptor.decrypt(encryptedValue);
    }

    public void setPreference(String key, String value) {
        if (key.toLowerCase().contains("apikey")) {
            if (encryptor != null) {
                String encryptedValue = encryptor.encrypt(value);
                secureProperties.setProperty(key, encryptedValue);
            } else {
                System.err.println("Warning: Cannot save API key, encryption unavailable.");
            }
        } else {
            properties.setProperty(key, value);
        }
    }

    public List<String> getRecentFiles() {
        return new ArrayList<>(Arrays.asList(getPreference("recentFiles", "").split(";")));
    }

    public void addRecentFile(String filePath) {
        List<String> recent = getRecentFiles();
        recent.remove(filePath);
        recent.add(0, filePath);
        if (recent.size() > 5) {
            recent = recent.subList(0, 5);
        }
        setPreference("recentFiles", String.join(";", recent));
    }

    public String getDefaultPath() {
        return getPreference("defaultPath", System.getProperty("user.dir"));
    }

    public String getAIReferences() {
        return getPreference("aiReferences", "");
    }

    public void setAIReferences(String references) {
        setPreference("aiReferences", references);
    }
}

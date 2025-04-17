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

    private static final String API_KEY = "apiKey";
    private static final String API_PROVIDER = "apiProvider";
    private static final String AUTOCOMPLETE_DELAY = "autocompleteDelay";
    private static final String NUM_SUGGESTIONS = "numSuggestions";
    private static final String DEFAULT_DIRECTORY = "defaultDirectory";
    private static final String USE_DARK_THEME = "useDarkTheme";
    private static final String DEFAULT_REWRITE_PROMPT = "defaultRewritePrompt";
    private static final String ENCRYPTED_API_KEY = "x249refElwgeew34sf";
    private static final String AI_REFERENCES = "aiReferences";

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
        properties.putIfAbsent("autocompleteDelay", "1000");
        properties.putIfAbsent("numSuggestions", "3");
        properties.putIfAbsent("theme", "System");
        properties.putIfAbsent("generalStylePrompt", "");
        properties.putIfAbsent("autocompleteMaxLength", "200");
        properties.putIfAbsent("recentFiles", "");
        properties.putIfAbsent("defaultPath", System.getProperty("user.dir"));

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
        return prefsNode.get(AI_REFERENCES, "");
    }

    public void setAIReferences(String references) {
        prefsNode.put(AI_REFERENCES, references);
    }

    public String getApiProvider() {
        return prefsNode.get(API_PROVIDER, "OpenAI");
    }

    public void setApiProvider(String provider) {
        prefsNode.put(API_PROVIDER, provider);
    }

    public String getAutocompleteDelay() {
        return prefsNode.get(AUTOCOMPLETE_DELAY, "1000");
    }

    public void setAutocompleteDelay(String delay) {
        prefsNode.put(AUTOCOMPLETE_DELAY, delay);
    }

    public String getNumSuggestions() {
        return prefsNode.get(NUM_SUGGESTIONS, "3");
    }

    public void setNumSuggestions(String num) {
        prefsNode.put(NUM_SUGGESTIONS, num);
    }

    public String getDefaultDirectory() {
        return prefsNode.get(DEFAULT_DIRECTORY, System.getProperty("user.home"));
    }

    public void setDefaultDirectory(String dir) {
        prefsNode.put(DEFAULT_DIRECTORY, dir);
    }

    public boolean getUseDarkTheme() {
        return prefsNode.getBoolean(USE_DARK_THEME, false);
    }

    public void setUseDarkTheme(boolean useDark) {
        prefsNode.putBoolean(USE_DARK_THEME, useDark);
    }

    public String getDefaultRewritePrompt() {
        return prefsNode.get(DEFAULT_REWRITE_PROMPT, "Rewrite the selected text for clarity and conciseness, maintaining the same style. Do not provide multiple options or styling, just the raw text.");
    }

    public void setDefaultRewritePrompt(String prompt) {
        prefsNode.put(DEFAULT_REWRITE_PROMPT, prompt);
    }

    public boolean hasApiKey() {
        if (encryptor == null) return false;
        String openAIKeyEnc = secureProperties.getProperty("apiKeyOpenAI", "");
        String geminiKeyEnc = secureProperties.getProperty("apiKeyGemini", "");
        return (!openAIKeyEnc.isEmpty() && !encryptor.decrypt(openAIKeyEnc).isEmpty()) || 
               (!geminiKeyEnc.isEmpty() && !encryptor.decrypt(geminiKeyEnc).isEmpty());
    }

    public String getApiKey() {
        if (encryptor == null) return null;
        String encryptedKey = prefsNode.get(ENCRYPTED_API_KEY, null);
        if (encryptedKey == null || encryptedKey.isEmpty()) {
            return null;
        }
        try {
            return encryptor.decrypt(encryptedKey);
        } catch (Exception e) {
            System.err.println("Failed to decrypt API key: " + e.getMessage());
            return null;
        }
    }

    public boolean setApiKey(String plainTextKey) {
        if (encryptor == null) return false;
        if (plainTextKey == null || plainTextKey.trim().isEmpty()) {
             System.err.println("Attempted to set an empty API key.");
             return false;
        }
        try {
            String encryptedKey = encryptor.encrypt(plainTextKey);
            if (encryptedKey.isEmpty()) {
                 System.err.println("Encryption resulted in an empty string. API key not set.");
                 return false;
            }
            prefsNode.put(ENCRYPTED_API_KEY, encryptedKey);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to encrypt and store API key: " + e.getMessage());
            return false;
        }
    }

    public void clearApiKey() {
        prefsNode.remove(ENCRYPTED_API_KEY);
        System.out.println("Stored API key cleared.");
    }
}

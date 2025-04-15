import java.io.*;
import java.util.*;

public class PreferencesManager {
    private static final String CONFIG_FILE = System.getProperty("user.home")
            + File.separator + "syngrafi.properties";
    private final Properties properties = new Properties();

    public PreferencesManager() {
        loadPreferences();
    }

    void loadPreferences() {
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                properties.load(fis);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void savePreferences() {
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            properties.store(fos, "Syngrafi Settings");
        } catch (IOException e) {
            e.printStackTrace();
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
}

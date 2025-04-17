import api.APIProvider;

import java.util.concurrent.CompletableFuture;

/**
 * Handles the logic for rewriting selected text using an API provider,
 * incorporating global style and reference prompts.
 */
public class RewriteManager {

    private final APIProvider apiProvider;
    private final PreferencesManager prefs;

    public RewriteManager(APIProvider apiProvider, PreferencesManager prefs) {
        this.apiProvider = apiProvider;
        this.prefs = prefs;
    }

    /**
     * Asynchronously performs the rewrite operation.
     *
     * @param selectedText       The text selected by the user.
     * @param userProvidedPrompt The specific rewrite instruction provided by the user (can be empty).
     * @return A CompletableFuture containing the rewritten text or an error message prefixed with "ERROR:".
     */
    public CompletableFuture<String> performRewrite(String selectedText, String userProvidedPrompt) {
        if (apiProvider == null || selectedText == null || selectedText.trim().isEmpty()) {
            return CompletableFuture.completedFuture("ERROR: Invalid input or API provider.");
        }

        // Get global prompts
        String stylePrompt = prefs.getPreference("generalStylePrompt", ""); // Use the key from SettingsDialog
        String references = prefs.getAIReferences(); // Use the specific getter
        String defaultRewrite = prefs.getDefaultRewritePrompt(); // Get default rewrite instruction

        // Use user prompt if provided, otherwise use default
        String finalUserInstruction = (userProvidedPrompt != null && !userProvidedPrompt.trim().isEmpty())
                ? userProvidedPrompt.trim()
                : defaultRewrite;

        // Construct the final prompt
        StringBuilder finalApiPrompt = new StringBuilder();
        finalApiPrompt.append(finalUserInstruction).append("\n\n---\n");

        if (!stylePrompt.trim().isEmpty()) {
            finalApiPrompt.append("Apply the following style: ").append(stylePrompt).append("\n\n---\n");
        }

        if (!references.trim().isEmpty()) {
            finalApiPrompt.append("Use the following reference examples:\n").append(references).append("\n\n---\n");
        }

        finalApiPrompt.append("Do not provide multiple options and do not use unicode characters. The text will be inserted directly into the file. Rewrite the following text: \n\n").append(selectedText);

        // Run API call asynchronously
        String promptToSend = finalApiPrompt.toString();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return apiProvider.generateCompletion(promptToSend);
            } catch (Exception ex) {
                System.err.println("Error during API call for rewrite: " + ex.getMessage());
                return "ERROR: API call failed. " + ex.getMessage();
            }
        });
    }
} 
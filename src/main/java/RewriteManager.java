import api.APIProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
     * Asynchronously performs the rewrite operation, generating multiple suggestions.
     *
     * @param selectedText       The text selected by the user.
     * @param userProvidedPrompt The specific rewrite instruction provided by the user (can be empty).
     * @return A CompletableFuture containing a List of rewritten text suggestions,
     *         or a list containing a single error message prefixed with "ERROR:".
     */
    public CompletableFuture<List<String>> performRewrite(String selectedText, String userProvidedPrompt) {
        if (apiProvider == null || selectedText == null || selectedText.trim().isEmpty()) {
            return CompletableFuture.completedFuture(Collections.singletonList("ERROR: Invalid input or API provider."));
        }

        // Get settings
        String stylePrompt = prefs.getPreference("generalStylePrompt", "");
        String references = prefs.getAIReferences();
        String defaultRewrite = prefs.getDefaultRewritePrompt();
        int numSuggestions = prefs.getNumRewriteSuggestions(); // Get number of suggestions
        if (numSuggestions <= 0) numSuggestions = 1; // Ensure at least 1

        // Determine final instruction
        String finalUserInstruction = (userProvidedPrompt != null && !userProvidedPrompt.trim().isEmpty()) 
                                       ? userProvidedPrompt.trim() 
                                       : defaultRewrite;
        
        // Construct the base prompt structure
        StringBuilder basePromptBuilder = new StringBuilder();
        basePromptBuilder.append(finalUserInstruction).append("\n\n---\n");
        if (!stylePrompt.trim().isEmpty()) {
            basePromptBuilder.append("Apply the following style, but prioritize the previous instruction: ").append(stylePrompt).append("\n\n---\n");
        }
        if (!references.trim().isEmpty()) {
            basePromptBuilder.append("Use the following reference examples:\n").append(references).append("\n\n---\n");
        }
        basePromptBuilder.append("Do not provide multiple options and do not use unicode characters. The text will be inserted directly into the file. Rewrite the following text:\n\n").append(selectedText);
        
        String basePrompt = basePromptBuilder.toString(); // Base prompt without variation info

        // Create a list of CompletableFuture for each suggestion
        List<CompletableFuture<String>> suggestionFutures = IntStream.range(0, numSuggestions)
            .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                try {
                    // Add variation info if needed by API, or just call multiple times
                    // String promptToSend = basePrompt + "\n(Variation: " + (i + 1) + ")"; 
                    String promptToSend = basePrompt; // Assuming multiple calls are sufficient
                    return apiProvider.generateCompletion(promptToSend);
                } catch (Exception ex) {
                    System.err.println("Error during API call for rewrite suggestion " + (i+1) + ": " + ex.getMessage());
                    // Return null or a specific error marker for this suggestion
                    return null; 
                }
            }))
            .collect(Collectors.toList());

        // Combine all futures: Wait for all to complete
        return CompletableFuture.allOf(suggestionFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> suggestionFutures.stream()
                .map(future -> {
                    try {
                        return future.join(); // Get result from completed future
                    } catch (Exception e) {
                        return null; // Handle potential exceptions during join
                    }
                })
                .filter(result -> result != null && !result.trim().isEmpty()) // Filter out nulls/empty results
                .collect(Collectors.toList()))
            .exceptionally(ex -> {
                 // Handle exceptions during the combination/collection phase
                 System.err.println("Error combining rewrite suggestion futures: " + ex.getMessage());
                 return Collections.singletonList("ERROR: Failed to generate rewrite suggestions.");
            });
    }
} 
// AutocompletePromptManager.java
public class AutocompletePromptManager {

    /**
     * Generates a suitable prompt for the AI based on context and variation.
     * Uses preferences for potentially user-defined prompt structures.
     *
     * @param textBeforeCursor Text preceding the cursor.
     * @param textAfterCursor  Text following the cursor.
     * @param variation        Integer indicating which prompt variation to use (1-based).
     * @param prefs            PreferencesManager to fetch prompt templates and settings.
     * @return The generated prompt string.
     */
    public static String getPrompt(String textBeforeCursor, String textAfterCursor, int variation, PreferencesManager prefs) {
        // Retrieve the base prompt template for the given variation
        String promptTemplate = prefs.getPreference(
                "autocompletePrompt" + variation,
                getDefaultPromptTemplate(variation)
        );

        // Retrieve the general style prompt
        String generalStyle = prefs.getPreference("generalStylePrompt", "");
        // Retrieve max length preference
        int maxLength = 200;
        try {
             maxLength = Integer.parseInt(prefs.getPreference("autocompleteMaxLength", "100"));
        } catch (NumberFormatException e) { /* use default */ }

        // Build the final prompt
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("You are an AI autocomplete tool helping a user write content.\n");
        promptBuilder.append("Your goal is to provide a relevant and helpful completion or continuation of their text.\n");

        // Insert the general style prompt if provided
        if (generalStyle != null && !generalStyle.trim().isEmpty()) {
            promptBuilder.append("\nPlease adhere to the following general style:\n");
            promptBuilder.append(generalStyle.trim()).append("\n");
        }

        // Retrieve and insert AI references if provided
        String references = prefs.getAIReferences();
        if (references != null && !references.trim().isEmpty()) {
            promptBuilder.append("\nConsider these user-provided references only if relevant to the context:\n");
            // Split by newline and list them
            String[] refLines = references.trim().split("\\r?\\n");
            for (String ref : refLines) {
                 if (!ref.trim().isEmpty()) {
                     promptBuilder.append("- ").append(ref.trim()).append("\n");
                 }
            }
             promptBuilder.append("\n"); // Add blank line after references
        }

        promptBuilder.append("Text before cursor:\n");
        promptBuilder.append("```\n").append(textBeforeCursor).append("\n```\n\n");

        promptBuilder.append("Text after cursor:\n");
        promptBuilder.append("```\n").append(textAfterCursor).append("\n```\n\n");

        // Add the specific instruction from the template
        promptBuilder.append(promptTemplate).append("\n");
        promptBuilder.append("Provide only the suggested text to go after cursor but before subsequent text, without any introductory phrases like \"Here is the suggestion:\".");
        promptBuilder.append("\nKeep the suggestion concise, ideally under ").append(maxLength).append(" characters.");

        return promptBuilder.toString();
    }

    /**
     * Provides default fallback prompt templates if not defined in preferences.
     */
    private static String getDefaultPromptTemplate(int variation) {
        switch (variation) {
            case 1: return "Provide the most likely next phrase based on the context.";
            case 2: return "Provide an alternative phrasing or direction for the text following the cursor, starting from the cursor position.";
            case 3: return "Expand on the text before the cursor with additional detail or explanation.";
            default: return "Continue writing the text from the cursor position."; // Generic fallback
        }
    }
}

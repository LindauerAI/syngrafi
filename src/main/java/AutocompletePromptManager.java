/**
 * Manages the generation of prompts for autocomplete functionality.
 */
public class AutocompletePromptManager {
    
    /**
     * Generates a prompt for autocomplete based on the current text and variation number.
     * 
     * @param currentText The current plain text
     * @param variation   Which variation of the prompt to generate (1-3)
     * @return A formatted prompt for the AI model
     */
    public static String getPrompt(String currentText, int variation) {
        // Extract the most recent context
        String context = extractRecentContext(currentText);
        
        // Base prompt
        String basePrompt = "You are a professional writing autocomplete assistant. "
                + "Provide a succinct, coherent continuation of the user’s text. "
                + "Do NOT repeat any text verbatim from the prompt. "
                + "Limit to 2 sentences maximum. No ellipses. Consider whitespace carefully."
                + "Context:\n\""
                + context + "\"\n\nContinuation:";
        
        // Variation-based wording
        switch (variation) {
            case 1: return basePrompt + " Provide the most likely next phrase.";
            case 2: return basePrompt + " Provide an alternate direction.";
            case 3: return basePrompt + " Expand with a bit more detail.";
            default: return basePrompt;
        }
    }
    
    private static String extractRecentContext(String fullText) {
        String trimmed = fullText.trim();
        if (trimmed.length() <= 400) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - 400);
    }
}

/**
 * Manages the generation of prompts for autocomplete functionality.
 */
public class AutocompletePromptManager {

    public static String getPrompt(String currentText, int variation, PreferencesManager prefs) {
        String context = extractContext(currentText);
        String fallback = defaultPromptForIndex(variation);
        String userPrompt = prefs.getPreference("autocompletePrompt" + variation, fallback);

        String basePrompt =
                "You are a professional writing autocomplete assistant called Syngrafi. "
                        + "Please continue the user's text carefully, ensuring that punctuation "
                        + "is followed by a space. "
                        + "Return up to 2 sentences. Do not repeat ANY text in \"text so far\" "
                        + "(in the backticks). No ellipses.\n"
                        + "Text so far:\n```" + context + "\n```\nContinuation:";

        return basePrompt + userPrompt.trim();
    }

    private static String defaultPromptForIndex(int i) {
        switch (i) {
            case 1: return "Provide the most likely next phrase.";
            case 2: return "Provide an alternative direction.";
            default: return "Expand on this with additional detail.";
        }
    }

    private static String extractContext(String text) {
        text = text.trim();
        if (text.length() <= 400) {
            return text;
        }
        return text.substring(text.length() - 400);
    }

}

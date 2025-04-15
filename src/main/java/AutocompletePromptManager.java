/**
 * Manages the generation of prompts for autocomplete functionality.
 */
public class AutocompletePromptManager {

    public static String getPrompt(String currentText, int variation) {
        String context = extractContext(currentText);

        String basePrompt = "You are a professional writing autocomplete assistant called Syngrafi. "
                + "Please continue the user's text carefully, ensuring that punctuation is followed by a space IF NOT ALREADY. "
                + "Return up to 2 sentences, without repeating ANY text in the \"text so far\" portion (in the backticks). No ellipses. "
                + "Text so far:\n```"
                + context + "\n```\nContinuation:";

        switch (variation) {
            case 1:
                return basePrompt + " Provide the most likely next phrase.";
            case 2:
                return basePrompt + " Provide an alternative direction.";
            default:
                return basePrompt + " Expand on this with additional detail.";
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

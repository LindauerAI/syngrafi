// AutocompletePromptManager.java
public class AutocompletePromptManager {

    /**
     * Builds a prompt that clearly separates before‑caret context,
     * after‑caret context, and a concise instruction for completion.
     */
    public static String getPrompt(
            String beforeText,
            String afterText,
            int variation,
            PreferencesManager prefs
    ) {
        // Trim contexts
        String left = beforeText.length() > 400
                ? beforeText.substring(beforeText.length() - 400)
                : beforeText;
        String right = afterText.length() > 200
                ? afterText.substring(0, 200)
                : afterText;

        String userPref = prefs.getPreference(
                "autocompletePrompt" + variation,
                defaultPromptForIndex(variation)
        );

        return String.join("\n",
                "You are Syngrafi’s Context‑Aware Autocomplete. ",
                "Your task: provide a seamless continuation at the cursor position, ",
                "respecting style, punctuation, and not repeating existing text. " +
                "Do not provide any formatting in your answer, just the raw text.",
                "",
                "=== Text before cursor (up to 400 chars) ===",
                "```" + left + "```",
                "",
                "=== Text after cursor (up to 200 chars) ===",
                "```" + right + "```",
                "",
                "=== Suggestion ===",
                userPref.trim()
        );
    }

    private static String defaultPromptForIndex(int i) {
        switch (i) {
            case 1:
                return "Provide the most likely next phrase.";
            case 2:
                return "Offer an alternative direction or phrasing.";
            default:
                return "Expand with additional relevant detail.";
        }
    }
}

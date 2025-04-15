package api;

/**
 * Common interface for language model providers.
 */
public interface APIProvider {
    /**
     * Generate a completion (suggestion) for the given prompt.
     *
     * @param prompt The prompt to send.
     * @return The generated text.
     * @throws Exception if the API call fails.
     */
    String generateCompletion(String prompt) throws Exception;
}

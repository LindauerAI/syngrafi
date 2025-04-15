package api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GeminiProvider implements APIProvider {
    private final String apiKey;
    private final String model; // e.g., "gemini-2.0-flash" or "gemini-pro"

    public GeminiProvider(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String generateCompletion(String prompt) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Build the endpoint URL using the chosen Gemini model.
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" 
                + model + ":generateContent?key=" + apiKey;

        // Construct the JSON payload.
        String jsonPayload = "{"
                + "\"contents\": [ { \"parts\": [ { \"text\": \"" + 
                prompt.replace("\"", "\\\"").replace("\n", "\\n") + "\" } ] } ],"
                + "\"generationConfig\": {"
                + "\"temperature\": 0.4,"
                + "\"maxOutputTokens\": 150,"
                + "\"topP\": 0.95"
                + "}"
                + "}";
                
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();
        
        // Extract the text from the response
        return extractTextFromResponse(responseBody);
    }
    
    /**
     * Extracts the actual text content from the Gemini API response
     */
    private String extractTextFromResponse(String responseBody) {
        // First check for error
        if (responseBody.contains("error")) {
            Pattern errorPattern = Pattern.compile("\"message\":\\s*\"([^\"]+)\"");
            Matcher errorMatcher = errorPattern.matcher(responseBody);
            if (errorMatcher.find()) {
                return "API Error: " + errorMatcher.group(1);
            }
            return "Unknown API Error";
        }
        
        // Try to extract text using regex
        Pattern textPattern = Pattern.compile("\"text\":\\s*\"([^\"]+)\"");
        Matcher textMatcher = textPattern.matcher(responseBody);
        if (textMatcher.find()) {
            return textMatcher.group(1).replace("\\n", "\n").replace("\\\"", "\"");
        }
        
        // If we can't find with regex, look for specific markers in the JSON
        int textIndex = responseBody.indexOf("\"text\":");
        if (textIndex >= 0) {
            int startQuote = responseBody.indexOf("\"", textIndex + 7);
            int endQuote = responseBody.indexOf("\"", startQuote + 1);
            if (startQuote >= 0 && endQuote > startQuote) {
                return responseBody.substring(startQuote + 1, endQuote).replace("\\n", "\n").replace("\\\"", "\"");
            }
        }
        // If all else fails, return the raw response
        return responseBody;
    }
}

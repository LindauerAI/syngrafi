package api;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

public class OpenAIProvider implements APIProvider {
    private final String apiKey;
    private final String model;

    public OpenAIProvider(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String generateCompletion(String prompt) throws Exception {
        // Initialize the OpenAI client using the provided API key.
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        // Map the model string to the appropriate ChatModel enum.
        ChatModel chosenModel;
        if (model.equalsIgnoreCase("gpt-4o")) {
            chosenModel = ChatModel.GPT_4O;
        } else {
            chosenModel = ChatModel.GPT_3_5_TURBO; // fallback option
        }

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(chosenModel)
                .addUserMessage(prompt)
                .maxTokens(50)
                .temperature(0.7)
                .build();

        ChatCompletion completion = client.chat().completions().create(params);
        // Return the first choice's content.
        return completion.toString();
    }
}

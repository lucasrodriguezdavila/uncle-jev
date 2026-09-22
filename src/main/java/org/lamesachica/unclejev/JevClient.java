package org.lamesachica.unclejev;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class JevClient {

    private static final URI ENDPOINT = URI.create("https://api.typesafe.ai/v1/systemone");

    public record Verdict(String insult, List<String> severeReasons, JsonObject answers) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final double severeThreshold;
    private final JsonObject questions;

    public JevClient(String apiKey, String model, Duration timeout, double severeThreshold, JsonObject questions) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.severeThreshold = severeThreshold;
        this.questions = questions;
    }

    public Verdict classify(String message, List<String> previousFromPlayer, List<String> recentChat) throws Exception {
        JsonObject state = new JsonObject();
        state.addProperty("message", message);
        state.add("previous_messages_from_player", toArray(previousFromPlayer));
        state.add("recent_chat", toArray(recentChat));

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("state", state);
        body.add("questions", questions);

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("JEV responded " + response.statusCode() + ": " + response.body());
        }

        JsonObject answers = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("answers");
        List<String> severeReasons = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : answers.entrySet()) {
            JsonObject answer = entry.getValue().getAsJsonObject();
            if (answer.get("type").getAsString().equals("noul") && answer.get("noul").getAsDouble() >= severeThreshold) {
                severeReasons.add(entry.getKey());
            }
        }
        return new Verdict(answers.getAsJsonObject("insult").get("choice").getAsString(), severeReasons, answers);
    }

    private static JsonArray toArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}

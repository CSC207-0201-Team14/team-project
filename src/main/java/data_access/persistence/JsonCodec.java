package data_access.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import entity.Dependency;
import entity.FailureEvent;
import entity.HealthCheckResult;

import java.io.Reader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;

/** Shared JSON operations and formats for domain records stored by multiple persistence adapters. */
public final class JsonCodec {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).setPrettyPrinting().create();

    private JsonCodec() {
    }

    public static JsonObject readObject(Reader reader) {
        JsonObject object = JSON.fromJson(reader, JsonObject.class);
        if (object == null) {
            throw new JsonParseException("Expected a JSON object");
        }
        return object;
    }

    public static String toJson(JsonObject object) {
        return JSON.toJson(object);
    }

    public static JsonObject encodeDependency(Dependency dependency) {
        JsonObject json = new JsonObject();
        json.add("id", encodeId(dependency.getId()));
        json.add("dependentServiceId", encodeId(dependency.getDependentServiceId()));
        json.add("requiredServiceId", encodeId(dependency.getRequiredServiceId()));
        return json;
    }

    public static Dependency readDependency(JsonElement element) {
        JsonObject json = element.getAsJsonObject();
        return new Dependency(readId(json, "id"), readId(json, "dependentServiceId"), readId(json, "requiredServiceId"));
    }

    public static JsonObject encodeFailureEvent(FailureEvent failure) {
        JsonObject json = new JsonObject();
        json.add("id", encodeId(failure.getId()));
        json.add("serviceId", encodeId(failure.getServiceId()));
        json.addProperty("timestamp", failure.getTimestamp().toString());
        json.add("evidence", encodeArray(failure.getEvidence(), JsonCodec::encodeHealthCheckResult));
        return json;
    }

    public static FailureEvent readFailureEvent(JsonElement element) {
        JsonObject json = element.getAsJsonObject();
        return new FailureEvent(readId(json, "id"), readId(json, "serviceId"), readInstant(json, "timestamp"),
                readList(json, "evidence", JsonCodec::readHealthCheckResult));
    }

    public static JsonObject encodeHealthCheckResult(HealthCheckResult result) {
        JsonObject json = new JsonObject();
        json.add("id", encodeId(result.getId()));
        json.add("serviceId", encodeId(result.getServiceId()));
        json.addProperty("timestamp", result.getTimestamp().toString());
        json.addProperty("outcome", result.getOutcome().name());
        result.getResponseTimeMs().ifPresent(ms -> json.addProperty("responseTimeMs", ms));
        json.addProperty("message", result.getMessage());
        return json;
    }

    public static HealthCheckResult readHealthCheckResult(JsonElement element) {
        JsonObject json = element.getAsJsonObject();
        OptionalLong responseTime = json.has("responseTimeMs")
                ? OptionalLong.of(readLong(json, "responseTimeMs")) : OptionalLong.empty();
        return new HealthCheckResult(readId(json, "id"), readId(json, "serviceId"), readInstant(json, "timestamp"),
                HealthCheckResult.Outcome.valueOf(readString(json, "outcome")), responseTime, readString(json, "message"));
    }

    public static <T> JsonArray encodeArray(Iterable<T> values, Function<? super T, ? extends JsonElement> encoder) {
        JsonArray array = new JsonArray();
        for (T value : values) {
            array.add(encoder.apply(value));
        }
        return array;
    }

    public static <T> List<T> readList(JsonObject json, String field, Function<JsonElement, T> decoder) {
        JsonArray array = json.getAsJsonArray(field);
        List<T> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            values.add(decoder.apply(element));
        }
        return values;
    }

    public static JsonPrimitive encodeId(UUID value) {
        return new JsonPrimitive(value.toString());
    }

    public static UUID readId(JsonObject json, String field) {
        return readId(json.get(field));
    }

    public static UUID readId(JsonElement element) {
        String value = readString(element);
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Invalid UUID: " + value);
        }
        return id;
    }

    public static Instant readInstant(JsonObject json, String field) {
        return Instant.parse(readString(json, field));
    }

    public static String readString(JsonObject json, String field) {
        return readString(json.get(field));
    }

    public static String readString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected a JSON string");
        }
        return element.getAsString();
    }

    public static int readInt(JsonObject json, String field) {
        return Math.toIntExact(readLong(json, field));
    }

    public static long readLong(JsonObject json, String field) {
        JsonPrimitive number = json.getAsJsonPrimitive(field);
        if (number == null || !number.isNumber()) {
            throw new IllegalArgumentException("Expected a number for " + field);
        }
        return number.getAsBigDecimal().longValueExact();
    }
}

package data_access;

import com.google.gson.JsonObject;
import data_access.persistence.JsonCodec;
import entity.Dependency;
import entity.HealthCheckResult;

import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

public final class JsonCodecChecks {
    public static void main(String[] args) {
        sharesConfigurationDependencyFormat();
        preservesOptionalResponseTime();
    }

    private static void sharesConfigurationDependencyFormat() {
        Dependency edge = new Dependency(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        JsonObject stored = new JsonObject();
        stored.addProperty("id", edge.getId().toString());
        stored.addProperty("dependentServiceId", edge.getDependentServiceId().toString());
        stored.addProperty("requiredServiceId", edge.getRequiredServiceId().toString());
        check(JsonCodec.encodeDependency(edge).equals(stored), "Dependency format differs from UC1");

        Dependency loaded = JsonCodec.readDependency(stored);
        check(loaded.getId().equals(edge.getId()) && loaded.getDependentServiceId().equals(edge.getDependentServiceId())
                && loaded.getRequiredServiceId().equals(edge.getRequiredServiceId()), "Dependency direction changed");
    }

    private static void preservesOptionalResponseTime() {
        long duration = 4_000_000_000L;
        JsonObject timed = JsonCodec.encodeHealthCheckResult(observation(OptionalLong.of(duration)));
        check(timed.getAsJsonPrimitive("responseTimeMs").isNumber()
                && JsonCodec.readHealthCheckResult(timed).getResponseTimeMs().orElseThrow() == duration,
                "Response time must retain its full integer precision");

        JsonObject untimed = JsonCodec.encodeHealthCheckResult(observation(OptionalLong.empty()));
        check(!untimed.has("responseTimeMs") && JsonCodec.readHealthCheckResult(untimed).getResponseTimeMs().isEmpty(),
                "An absent response time must survive reloading");

        timed.addProperty("responseTimeMs", 1.5);
        try {
            JsonCodec.readHealthCheckResult(timed);
        } catch (ArithmeticException | IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Accepted a fractional response time");
    }

    private static HealthCheckResult observation(OptionalLong responseTimeMs) {
        return new HealthCheckResult(UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH,
                HealthCheckResult.Outcome.PASS, responseTimeMs, "OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

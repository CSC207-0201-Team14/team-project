package data_access.persistence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import entity.DiagnosticFinding;
import entity.Incident;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static data_access.persistence.JsonCodec.encodeArray;
import static data_access.persistence.JsonCodec.encodeId;
import static data_access.persistence.JsonCodec.readId;
import static data_access.persistence.JsonCodec.readInstant;
import static data_access.persistence.JsonCodec.readList;
import static data_access.persistence.JsonCodec.readString;

/** The on-disk incident format. Active findings refer to the full records stored in history. */
final class IncidentJsonCodec {
    private static final int SCHEMA_VERSION = 1;

    private IncidentJsonCodec() {
    }

    static JsonObject encode(Incident incident) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.add("id", encodeId(incident.getId()));
        json.add("systemId", encodeId(incident.getSystemId()));
        json.addProperty("openedAt", incident.getOpenedAt().toString());
        incident.getInvestigatingAt().ifPresent(time -> json.addProperty("investigatingAt", time.toString()));
        incident.getResolvedAt().ifPresent(time -> json.addProperty("resolvedAt", time.toString()));
        json.add("failures", encodeArray(incident.getFailures(), JsonCodec::encodeFailureEvent));
        json.add("potentiallyAffectedServiceIds",
                encodeArray(incident.getPotentiallyAffectedServiceIds(), JsonCodec::encodeId));
        json.add("findingHistory", encodeArray(incident.getFindingHistory(), IncidentJsonCodec::encodeFinding));
        json.add("activeFindingIds", encodeArray(incident.getActiveFindings(), finding -> encodeId(finding.getId())));
        json.add("resolutionEvidence", encodeArray(incident.getResolutionEvidence(), JsonCodec::encodeHealthCheckResult));
        return json;
    }

    static Incident decode(JsonObject json) {
        int version = JsonCodec.readInt(json, "schemaVersion");
        if (version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported incident schema version: " + version);
        }
        List<DiagnosticFinding> history = readList(json, "findingHistory", IncidentJsonCodec::readFinding);
        Map<UUID, DiagnosticFinding> byId = new HashMap<>();
        for (DiagnosticFinding finding : history) {
            byId.put(finding.getId(), finding);
        }
        List<DiagnosticFinding> active = new ArrayList<>();
        for (UUID id : readList(json, "activeFindingIds", JsonCodec::readId)) {
            DiagnosticFinding finding = byId.get(id);
            if (finding == null) {
                throw new IllegalArgumentException("Active finding is missing from history: " + id);
            }
            active.add(finding);
        }
        return Incident.restore(readId(json, "id"), readId(json, "systemId"), readInstant(json, "openedAt"),
                readOptionalTime(json, "investigatingAt"), readOptionalTime(json, "resolvedAt"),
                readList(json, "failures", JsonCodec::readFailureEvent),
                Set.copyOf(readList(json, "potentiallyAffectedServiceIds", JsonCodec::readId)), active, history,
                readList(json, "resolutionEvidence", JsonCodec::readHealthCheckResult));
    }

    private static Instant readOptionalTime(JsonObject json, String field) {
        return json.has(field) ? readInstant(json, field) : null;
    }

    private static JsonObject encodeFinding(DiagnosticFinding finding) {
        JsonObject json = new JsonObject();
        json.add("id", encodeId(finding.getId()));
        json.addProperty("ruleId", finding.getRuleId());
        json.add("affectedServiceId", encodeId(finding.getAffectedServiceId()));
        json.addProperty("createdAt", finding.getCreatedAt().toString());
        finding.getSuspectedCauseServiceId().ifPresent(cause -> json.add("suspectedCauseServiceId", encodeId(cause)));
        json.add("observationIds", encodeArray(finding.getObservationIds(), JsonCodec::encodeId));
        json.add("dependencyEvidence", encodeArray(finding.getDependencyEvidence(), JsonCodec::encodeDependency));
        json.addProperty("explanation", finding.getExplanation());
        json.add("nextSteps", encodeArray(finding.getNextSteps(), JsonPrimitive::new));
        return json;
    }

    private static DiagnosticFinding readFinding(JsonElement element) {
        JsonObject json = element.getAsJsonObject();
        Optional<UUID> cause = Optional.ofNullable(json.get("suspectedCauseServiceId")).map(JsonCodec::readId);
        return new DiagnosticFinding(readId(json, "id"), readString(json, "ruleId"), readId(json, "affectedServiceId"),
                readInstant(json, "createdAt"), cause, readList(json, "observationIds", JsonCodec::readId),
                readList(json, "dependencyEvidence", JsonCodec::readDependency), readString(json, "explanation"),
                readList(json, "nextSteps", JsonCodec::readString));
    }
}

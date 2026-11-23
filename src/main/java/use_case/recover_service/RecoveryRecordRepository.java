package use_case.recover_service;

import entity.IncidentRecoveryRecord;
import java.util.Optional;
import java.util.UUID;

/** Stores recovery history per incident. Access failures throw IncidentDataException. */
public interface RecoveryRecordRepository {
    /** Empty means no recovery has been attempted or started for the incident. */
    Optional<IncidentRecoveryRecord> findByIncidentId(UUID incidentId);

    /** Inserts or replaces the complete record by incident ID. A failed save leaves the previous value intact. */
    void save(IncidentRecoveryRecord record);
}

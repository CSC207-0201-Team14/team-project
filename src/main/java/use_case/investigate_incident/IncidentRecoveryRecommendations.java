package use_case.investigate_incident;

import entity.Incident;
import entity.RecoveryAction;
import use_case.shared.ConfigurationSnapshot;

import java.util.List;

/** Supplies current recovery candidates when assembling incident details. */
@FunctionalInterface
public interface IncidentRecoveryRecommendations {
    List<RecoveryAction> recommend(Incident incident, ConfigurationSnapshot configuration);
}

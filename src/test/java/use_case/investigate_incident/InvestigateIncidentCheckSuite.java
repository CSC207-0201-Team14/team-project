package use_case.investigate_incident;

import app.IncidentMonitoringChecks;
import data_access.persistence.JsonIncidentRepositoryChecks;
import entity.IncidentRestoreChecks;
import infrastructure.scheduling.SingleThreadIncidentWorkerChecks;
import interface_adapter.investigate_incident.IncidentFailureConnectorChecks;
import interface_adapter.investigate_incident.IncidentPresenterChecks;
import interface_adapter.investigate_incident.IncidentReaderChecks;

/** Runs every UC3 check program in one JVM; the first failed check stops the run. */
public final class InvestigateIncidentCheckSuite {
    private InvestigateIncidentCheckSuite() {
    }

    public static void main(String[] args) throws Exception {
        IncidentRestoreChecks.main(args);
        IncidentDiagnosisChecks.main(args);
        RecoveryRecommendationChecks.main(args);
        IncidentContractChecks.main(args);
        SingleThreadIncidentWorkerChecks.main(args);
        SerializedIncidentChecks.main(args);
        JsonIncidentRepositoryChecks.main(args);
        IncidentReaderChecks.main(args);
        IncidentFailureConnectorChecks.main(args);
        IncidentPresenterChecks.main(args);
        IncidentMonitoringChecks.main(args);
        System.out.println("All UC3 checks passed");
    }
}

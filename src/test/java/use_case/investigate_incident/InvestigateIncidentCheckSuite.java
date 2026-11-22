package use_case.investigate_incident;

import data_access.persistence.JsonIncidentRepositoryChecks;
import entity.IncidentRestoreChecks;

/** Runs every UC3 check program in one JVM; the first failed check stops the run. */
public final class InvestigateIncidentCheckSuite {
    private InvestigateIncidentCheckSuite() {
    }

    public static void main(String[] args) throws Exception {
        IncidentRestoreChecks.main(args);
        IncidentDiagnosisChecks.main(args);
        RecoveryRecommendationChecks.main(args);
        IncidentContractChecks.main(args);
        SerializedIncidentChecks.main(args);
        JsonIncidentRepositoryChecks.main(args);
        System.out.println("All UC3 checks passed");
    }
}

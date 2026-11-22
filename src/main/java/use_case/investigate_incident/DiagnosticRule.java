package use_case.investigate_incident;

import entity.DiagnosticFinding;
import java.util.List;

/** Pure rule producing evidence-backed findings, without incident mutation or I/O. */
public interface DiagnosticRule {
    List<DiagnosticFinding> evaluate(DiagnosticSnapshot snapshot);
}

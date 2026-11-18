package interface_adapter.monitor_service_health;

import entity.HealthCheckResult;
import use_case.monitor_service_health.MonitoringOutputBoundary;

import javax.swing.SwingUtilities;
import java.util.Objects;

public final class MonitoringPresenter implements MonitoringOutputBoundary {

    private final MonitoringViewModel viewModel;

    public MonitoringPresenter(MonitoringViewModel viewModel) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
    }

    @Override
    public void presentCheckResult(HealthCheckResult result) {
        Objects.requireNonNull(result, "result");

        SwingUtilities.invokeLater(() -> viewModel.showResult(result));
    }

    @Override
    public void presentError(String message) {
        Objects.requireNonNull(message, "message");

        SwingUtilities.invokeLater(() -> viewModel.showError(message));
    }
}
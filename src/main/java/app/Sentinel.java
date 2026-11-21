package app;

import data_access.configuration.JsonConfigurationRepository;
import interface_adapter.configuration.ConfigurationController;
import interface_adapter.configuration.ConfigurationPresenter;
import use_case.configure_monitored_system.ConfigurationInteractor;
import view.ConfigurationView;

import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Sentinel {
    private Sentinel() {
    }

    public static void main(String[] args) {
        Path configurations = Path.of(System.getProperty("user.home"), ".sentinel", "systems");
        SwingUtilities.invokeLater(() -> open(configurations));
    }

    private static void open(Path configurations) {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ConfigurationView view = new ConfigurationView();
        ConfigurationInteractor interactor = new ConfigurationInteractor(
                new JsonConfigurationRepository(configurations), new ConfigurationPresenter(view));
        view.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                worker.shutdown();
            }
        });
        view.connect(new ConfigurationController(interactor, worker));
        view.setVisible(true);
    }
}

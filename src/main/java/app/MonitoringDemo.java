package app;

import com.sun.net.httpserver.HttpServer;
import data_access.http.HttpHealthChecker;
import data_access.persistence.InMemoryObservationStore;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;
import interface_adapter.monitor_service_health.MonitoringController;
import interface_adapter.monitor_service_health.MonitoringPresenter;
import interface_adapter.monitor_service_health.MonitoringViewModel;
import use_case.monitor_service_health.MonitoringInteractor;
import view.monitor_service_health.MonitoringView;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

public final class MonitoringDemo {

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );

        server.createContext("/health", exchange -> {
            try {
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });

        server.start();

        int port = server.getAddress().getPort();

        Service service = new Service(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Local demo service",
                MonitoringTarget.http(
                        URI.create("http://127.0.0.1:" + port + "/health")
                ),
                new MonitoringPolicy(
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(2),
                        3,
                        2
                )
        );

        SwingUtilities.invokeLater(() -> showWindow(service, server));
    }

    private static void showWindow(Service service, HttpServer server) {
        MonitoringViewModel viewModel = new MonitoringViewModel();
        MonitoringPresenter presenter = new MonitoringPresenter(viewModel);

        MonitoringInteractor interactor = new MonitoringInteractor(
                new HttpHealthChecker(),
                new InMemoryObservationStore(),
                presenter
        );

        MonitoringController controller = new MonitoringController(
                interactor,
                presenter
        );

        MonitoringView view = new MonitoringView(
                service,
                controller,
                viewModel
        );

        JFrame frame = new JFrame("Sentinel — Monitoring Demo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(view);
        frame.setSize(900, 450);
        frame.setLocationRelativeTo(null);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                server.stop(0);
            }
        });

        frame.setVisible(true);
    }
}
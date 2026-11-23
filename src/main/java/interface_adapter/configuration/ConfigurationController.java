package interface_adapter.configuration;

import use_case.configure_monitored_system.ConfigurationInput;
import use_case.configure_monitored_system.ConfigurationInputBoundary;

import java.util.concurrent.Executor;

public final class ConfigurationController {
    private final ConfigurationInputBoundary input;
    private final Executor worker;

    public ConfigurationController(ConfigurationInputBoundary input, Executor worker) {
        this.input = input;
        this.worker = worker;
    }

    public void loadSystems() {
        worker.execute(input::loadSystems);
    }

    public void save(ConfigurationInput draft) {
        worker.execute(() -> input.save(draft));
    }
}

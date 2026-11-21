package use_case.configure_monitored_system;

public interface ConfigurationInputBoundary {
    void loadSystems();

    void save(ConfigurationInput input);
}

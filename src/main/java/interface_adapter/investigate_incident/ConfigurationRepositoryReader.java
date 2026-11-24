package interface_adapter.investigate_incident;

import use_case.configure_monitored_system.ConfigurationRepository;
import use_case.configure_monitored_system.ConfigurationStoreException;
import use_case.shared.ConfigurationReader;
import use_case.shared.ConfigurationSnapshot;
import use_case.shared.IncidentDataException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ConfigurationRepositoryReader implements ConfigurationReader {
    private final ConfigurationRepository repository;

    public ConfigurationRepositoryReader(ConfigurationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<ConfigurationSnapshot> findBySystemId(UUID systemId) {
        try {
            return repository.findById(systemId).map(ConfigurationSnapshot::available);
        } catch (ConfigurationStoreException exception) {
            throw new IncidentDataException(exception.getMessage(), exception);
        }
    }
}

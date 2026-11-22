package use_case.recover_service;

/**
 * Mutates a container. Only the recovery use case calls this, after explicit confirmation.
 * Each method returns normally when the engine accepted the operation and throws
 * ContainerAccessException otherwise.
 */
public interface ContainerOperator {
    void start(String containerId);

    void unpause(String containerId);

    void restart(String containerId);
}

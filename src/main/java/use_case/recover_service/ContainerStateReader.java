package use_case.recover_service;

/** Reads live container state. Implemented by an outer-layer container engine adapter. */
public interface ContainerStateReader {
    /**
     * Returns the current state, or ContainerState.missing() when the container does not exist.
     * Access failures throw ContainerAccessException.
     */
    ContainerState inspect(String containerId);
}

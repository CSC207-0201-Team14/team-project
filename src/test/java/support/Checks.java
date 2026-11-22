package support;

/** Assertions shared by the dependency-free check programs. */
public final class Checks {
    private Checks() {
    }

    public static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Passes only if the action throws the expected exception type. */
    public static void expect(Class<? extends RuntimeException> expected, Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            if (expected.isInstance(exception)) {
                return;
            }
            throw exception;
        }
        throw new AssertionError("Expected " + expected.getSimpleName() + ": " + message);
    }
}

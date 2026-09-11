package io.quarkiverse.goblin.assault;

import java.lang.reflect.InvocationTargetException;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.MutableAssaultConfig;

/**
 * Chaos assault that throws a configurable exception before the request reaches the endpoint.
 * <p>
 * The exception class and message are read from the {@link MutableAssaultConfig}. When the configured class cannot be
 * instantiated via reflection, a fallback {@link RuntimeException} carrying the configured message is thrown and a
 * {@code WARN} log explains the failure reason.
 */
@ApplicationScoped
public class ExceptionAssault implements Assault {

    private static final Logger LOG = Logger.getLogger(ExceptionAssault.class);

    /**
     * {@inheritDoc}
     *
     * @return the {@link AssaultType#EXCEPTION} assault type
     */
    @Override
    public AssaultType type() {
        return AssaultType.EXCEPTION;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code 20}, running after latency and before the other request-aborting assaults
     */
    @Override
    public int order() {
        return 20;
    }

    /**
     * {@inheritDoc}
     *
     * @return whether the exception assault is enabled in the configuration
     */
    @Override
    public boolean isEnabled(MutableAssaultConfig config) {
        return config.isExceptionEnabled();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code "exception"}, the label used in history and report
     */
    @Override
    public String recordLabel() {
        return "exception";
    }

    /**
     * {@inheritDoc}
     *
     * @return never -- this assault always throws
     * @throws RuntimeException the configured exception, or a fallback {@link RuntimeException} carrying the configured
     *         message when reflection fails
     */
    @Override
    public AssaultOutcome apply(AssaultContext context) {
        MutableAssaultConfig config = context.getConfig();
        LOG.debugf("Goblin: injecting exception on %s", context.getMethodName());
        context.getEngine().recordAssault(context.getMethodName(), recordLabel());
        throw createException(config);
    }

    /**
     * Instantiates the configured exception class via reflection.
     * <p>
     * Falls back to a {@link RuntimeException} carrying the configured message when the class cannot be found, has no
     * public {@code String} constructor, does not extend {@link RuntimeException}, or its constructor throws. A
     * {@code WARN} log describes the failure reason.
     *
     * @param config the configuration holding the exception type and message
     * @return the reflective exception instance, or the fallback {@link RuntimeException}
     */
    private RuntimeException createException(MutableAssaultConfig config) {
        String type = config.getExceptionType();
        String message = config.getExceptionMessage();
        try {
            Class<?> clazz = Class.forName(type);
            var ctor = clazz.getConstructor(String.class);
            return (RuntimeException) ctor.newInstance(message);
        } catch (Exception e) {
            LOG.warnf("Goblin: failed to instantiate exception type '%s' (%s), falling back to RuntimeException", type,
                    failureReason(type, e));
            return new RuntimeException(message);
        }
    }

    /**
     * Produces a human-readable explanation of why reflective instantiation of an exception class failed.
     *
     * @param type the fully qualified exception class name that failed to load or instantiate
     * @param e the reflection failure; mapped to a specific reason per exception type
     * @return a formatted reason, e.g. {@code "class 'com.example.X' does not extend RuntimeException"}
     */
    static String failureReason(String type, Exception e) {
        return switch (e) {
            case ClassNotFoundException cnf -> "class '%s' not found".formatted(type);
            case NoSuchMethodException nsm -> "class '%s' has no String constructor".formatted(type);
            case ClassCastException cce -> "class '%s' does not extend RuntimeException".formatted(type);
            case InvocationTargetException ite ->
                "constructor threw %s"
                        .formatted(ite.getCause() == null ? "an exception" : ite.getCause().getClass().getName());
            default -> e.getMessage();
        };
    }
}
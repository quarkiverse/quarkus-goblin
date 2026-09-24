package io.quarkiverse.goblin;

/**
 * Where an assault was injected, carried by every {@link AssaultEngine.AssaultRecord} so observers (metrics, traces)
 * never have to infer it from the free-form history identifier.
 */
public enum AssaultSource {

    /**
     * Inbound REST endpoint ({@link ChaosLayer#HTTP_IN}), and any custom assault recorded without an explicit source.
     */
    SERVER("server"),

    /**
     * Application bean invocation ({@link ChaosLayer#SERVICE}).
     */
    SERVICE("service"),

    /**
     * Outgoing MicroProfile / Quarkus REST Client call ({@link ChaosLayer#HTTP_OUT}).
     */
    REST_CLIENT("rest-client"),

    /**
     * Outgoing Vert.x WebClient call ({@link ChaosLayer#HTTP_OUT}).
     */
    WEBCLIENT("webclient"),

    /**
     * JDBC connection acquisition ({@link ChaosLayer#DATABASE}).
     */
    DATABASE("database"),

    /**
     * {@code @Incoming} message consumer ({@link ChaosLayer#MESSAGING}).
     */
    MESSAGING("messaging");

    private final String tag;

    AssaultSource(String tag) {
        this.tag = tag;
    }

    /**
     * @return the stable lower-case value used as metric tag and span attribute, e.g. {@code "rest-client"}
     */
    public String tag() {
        return tag;
    }
}

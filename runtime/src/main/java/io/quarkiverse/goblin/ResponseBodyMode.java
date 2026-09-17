package io.quarkiverse.goblin;

/**
 * Transformation applied to the response body by the response body assault.
 * <p>
 * {@link #TRUNCATE} keeps only the first {@code percentage}% of the original body (a JSON client reading it fails to
 * parse). {@link #INFLATE} pads the body so the final payload is {@code percentage}% of the original size, simulating
 * an oversized response.
 */
public enum ResponseBodyMode {

    /**
     * Cuts the response body at the configured percentage of its original length.
     */
    TRUNCATE,

    /**
     * Pads the response body up to the configured percentage of its original length.
     */
    INFLATE
}
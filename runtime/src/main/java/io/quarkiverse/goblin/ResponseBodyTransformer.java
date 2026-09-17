package io.quarkiverse.goblin;

import java.nio.charset.StandardCharsets;

/**
 * Applies the {@link ResponseBodyMode} transformation to a raw response body.
 * <p>
 * The body is treated as bytes; {@link #transform(byte[], ResponseBodyMode, int)} returns a new array whose size is the
 * requested percentage of the original, rounded down. {@link ResponseBodyMode#TRUNCATE} keeps only the first
 * {@code percentage}% bytes, {@link ResponseBodyMode#INFLATE} pads the body with a fixed marker up to
 * {@code percentage}% of the original size.
 */
public final class ResponseBodyTransformer {

    private static final byte[] PADDING = "[goblin-response-inflated]".getBytes(StandardCharsets.UTF_8);

    private ResponseBodyTransformer() {
    }

    /**
     * Transforms a response body according to the configured mode and target size.
     * <p>
     * The target size is {@code floor(body.length * percentage / 100)}, i.e. rounded down so the emitted payload never
     * exceeds the requested percentage. A non-zero percentage always keeps at least one byte, while a percentage of
     * {@code 0} produces an empty body. When the computed target is not greater than the original length, both modes
     * return the body unchanged (a truncation at or above {@code 100}% and an inflation at or below {@code 100}%).
     *
     * @param body the original response body bytes, never {@code null}
     * @param mode the transformation to apply, never {@code null}
     * @param percentage the target size in percent of the original length
     * @return the transformed body; an empty array when the body is empty
     */
    public static byte[] transform(byte[] body, ResponseBodyMode mode, int percentage) {
        if (body.length == 0) {
            return body;
        }
        long targetLength = Math.max(1, (long) Math.floor(body.length * (percentage / 100.0)));
        if (percentage <= 0) {
            targetLength = 0;
        }
        if (mode == ResponseBodyMode.TRUNCATE) {
            if (targetLength >= body.length) {
                return body;
            }
            byte[] truncated = new byte[(int) targetLength];
            System.arraycopy(body, 0, truncated, 0, truncated.length);
            return truncated;
        }
        if (targetLength <= body.length) {
            return body;
        }
        byte[] inflated = new byte[(int) targetLength];
        System.arraycopy(body, 0, inflated, 0, body.length);
        int offset = body.length;
        while (offset < inflated.length) {
            int copy = Math.min(PADDING.length, inflated.length - offset);
            System.arraycopy(PADDING, 0, inflated, offset, copy);
            offset += copy;
        }
        return inflated;
    }
}
package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResponseBodyTransformer}, verifying the truncate and inflate byte transformations and their
 * edge cases.
 */
class ResponseBodyTransformerTest {

    private static final byte[] BODY = "{\"name\":\"goblin\",\"size\":12345}".getBytes(StandardCharsets.UTF_8);

    @Test
    void truncateKeepsRequestedPercentage() {
        byte[] result = ResponseBodyTransformer.transform(BODY, ResponseBodyMode.TRUNCATE, 50);
        assertEquals(BODY.length / 2, result.length);
        assertArrayEquals(java.util.Arrays.copyOf(BODY, BODY.length / 2), result);
    }

    @Test
    void truncateAt100ReturnsOriginal() {
        byte[] result = ResponseBodyTransformer.transform(BODY, ResponseBodyMode.TRUNCATE, 100);
        assertArrayEquals(BODY, result);
    }

    @Test
    void truncateAt0ReturnsEmpty() {
        byte[] result = ResponseBodyTransformer.transform(BODY, ResponseBodyMode.TRUNCATE, 0);
        assertEquals(0, result.length);
    }

    @Test
    void inflatePadsUpToPercentage() {
        byte[] result = ResponseBodyTransformer.transform(BODY, ResponseBodyMode.INFLATE, 200);
        assertEquals(BODY.length * 2, result.length);
        assertArrayEquals(BODY, java.util.Arrays.copyOf(result, BODY.length),
                "inflated body must start with the original payload");
    }

    @Test
    void inflateOver150KeepsOriginalPrefixThenPadding() {
        byte[] result = ResponseBodyTransformer.transform(BODY, ResponseBodyMode.INFLATE, 150);
        assertEquals((long) Math.floor(BODY.length * 1.5), result.length);
        assertArrayEquals(BODY, java.util.Arrays.copyOf(result, BODY.length));
    }

    @Test
    void targetLengthIsRoundedDownForBothModes() {
        byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);

        byte[] truncated = ResponseBodyTransformer.transform(body, ResponseBodyMode.TRUNCATE, 50);
        assertEquals(5, truncated.length, "floor(11 * 0.5) = 5");
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), truncated);

        byte[] inflated = ResponseBodyTransformer.transform(body, ResponseBodyMode.INFLATE, 150);
        assertEquals(16, inflated.length, "floor(11 * 1.5) = 16");
        assertArrayEquals(body, java.util.Arrays.copyOf(inflated, body.length));
    }

    @Test
    void nonZeroPercentageKeepsAtLeastOneByte() {
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        assertEquals(1, ResponseBodyTransformer.transform(body, ResponseBodyMode.TRUNCATE, 1).length);
        assertEquals(1, ResponseBodyTransformer.transform(body, ResponseBodyMode.TRUNCATE, 50).length);
    }

    @Test
    void inflateAt100ReturnsOriginal() {
        byte[] result = ResponseBodyTransformer.transform(BODY, ResponseBodyMode.INFLATE, 100);
        assertArrayEquals(BODY, result);
    }

    @Test
    void emptyBodyStaysEmpty() {
        byte[] body = new byte[0];
        assertArrayEquals(body, ResponseBodyTransformer.transform(body, ResponseBodyMode.TRUNCATE, 50));
        assertArrayEquals(body, ResponseBodyTransformer.transform(body, ResponseBodyMode.INFLATE, 200));
    }
}
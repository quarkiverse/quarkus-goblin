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
        assertEquals(Math.round(BODY.length * 1.5), result.length);
        assertArrayEquals(BODY, java.util.Arrays.copyOf(result, BODY.length));
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
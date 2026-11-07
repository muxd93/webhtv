package com.fongmi.android.tv.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

public class WebDebugCaptureTest {

    @Test
    public void disabledCaptureReturnsEmptyAndNeverCopiesBody() throws Exception {
        CountingBody body = new CountingBody("{\"keyword\":\"demo\",\"page\":1}");
        Request request = new Request.Builder().url("https://example.com/api").post(body).build();
        assertEquals("", WebDebugCapture.request(request, false));
        assertEquals(0, body.writes);
    }

    @Test
    public void enabledCaptureIncludesHeadersAndBodyPreview() throws Exception {
        CountingBody body = new CountingBody("{\"keyword\":\"demo\"}");
        Request request = new Request.Builder().url("https://example.com/api").header("X-Test", "1").post(body).build();
        String detail = WebDebugCapture.request(request, true);
        assertTrue(detail.contains("Request Headers"));
        assertTrue(detail.contains("X-Test: 1"));
        assertTrue(detail.contains("payload:"));
        assertTrue(detail.contains("{\"keyword\":\"demo\"}"));
        assertEquals(1, body.writes);
    }

    private static final class CountingBody extends RequestBody {

        private final String text;
        private int writes;

        CountingBody(String text) {
            this.text = text;
        }

        @Override
        public MediaType contentType() {
            return MediaType.parse("application/json");
        }

        @Override
        public long contentLength() {
            return text.length();
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            writes++;
            sink.writeUtf8(text);
        }
    }
}

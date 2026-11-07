package com.fongmi.android.tv.web;

import java.nio.charset.StandardCharsets;

import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** 桥接调试图层的网络详情文本：调试关闭时直接返回空串，避免为调试面板复制请求体 */
public class WebDebugCapture {

    private static final int PREVIEW_LIMIT = 2000;

    public static String request(Request request, boolean enabled) {
        if (!enabled) return "";
        StringBuilder builder = new StringBuilder();
        builder.append("Request Headers\n").append(request.headers());
        RequestBody body = request.body();
        if (body != null) builder.append("Body\n").append("contentType=").append(body.contentType()).append('\n').append("contentLength=").append(contentLength(body)).append('\n').append(bodyPreview(body));
        return builder.toString().trim();
    }

    public static String response(Response response, boolean enabled) {
        if (!enabled) return "";
        return ("message=" + response.message() + "\nResponse Headers\n" + response.headers()).trim();
    }

    private static long contentLength(RequestBody body) {
        try {
            return body.contentLength();
        } catch (Throwable e) {
            return -1;
        }
    }

    private static String bodyPreview(RequestBody body) {
        try {
            okio.Buffer buffer = new okio.Buffer();
            body.writeTo(buffer);
            String text = buffer.readString(StandardCharsets.UTF_8);
            return "payload:\n" + (text.length() > PREVIEW_LIMIT ? text.substring(0, PREVIEW_LIMIT) + "\n...truncated" : text);
        } catch (Throwable e) {
            return "payloadPreview=" + e.getMessage();
        }
    }
}

package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.player.AdFilterController;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/** Serves a pre-purified VOD playlist from the URL-keyed cache; tokens are idempotent within the cache TTL. */
public class VodM3u8 implements Process {

    private static final String MIME_M3U8 = "application/vnd.apple.mpegurl; charset=utf-8";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return "/vodm3u8".equals(url);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        String text = AdFilterController.serve(session.getParms().get("t"));
        if (TextUtils.isEmpty(text)) return Nano.error(Response.Status.BAD_REQUEST, "Unknown or expired playlist token");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Response response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_M3U8, new ByteArrayInputStream(bytes), bytes.length);
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.addHeader("Pragma", "no-cache");
        return response;
    }
}

package com.fred.realweargpt;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class LiveProtocolTest {
    @Test public void sessionUsesLiveProtocolAndKeepsRequestedModels() throws Exception {
        JSONObject event = LiveProtocol.start("gpt-live-1", "gpt-6-sol", "marin", new JSONArray(), true);
        JSONObject session = event.getJSONObject("session");
        assertEquals("session.start", event.getString("type"));
        assertEquals("gpt-live-1", session.getString("model"));
        assertEquals("gpt-6-sol", session.getJSONObject("delegation").getJSONObject("responses").getString("model"));
        assertFalse(session.getBoolean("store"));
        assertFalse(session.has("modalities"));
        assertFalse(session.has("turn_detection"));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsOddPcmByteCount() throws Exception {
        LiveProtocol.audio(new byte[]{0, 1, 2}, 3);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsOversizedCameraPayload() throws Exception {
        byte[] image = new byte[LiveProtocol.MAX_JPEG_BYTES + 1];
        image[0] = (byte)255; image[1] = (byte)216;
        LiveProtocol.imageItem(image, "Test");
    }

    @Test public void toolResultRetainsCallerAndUsesStringOutput() throws Exception {
        JSONObject caller = new JSONObject().put("type", "direct");
        JSONObject event = LiveProtocol.toolResult("call_1", new JSONObject().put("ok", true), caller);
        JSONObject item = event.getJSONObject("item");
        assertEquals("response.item.create", event.getString("type"));
        assertEquals("function_call_output", item.getString("type"));
        assertEquals("direct", item.getJSONObject("caller").getString("type"));
        assertTrue(new JSONObject(item.getString("output")).getBoolean("ok"));
    }
}

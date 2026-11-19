package com.fred.realweargpt;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.*;

/** No paid API calls: a local WebSocket server exercises real transport ordering. */
public class LiveClientTest {
    private MockWebServer server;
    private LiveClient client;
    private final AtomicReference<WebSocket> peer = new AtomicReference<>();
    private final BlockingQueue<JSONObject> requests = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> calls = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> transcripts = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> sources = new LinkedBlockingQueue<>();
    private final BlockingQueue<Boolean> closed = new LinkedBlockingQueue<>();
    private final CountDownLatch ready = new CountDownLatch(1);

    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) { peer.set(socket); }
            @Override public void onMessage(WebSocket socket, String message) {
                try { requests.add(new JSONObject(message)); }
                catch (Exception e) { throw new AssertionError(e); }
            }
        }));
        server.start();
        client = new LiveClient(new LiveClient.Listener() {
            @Override public void onReady() { ready.countDown(); }
            @Override public void onToolCall(String callId, String name, JSONObject args, String delegation) { calls.add(callId); }
            @Override public void onTranscript(String role, String delta) { transcripts.add(role + ":" + delta); }
            @Override public void onError(String message) { errors.add(message); }
            @Override public void onClosed(String reason, boolean finalized) { closed.add(finalized); }
            @Override public void onSource(String title, String url) { sources.add(url); }
        }, new OkHttpClient.Builder().retryOnConnectionFailure(false).build(), server.url("/v1/live/sessions").toString());
    }

    @After public void teardown() throws Exception {
        client.dispose();
        // Server-side MockWebServer sockets have no HTTP Call; cancel() dereferences null.
        if (peer.get() != null) peer.get().close(1000, "Test complete");
        server.shutdown();
    }

    private JSONObject next() throws Exception {
        JSONObject event = requests.poll(3, TimeUnit.SECONDS);
        assertNotNull("Expected a client event", event);
        return event;
    }

    private void start() throws Exception {
        client.connect("sk-local-test-placeholder", "gpt-live-1", "gpt-6-luna", "marin", new JSONArray(), true);
        assertEquals("session.start", next().getString("type"));
        peer.get().send("{\"type\":\"session.started\",\"session\":{\"id\":\"test_session\"}}");
        assertTrue(ready.await(3, TimeUnit.SECONDS));
    }

    private void response(JSONObject nested) throws Exception {
        peer.get().send(new JSONObject().put("type", "response.event").put("delegation_id", "del_test")
                .put("event", nested).toString());
    }

    private void created() throws Exception {
        response(new JSONObject().put("type", "response.created").put("response", new JSONObject().put("id", "resp_test")));
    }

    private void call(String id) throws Exception {
        response(new JSONObject().put("type", "response.output_item.done").put("item", new JSONObject()
                .put("type", "function_call").put("call_id", id).put("name", "display_text").put("arguments", "{\"text\":\"test\"}")));
    }

    private void completed() throws Exception {
        response(new JSONObject().put("type", "response.completed").put("response", new JSONObject().put("id", "resp_test")
                .put("output", new JSONArray())));
    }

    @Test public void waitsForSessionStartedBeforeSendingMedia() throws Exception {
        client.connect("sk-local-test-placeholder", "gpt-live-1", "gpt-6-luna", "marin", new JSONArray(), true);
        JSONObject start = next();
        assertEquals("session.start", start.getString("type"));
        assertFalse(client.appendAudio(new byte[]{0, 1, 2, 3}, 4));
        assertNull(requests.poll(100, TimeUnit.MILLISECONDS));
        peer.get().send("{\"type\":\"session.started\",\"session\":{\"id\":\"test_session\"}}");
        assertTrue(ready.await(3, TimeUnit.SECONDS));
        assertTrue(client.appendAudio(new byte[]{0, 1, 2, 3}, 4));
        JSONObject audio = next();
        assertEquals("session.input_audio.append", audio.getString("type"));
        assertEquals("AAECAw==", audio.getString("audio"));
    }

    @Test public void batchesEveryToolResultBeforeSingleContinuation() throws Exception {
        start(); created(); call("call_a"); call("call_b"); completed();
        assertNotNull(calls.poll(3, TimeUnit.SECONDS));
        assertNotNull(calls.poll(3, TimeUnit.SECONDS));
        assertTrue(client.completeTool("call_a", new JSONObject().put("ok", true)));
        assertEquals("call_a", next().getJSONObject("item").getString("call_id"));
        assertNull(requests.poll(100, TimeUnit.MILLISECONDS));
        assertTrue(client.completeTool("call_b", new JSONObject().put("ok", true)));
        assertEquals("call_b", next().getJSONObject("item").getString("call_id"));
        assertEquals("response.create", next().getString("type"));
        assertFalse(client.completeTool("call_b", new JSONObject().put("ok", true)));
        assertNull(requests.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test public void imageAndCameraResultShareOneContinuation() throws Exception {
        start(); created(); call("camera_call"); completed();
        assertEquals("camera_call", calls.poll(3, TimeUnit.SECONDS));
        assertTrue(client.sendImage(new byte[]{(byte)255, (byte)216, (byte)255, (byte)217}, "Lis cette étiquette"));
        JSONObject message = next().getJSONObject("item");
        assertEquals("input_image", message.getJSONArray("content").getJSONObject(1).getString("type"));
        assertNull(requests.poll(100, TimeUnit.MILLISECONDS));
        client.completeTool("camera_call", new JSONObject().put("ok", true));
        assertEquals("function_call_output", next().getJSONObject("item").getString("type"));
        assertEquals("response.create", next().getString("type"));
        assertNull(requests.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test public void duplicateToolEventDoesNotExecuteTwice() throws Exception {
        start(); created(); call("once"); call("once"); completed();
        assertEquals("once", calls.poll(3, TimeUnit.SECONDS));
        assertNull(calls.poll(100, TimeUnit.MILLISECONDS));
        client.completeTool("once", new JSONObject().put("ok", true));
        next();
        assertEquals("response.create", next().getString("type"));
    }

    @Test public void closeWaitsForTerminalEventAndRejectsNewWork() throws Exception {
        start(); client.close();
        assertEquals("session.close", next().getString("type"));
        assertFalse(client.sendText("Ne doit pas être envoyé"));
        assertNull(closed.poll(100, TimeUnit.MILLISECONDS));
        peer.get().send("{\"type\":\"session.closed\",\"reason\":\"close_requested\",\"usage\":{\"seconds\":2.4}}");
        assertEquals(Boolean.TRUE, closed.poll(3, TimeUnit.SECONDS));
        assertFalse(client.isActive());
    }

    @Test public void activityDisposalStillWaitsForServerFinalization() throws Exception {
        start(); client.disposeAfterClose();
        assertEquals("session.close", next().getString("type"));
        assertTrue(client.isActive());
        assertFalse(client.appendAudio(new byte[]{0, 0}, 2));
        assertNull(closed.poll(100, TimeUnit.MILLISECONDS));
        peer.get().send("{\"type\":\"session.closed\",\"reason\":\"close_requested\",\"usage\":{\"seconds\":2.4}}");
        assertEquals(Boolean.TRUE, closed.poll(3, TimeUnit.SECONDS));
        assertFalse(client.isActive());
        client.connect("sk-local-test-placeholder", "gpt-live-1", "gpt-6-luna", "marin", new JSONArray(), false);
        assertNotNull(errors.poll(3, TimeUnit.SECONDS));
        assertFalse(client.isActive());
    }

    @Test public void serverErrorsNeverExposeKeyMaterial() throws Exception {
        start();
        peer.get().send("{\"type\":\"error\",\"error\":{\"code\":\"test\",\"message\":\"Invalid sk-example-DO-NOT-DISPLAY\"}}");
        String error = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull(error);
        assertFalse(error.contains("sk-example"));
        assertFalse(client.isReady());
    }

    @Test public void transcriptsAndActualCitationsAreForwarded() throws Exception {
        start();
        peer.get().send("{\"type\":\"session.input_transcript.delta\",\"delta\":\"Bonjour\",\"start_ms\":0,\"end_ms\":400}");
        assertEquals("user:Bonjour", transcripts.poll(3, TimeUnit.SECONDS));
        response(new JSONObject().put("type", "response.output_text.annotation.added").put("annotation", new JSONObject()
                .put("type", "url_citation").put("url", "https://developers.openai.com/").put("title", "Documentation")));
        assertEquals("https://developers.openai.com/", sources.poll(3, TimeUnit.SECONDS));
    }
}

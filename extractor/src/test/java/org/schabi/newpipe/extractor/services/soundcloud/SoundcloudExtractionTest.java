package org.schabi.newpipe.extractor.services.soundcloud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.services.soundcloud.extractors.SoundcloudStreamExtractor;
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.schabi.newpipe.extractor.ServiceList.SoundCloud;

class SoundcloudExtractionTest {
    private static final String TRACK_URL = "https://soundcloud.com/artist/track";
    private final StubDownloader downloader = new StubDownloader();
    private Downloader previousDownloader;
    private Object previousClientId;
    private Field clientIdField;

    @BeforeEach
    void setUp() throws Exception {
        previousDownloader = NewPipe.getDownloader();
        clientIdField = SoundcloudParsingHelper.class.getDeclaredField("clientId");
        clientIdField.setAccessible(true);
        previousClientId = clientIdField.get(null);
        clientIdField.set(null, null);
        NewPipe.init(downloader);
        downloader.respond("https://soundcloud.com", 200,
                "<script src='https://a-v2.sndcdn.com/assets/app.js'></script>", "");
        downloader.respond("https://a-v2.sndcdn.com/assets/app.js", 200,
                ",client_id:\"test-client\"", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        NewPipe.init(previousDownloader);
        clientIdField.set(null, previousClientId);
    }

    @Test
    void keepsHlsWhenProgressiveRequestFails() throws Exception {
        downloader.respond("https://media.example/hls", 200,
                "{\"url\":\"https://cdn.example/track.m3u8\"}", "");
        final List<AudioStream> streams = extract(
                transcoding("progressive", "mp3_0_1", "progressive"),
                transcoding("hls", "mp3_0_1", "hls"));
        assertEquals(1, streams.size());
        assertEquals(DeliveryMethod.HLS, streams.get(0).getDeliveryMethod());
        assertEquals("https://cdn.example/track.m3u8", streams.get(0).getContent());
    }

    @Test
    void keepsBothProgressiveAndHls() throws Exception {
        downloader.respond("https://media.example/progressive", 200,
                "{\"url\":\"https://cdn.example/track.mp3\"}", "");
        downloader.respond("https://media.example/hls", 200,
                "{\"url\":\"https://cdn.example/track.m3u8\"}", "");
        final List<AudioStream> streams = extract(
                transcoding("progressive", "mp3_0_1", "progressive"),
                transcoding("hls", "mp3_0_1", "hls"));
        assertEquals(List.of(DeliveryMethod.PROGRESSIVE_HTTP, DeliveryMethod.HLS),
                streams.stream().map(AudioStream::getDeliveryMethod).toList());
    }

    @Test
    void usesActualOpusProtocolAndPreservesAac() throws Exception {
        for (final String endpoint : List.of("opus", "opus-hls", "aac")) {
            downloader.respond("https://media.example/" + endpoint, 200,
                    "{\"url\":\"https://cdn.example/" + endpoint + "\"}", "");
        }
        final List<AudioStream> streams = extract(
                transcoding("opus", "opus_0_0", "progressive"),
                transcoding("opus-hls", "opus_0_0", "hls"),
                transcoding("aac", "aac_160k", "hls"),
                transcoding("encrypted", "aac_160k", "ctr-encrypted-hls"));
        assertEquals(3, streams.size());
        assertEquals(DeliveryMethod.PROGRESSIVE_HTTP, streams.get(0).getDeliveryMethod());
        assertEquals(DeliveryMethod.HLS, streams.get(1).getDeliveryMethod());
        assertEquals(160, streams.get(2).getAverageBitrate());
        assertFalse(downloader.requests.stream().anyMatch(url -> url.contains("/encrypted")));
    }

    @Test
    void resolvesShortLinksEvenWhenRedirectReturnsForbidden() throws Exception {
        final String shortUrl = "https://on.soundcloud.com/AbC123?si=tracking";
        final SoundcloudStreamLinkHandlerFactory factory =
                SoundcloudStreamLinkHandlerFactory.getInstance();
        assertTrue(factory.onAcceptUrl(shortUrl));
        assertFalse(factory.onAcceptUrl("https://on.soundcloud.com.evil.example/AbC123"));
        downloader.respond("https://api-widget.soundcloud.com/resolve", 200,
                "{\"id\":9876543210}", "");
        for (final int status : List.of(200, 403)) {
            downloader.respond("https://on.soundcloud.com/AbC123", status, "",
                    TRACK_URL + "/?utm_source=share");
            assertEquals("9876543210", factory.getId(shortUrl));
        }
        assertTrue(downloader.requests.contains("HEAD " + shortUrl));
        assertTrue(downloader.requests.contains("GET https://api-widget.soundcloud.com/resolve"
                + "?url=https%3A%2F%2Fsoundcloud.com%2Fartist%2Ftrack"
                + "&format=json&client_id=test-client"));
    }

    @Test
    void rejectsShortLinkServerErrors() {
        downloader.respond("https://on.soundcloud.com/AbC123", 503, "", TRACK_URL);
        assertThrows(ParsingException.class, () ->
                SoundcloudParsingHelper.resolveIdWithWidgetApi("https://on.soundcloud.com/AbC123"));
        assertEquals(List.of("HEAD https://on.soundcloud.com/AbC123"), downloader.requests);
    }

    private List<AudioStream> extract(final String... transcodings) throws Exception {
        final String track = "{\"policy\":\"ALLOW\",\"streamable\":true,"
                + "\"track_authorization\":\"test-auth\",\"media\":{\"transcodings\":["
                + String.join(",", transcodings) + "]}}";
        downloader.respond("https://api-v2.soundcloud.com/resolve", 200, track, "");
        final SoundcloudStreamExtractor extractor = new SoundcloudStreamExtractor(SoundCloud,
                new LinkHandler(TRACK_URL, TRACK_URL, "123"));
        extractor.onFetchPage(downloader);
        final List<AudioStream> streams = extractor.getAudioStreams();
        assertTrue(downloader.requests.stream().filter(url -> url.contains("media.example"))
                .allMatch(url -> url.endsWith("?client_id=test-client&track_authorization=test-auth")));
        return streams;
    }

    private static String transcoding(final String endpoint, final String preset,
                                      final String protocol) {
        return "{\"url\":\"https://media.example/" + endpoint + "\",\"preset\":\"" + preset
                + "\",\"format\":{\"protocol\":\"" + protocol + "\"}}";
    }

    private static final class StubDownloader extends Downloader {
        private final Map<String, Response> responses = new HashMap<>();
        private final List<String> requests = new ArrayList<>();

        void respond(final String url, final int status, final String body, final String latestUrl) {
            responses.put(url, new Response(status, "", Map.of(), body, null, latestUrl));
        }

        @Override
        public Response execute(final Request request) throws IOException {
            requests.add(request.httpMethod() + " " + request.url());
            final Response response = responses.get(request.url().split("\\?")[0]);
            if (response == null) {
                throw new IOException("No response for " + request.url());
            }
            return response;
        }

        @Override
        public CancellableCall executeAsync(final Request request, final AsyncCallback callback) {
            throw new UnsupportedOperationException();
        }
    }
}

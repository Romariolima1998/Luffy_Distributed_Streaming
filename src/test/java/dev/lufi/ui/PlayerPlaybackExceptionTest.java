package dev.lufi.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerPlaybackExceptionTest {
    @Test
    void preservesTheBackendErrorCode() {
        PlayerPlaybackException failure = new PlayerPlaybackException(PlayerErrorCode.MEDIA_DECODE_FAILED,
                "decoder recusou o stream");

        assertEquals(PlayerErrorCode.MEDIA_DECODE_FAILED, PlayerPlaybackException.from(failure, "LOCAL_FILE").code());
    }

    @Test
    void classifiesUnexpectedTorrentBridgeFailuresAsHttpFailures() {
        PlayerPlaybackException failure = PlayerPlaybackException.from(new IllegalStateException("HTTP 503"),
                "TORRENT_HTTP");

        assertEquals(PlayerErrorCode.HTTP_STREAM_FAILED, failure.code());
    }

    @Test
    void keepsUnexpectedLocalFailureDistinctFromLibVlcFailures() {
        PlayerPlaybackException failure = PlayerPlaybackException.from(new IllegalStateException("falha local"),
                "LOCAL_FILE");

        assertEquals(PlayerErrorCode.UNKNOWN, failure.code());
    }
}

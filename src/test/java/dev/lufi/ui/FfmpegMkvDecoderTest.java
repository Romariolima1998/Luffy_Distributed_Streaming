package dev.lufi.ui;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FfmpegMkvDecoderTest {
    @TempDir Path temporaryDirectory;

    @Test void readsFramesFromAnMkvContainer() throws Exception {
        Path mkv = temporaryDirectory.resolve("ola-luffy.mkv");
        BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_3BYTE_BGR);
        image.setRGB(0, 0, Color.ORANGE.getRGB());

        try (Java2DFrameConverter converter = new Java2DFrameConverter();
             FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(mkv.toFile(), 32, 24)) {
            recorder.setFormat("matroska");
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
            recorder.setFrameRate(5);
            recorder.start();
            recorder.record(converter.convert(image));
            recorder.stop();
        }

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mkv.toFile())) {
            grabber.start();
            Frame decoded = grabber.grabImage();
            assertNotNull(decoded);
            assertNotNull(decoded.image);
            assertEquals(32, grabber.getImageWidth());
            assertEquals(24, grabber.getImageHeight());
            grabber.stop();
        }
    }
}

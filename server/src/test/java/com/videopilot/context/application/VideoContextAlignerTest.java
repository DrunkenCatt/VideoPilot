package com.videopilot.context.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoContextAlignerTest {

    private final VideoContextAligner aligner = new VideoContextAligner(new ObjectMapper(), 60_000);

    @Test
    void coversTheTrustedDurationAndClampsObservationsIntoOrderedWindows() {
        var result = aligner.align(new VideoContextAligner.Input(
                125_000,
                List.of(
                        new VideoContextAligner.Transcript(-500, 61_000, " opening ", "asr/0.json"),
                        new VideoContextAligner.Transcript(120_000, 130_000, "ending", "asr/2.json")),
                List.of(
                        new VideoContextAligner.OcrObservation(60_000, "board", 0.91, "ocr/1.json"),
                        new VideoContextAligner.OcrObservation(140_000, "outside", 0.99, "ocr/out.json")),
                List.of(
                        new VideoContextAligner.Keyframe(-10, "frames/first.jpg", "a".repeat(64)),
                        new VideoContextAligner.Keyframe(124_999, "frames/last.jpg", "b".repeat(64)))));

        assertThat(result).hasSize(3);
        assertThat(result).extracting(segment -> List.of(segment.startMs(), segment.endMs()))
                .containsExactly(List.of(0L, 60_000L), List.of(60_000L, 120_000L), List.of(120_000L, 125_000L));
        assertThat(result).extracting(segment -> segment.ordinal()).containsExactly(0, 1, 2);
        assertThat(result.getFirst().asrText()).contains("opening");
        assertThat(result.get(1).asrText()).contains("opening");
        assertThat(result.getLast().asrText()).contains("ending");
        assertThat(result.getFirst().ocrObservations()).doesNotContain("board");
        assertThat(result.get(1).ocrObservations()).contains("board", "ocr/1.json");
        assertThat(result.getLast().ocrObservations()).contains("outside", "ocr/out.json");
        assertThat(result.getFirst().keyframes()).contains("frames/first.jpg");
        assertThat(result.getLast().keyframes()).contains("frames/last.jpg");
        assertThat(result).allSatisfy(segment -> assertThat(segment.contentDigest()).matches("[0-9a-f]{64}"));
    }

    @Test
    void producesStableSegmentsForTheSameInputs() {
        var input = new VideoContextAligner.Input(
                1_000,
                List.of(new VideoContextAligner.Transcript(0, 1_000, "lesson", "asr.json")),
                List.of(),
                List.of());

        assertThat(aligner.align(input)).isEqualTo(aligner.align(input));
    }
}

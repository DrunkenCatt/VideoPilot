package com.videopilot.infrastructure.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class M5ReleaseDatasetLoaderTest {

    @TempDir
    Path directory;

    @Test
    void bindsFrozenCasesToRuntimeJobsAndPublishedSegments() throws Exception {
        Path manifest = write("manifest.json", """
                {"datasetId":"00000000-0000-0000-0000-000000000601","dataVersion":"0.2.0",
                 "split":"dev","status":"frozen","datasetFingerprint":"%s",
                 "frozenAt":"2026-11-09T13:30:45Z","videos":[{"videoRef":"video-a"}]}
                """.formatted("a".repeat(64)));
        Path retrieval = write("retrieval.jsonl",
                "{\"caseId\":\"r1\",\"question\":\"定位问题\",\"expectedHits\":[{\"segmentId\":\"00000000-0000-0000-0000-000000000611\"}],\"review\":{\"status\":\"approved\",\"reviewerCount\":2}}\n");
        Path answers = write("answer.jsonl",
                "{\"caseId\":\"a1\",\"question\":\"回答问题\",\"answerability\":\"answerable\",\"requiredFacts\":[\"事实\"],\"forbiddenClaims\":[\"错误\"],\"evidenceChannels\":[\"asr\"],\"requiredCitations\":[{\"startMs\":1000,\"endMs\":2000}],\"review\":{\"status\":\"approved\",\"reviewerCount\":2}}\n");
        Path index = write("index.jsonl",
                "{\"caseId\":\"r1\",\"videoRef\":\"video-a\",\"category\":\"exact\"}\n"
                        + "{\"caseId\":\"a1\",\"videoRef\":\"video-a\",\"category\":\"answer\"}\n");
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000612");
        UUID answerSegment = UUID.fromString("00000000-0000-0000-0000-000000000613");

        var loaded = new M5ReleaseDatasetLoader(new ObjectMapper()).load(
                manifest, retrieval, answers, index, Map.of("video-a", jobId),
                Map.of("video-a", List.of(new M5ReleaseDatasetLoader.SegmentWindow(
                        answerSegment, 0, 60_000))));

        assertThat(loaded.command().sourceFingerprint()).isEqualTo("a".repeat(64));
        assertThat(loaded.command().videoCount()).isEqualTo(1);
        assertThat(loaded.jsonLines()).hasSize(2);
        assertThat(loaded.jsonLines()).allSatisfy(line -> assertThat(line).contains(jobId.toString()));
        assertThat(loaded.jsonLines().get(1)).contains(answerSegment.toString(), "\"allowedChannels\":[\"asr\"]");
    }

    private Path write(String name, String content) throws Exception {
        Path path = directory.resolve(name);
        Files.writeString(path, content);
        return path;
    }
}

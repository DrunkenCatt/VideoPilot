package com.videopilot.infrastructure.context.derivation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class FakeFfmpegMain {

    private FakeFfmpegMain() {
    }

    public static void main(String[] args) throws Exception {
        Path audio = pattern(args, "audio-%06d.wav").resolveSibling("audio-000000.wav");
        Path frame = pattern(args, "frame-%06d.jpg").resolveSibling("frame-000000.jpg");
        Files.createDirectories(audio.getParent());
        Files.writeString(audio.getParent().resolve("child.pid"), Long.toString(ProcessHandle.current().pid()));
        if ("timeout".equals(System.getProperty("fake.mode"))) {
            Thread.sleep(30_000);
            return;
        }
        Files.write(audio, new byte[] {1, 2, 3});
        Files.write(frame, new byte[] {4, 5, 6});
        System.err.println("[Parsed_showinfo_0] n:0 pts:0 pts_time:0.0");
    }

    private static Path pattern(String[] args, String suffix) {
        return Arrays.stream(args)
                .filter(value -> value.endsWith(suffix))
                .map(Path::of)
                .findFirst()
                .orElseThrow();
    }
}

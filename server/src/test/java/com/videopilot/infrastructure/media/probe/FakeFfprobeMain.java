package com.videopilot.infrastructure.media.probe;

import java.nio.file.Files;
import java.nio.file.Path;

public final class FakeFfprobeMain {

    private FakeFfprobeMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = Files.readString(Path.of(args[args.length - 1]));
        switch (mode) {
            case "valid" -> System.out.print("""
                    {"streams":[
                      {"codec_type":"audio","codec_name":"aac"},
                      {"codec_type":"video","codec_name":"h264","width":1920,"height":1080}
                    ],"format":{"format_name":"mov,mp4","duration":"61.250000"}}
                    """);
            case "audio-only" -> System.out.print("""
                    {"streams":[{"codec_type":"audio","codec_name":"aac"}],
                     "format":{"format_name":"mp4","duration":"5.0"}}
                    """);
            case "invalid-json" -> System.out.print("not-json");
            case "too-large" -> System.out.print("x".repeat(4_096));
            case "timeout" -> Thread.sleep(5_000);
            default -> System.exit(1);
        }
    }
}

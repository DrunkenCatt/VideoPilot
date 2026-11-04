package com.videopilot.infrastructure.context.ocr;

import java.nio.file.Files;
import java.nio.file.Path;

public final class FakeTesseractMain {

    private FakeTesseractMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("fake.mode", "success");
        if (java.util.Arrays.asList(args).contains("--version")) {
            System.out.println("tesseract 5.5.1");
            return;
        }
        if (mode.equals("timeout")) {
            Path.of(args[0]).resolveSibling("child.pid").toFile().deleteOnExit();
            Files.writeString(Path.of(args[0]).resolveSibling("child.pid"), Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(30_000);
            return;
        }
        if (mode.equals("overflow")) {
            System.out.print("x".repeat(32_000));
            return;
        }
        if (mode.equals("bad-image")) System.exit(1);
        System.out.println("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext");
        System.out.println("5\t1\t1\t1\t1\t1\t10\t20\t100\t30\t96.5\tVideoPilot");
        System.out.println("5\t1\t1\t1\t1\t2\t115\t20\t60\t30\t-1\tignored");
    }
}

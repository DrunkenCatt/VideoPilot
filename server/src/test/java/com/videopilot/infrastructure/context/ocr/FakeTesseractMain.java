package com.videopilot.infrastructure.context.ocr;

import java.nio.file.Files;
import java.nio.file.Path;

public final class FakeTesseractMain {

    private FakeTesseractMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("fake.mode", "success");
        if (java.util.Arrays.asList(args).contains("--version")) {
            if (mode.equals("version-fail")) System.exit(2);
            System.out.println("tesseract 5.5.1");
            return;
        }
        if (mode.equals("timeout")) {
            Files.writeString(Path.of(args[0]).resolveSibling("child.pid"), Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(30_000);
            return;
        }
        if (mode.equals("overflow")) {
            System.out.print("x".repeat(32_000));
            return;
        }
        if (mode.equals("text-limit")) {
            System.out.println("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext");
            System.out.println("5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t90\t" + "x".repeat(2_000));
            return;
        }
        if (mode.equals("invalid-tsv")) {
            System.out.println("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext");
            System.out.println("5\t1\t1\t1\t1\t1\tleft\t0\t10\t10\t90\tbroken");
            return;
        }
        if (mode.equals("bad-image")) System.exit(1);
        System.out.println("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext");
        System.out.println("5\t1\t1\t1\t1\t1\t10\t20\t100\t30\t96.5\tVideoPilot");
        System.out.println("5\t1\t1\t1\t1\t2\t115\t20\t60\t30\t-1\tignored");
    }
}

package io.github.lumiagg.gestureevo.utils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A real child process for verifying pipe, encoding, exit, environment and stdin behavior. */
public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "large":
                byte[] block = new byte[256 * 1024];
                Arrays.fill(block, (byte) 'x');
                System.out.write(block);
                System.err.write(block);
                break;
            case "fail":
                System.out.print("partial output");
                System.err.print("command failed");
                System.exit(7);
                break;
            case "unicode":
                System.out.write("中文图标\n".getBytes(StandardCharsets.UTF_8));
                break;
            case "environment":
                System.out.print(System.getenv("GESTURE_EVO_TEST_VALUE") + "|" + System.getProperty("user.dir"));
                break;
            case "stdin":
                System.out.print(System.in.read());
                break;
            case "wait":
                Thread.sleep(30000);
                break;
            default:
                throw new IllegalArgumentException(args[0]);
        }
    }
}

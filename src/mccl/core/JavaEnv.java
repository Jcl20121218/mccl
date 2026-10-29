package mccl.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** Java 运行环境探测：优先程序目录内便携 JRE，其次系统 PATH。 */
public final class JavaEnv {
    private JavaEnv() {}

    public static String locate() {
        Path local = Loc.jreBin();
        if (Files.exists(local)) return local.toString();
        try {
            ProcessBuilder pb = new ProcessBuilder("java", "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor() == 0) return "java";
        } catch (Exception ignored) {}
        return null;
    }
}

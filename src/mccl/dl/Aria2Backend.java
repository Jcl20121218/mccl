package mccl.dl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * aria2 可选加速后端（绿色便携，不打包）。
 * 探测顺序：1) 程序目录 tools/aria2/aria2c.exe   2) 系统 PATH 的 aria2c
 * 找不到时由 Downloader 自动回退到内置 Java 下载器。
 */
public class Aria2Backend {

    /** 找到 aria2c 绝对路径则返回，否则 null。 */
    public static String locate() {
        Path local = mccl.core.Loc.aria2Exe();
        if (Files.exists(local)) return local.toString();
        try {
            ProcessBuilder pb = new ProcessBuilder("aria2c", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor() == 0) return "aria2c";
        } catch (Exception ignored) {}
        return null;
    }

    public static boolean available() { return locate() != null; }

    public static boolean download(List<DownloadTask> tasks, int threads, boolean dryRun) {
        String exe = locate();
        if (exe == null) return false;
        try {
            StringBuilder input = new StringBuilder();
            for (DownloadTask t : tasks) {
                input.append(t.url()).append('\n');
                input.append("  out=").append(t.outPath()).append('\n');
                if (t.sha1() != null && !t.sha1().isBlank())
                    input.append("  checksum=sha1=").append(t.sha1()).append('\n');
                input.append('\n');
            }
            Path inputFile = mccl.core.Loc.cache().resolve("aria2-input.txt");
            Files.createDirectories(inputFile.getParent());
            Files.writeString(inputFile, input.toString());

            if (dryRun) {
                System.out.println("  [aria2][dry] 任务清单 (" + tasks.size() + " 条):");
                System.out.println("  " + inputFile);
                System.out.print(input);
                return true;
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(exe);
            cmd.add("-x"); cmd.add(String.valueOf(Math.max(1, threads)));
            cmd.add("-s"); cmd.add(String.valueOf(Math.max(1, threads)));
            cmd.add("--file-allocation=none");
            cmd.add("--input-file=" + inputFile.toAbsolutePath());
            cmd.add("--console-log-level=notice");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            Process p = pb.start();
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            System.err.println("  aria2 调用失败: " + e.getMessage());
            return false;
        }
    }
}

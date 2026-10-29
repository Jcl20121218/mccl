package mccl.dl;

import java.util.List;

/**
 * 统一下载入口。backend: auto(默认) / aria2 / java。
 * auto 时优先 aria2（若可用），否则回退自研 Java 下载器。
 */
public final class Downloader {
    private Downloader() {}

    public static boolean run(List<DownloadTask> tasks, String backend, int threads, boolean dryRun) {
        if (tasks.isEmpty()) { System.out.println("  没有需要下载的文件。"); return true; }
        String b = (backend == null || backend.isBlank()) ? "auto" : backend.toLowerCase();
        boolean usedAria = false;
        if (b.equals("auto") || b.equals("aria2")) {
            if (Aria2Backend.available()) {
                usedAria = true;
                boolean ok = Aria2Backend.download(tasks, threads, dryRun);
                if (ok) return true;
                if (b.equals("aria2")) {
                    System.err.println("  aria2 后端执行失败。");
                    return false;
                }
                // auto 失败回退 java
                System.out.println("  aria2 失败，回退到内置 Java 下载器。");
            } else if (b.equals("aria2")) {
                System.err.println("  未检测到 aria2c.exe（tools/aria2 或 PATH 中均无）。");
                return false;
            }
        }
        if (b.equals("java") || b.equals("auto")) {
            boolean all = true;
            for (DownloadTask t : tasks) {
                try {
                    if (!JavaDownloadBackend.download(t, threads, dryRun)) all = false;
                } catch (Exception e) {
                    System.err.println("  下载异常: " + e.getMessage());
                    all = false;
                }
            }
            return all;
        }
        System.err.println("  未知后端: " + backend);
        return false;
    }
}

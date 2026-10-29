package mccl.dl;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 自研纯 JDK 多线程下载后端：Range 分块并发、断点续传(.part)、sha1 校验。
 * 仅依赖 JDK，是 MCCL 默认的开箱即用下载核心。
 */
public class JavaDownloadBackend {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** 返回 true 表示文件已存在且校验通过（无需再下）。 */
    public static boolean checkExisting(DownloadTask t) {
        if (Files.exists(t.outPath()) && t.sha1() != null && !t.sha1().isBlank()) {
            return verifySha1(t.outPath(), t.sha1());
        }
        return false;
    }

    public static boolean download(DownloadTask t, int threads, boolean dryRun) throws Exception {
        Path target = t.outPath();
        if (checkExisting(t)) {
            System.out.println("  已存在且校验通过: " + relative(target));
            return true;
        }
        if (dryRun) {
            System.out.printf("  [java][dry] %s -> %s sha1=%s%n", t.url(), relative(target), t.sha1());
            return true;
        }
        Files.createDirectories(target.getParent());
        long total = getRemoteLength(t.url());
        if (total <= 0) {
            System.out.println("  无法获取远程大小，改用单线程: " + t.url());
            return singleDownload(t.url(), target, t.sha1());
        }

        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(target);
        Files.deleteIfExists(part);
        try (FileChannel fc = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            fc.truncate(total);
        }

        int n = Math.max(1, threads);
        long block = total / n;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        for (int i = 0; i < n; i++) {
            long start = i * block;
            long end = (i == n - 1) ? total - 1 : (i + 1) * block - 1;
            final long s = start, e = end;
            pool.submit(() -> downloadBlock(t.url(), part, s, e));
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.MINUTES);

        if (t.sha1() != null && !t.sha1().isBlank() && !verifySha1(part, t.sha1())) {
            System.err.println("  SHA1 校验失败，保留 .part 以便断点: " + relative(part));
            return false;
        }
        Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("  完成: " + relative(target));
        return true;
    }

    private static void downloadBlock(String url, Path part, long start, long end) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Range", "bytes=" + start + "-" + end).GET().build();
            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body(); FileChannel fc = FileChannel.open(part, StandardOpenOption.WRITE)) {
                byte[] buf = new byte[1 << 16];
                int n;
                long pos = start;
                while ((n = in.read(buf)) > 0) {
                    fc.write(ByteBuffer.wrap(buf, 0, n), pos);
                    pos += n;
                }
            }
        } catch (Exception e) {
            System.err.println("  分块失败 " + start + "-" + end + ": " + e.getMessage());
        }
    }

    private static boolean singleDownload(String url, Path target, String sha1) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (sha1 != null && !sha1.isBlank() && !verifySha1(target, sha1)) {
                System.err.println("  SHA1 校验失败: " + relative(target));
                return false;
            }
            System.out.println("  完成(单线程): " + relative(target));
            return true;
        } catch (Exception e) {
            System.err.println("  下载失败: " + e.getMessage());
            return false;
        }
    }

    private static long getRemoteLength(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("Range", "bytes=0-0").GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String cr = resp.headers().firstValue("Content-Range").orElse(null);
            if (cr != null) {
                int idx = cr.indexOf('/');
                if (idx >= 0) return Long.parseLong(cr.substring(idx + 1).trim());
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static boolean verifySha1(Path p, String expected) {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(expected);
        } catch (Exception e) {
            return false;
        }
    }

    private static String relative(Path p) {
        try { return mccl.core.Loc.base().relativize(p).toString(); } catch (Exception e) { return p.toString(); }
    }
}

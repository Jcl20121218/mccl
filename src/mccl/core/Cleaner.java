package mccl.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一键清理游戏垃圾（日志 / 崩溃报告 / 加载器缓存）。
 * 只清可再生的缓存与日志，绝不触碰模组 jar、版本内核、libraries、assets。
 * 流程：列出预览清单 -> 用户 y/n 确认 -> 删除；不做任何后台自动清理。
 */
public final class Cleaner {
    private Cleaner() {}

    /** 判定一个文件名/目录名是否属于可安全清理的垃圾。 */
    private static boolean isGarbageName(String name) {
        String n = name.toLowerCase();
        return n.equals("crash-reports")     // 崩溃报告
                || n.equals("logs")           // 运行日志
                || n.equals(".fml")           // 加载器(NeoForge/Forge)缓存
                || n.equals("cache")          // 通用缓存
                || n.equals("crash-reports-old")
                || n.equals("config-junk")
                || n.equals(".cache");
    }

    /** 收集单个实例目录内可清理的垃圾项（仅返回实际存在的路径）。 */
    public static List<Path> collectInstance(Path instanceDir) {
        List<Path> out = new ArrayList<>();
        if (instanceDir == null || !Files.isDirectory(instanceDir)) return out;
        try (var stream = Files.list(instanceDir)) {
            stream.forEach(p -> {
                if (isGarbageName(p.getFileName().toString())) out.add(p);
            });
        } catch (IOException ignored) {}
        return out;
    }

    /** clean all 时，额外收集游戏根目录下的共享垃圾（崩溃报告/日志）。 */
    public static List<Path> collectGameRoot() {
        List<Path> out = new ArrayList<>();
        Path game = Loc.game();
        if (game == null || !Files.isDirectory(game)) return out;
        try (var stream = Files.list(game)) {
            stream.forEach(p -> {
                String n = p.getFileName().toString().toLowerCase();
                if (n.equals("crash-reports") || n.equals("logs") || n.equals("crash-reports-old")) out.add(p);
            });
        } catch (IOException ignored) {}
        return out;
    }

    /** 递归统计路径占用字节数（删除前预览大小）。 */
    public static long sizeOf(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (var s = Files.walk(p)) { return s.filter(Files::isRegularFile).mapToLong(x -> {
                    try { return Files.size(x); } catch (IOException e) { return 0; }
                }).sum(); }
            }
            return Files.size(p);
        } catch (IOException e) { return 0; }
    }

    /** 删除一组路径（递归），返回成功删除数。 */
    public static int remove(List<Path> paths) {
        int n = 0;
        for (Path p : paths) {
            try {
                if (Files.isDirectory(p)) {
                    try (var s = Files.walk(p)) {
                        for (Path f : s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) Files.deleteIfExists(f);
                    }
                } else {
                    Files.deleteIfExists(p);
                }
                n++;
            } catch (IOException ignored) {}
        }
        return n;
    }
}

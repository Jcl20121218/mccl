package mccl.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 崩溃日志分析：游戏异常退出后，读取最新 crash-report 文本，
 * 用关键词做【粗略归类 + 引导话术】，仅供定位方向，不自动修复、不做深度推理。
 */
public final class CrashAnalyzer {
    private CrashAnalyzer() {}

    /** 分类结果 */
    public record Result(Path logPath, String kind, String hint) {}

    /** 在实例目录 / 游戏根目录下找最新的崩溃报告文件。 */
    public static Path findLatestCrash(String instId) {
        Path[] candidates = { Loc.instanceDir(instId).resolve("crash-reports"),
                              Loc.game().resolve("crash-reports") };
        Path latest = null;
        for (Path dir : candidates) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.toList()) {
                    String fn = p.getFileName().toString().toLowerCase();
                    if (!fn.endsWith(".txt") || !fn.startsWith("crash-")) continue;
                    if (latest == null || lastModified(p) > lastModified(latest)) latest = p;
                }
            } catch (IOException ignored) {}
        }
        return latest;
    }

    private static long lastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
    }

    /** 读取日志并做关键词粗分类。无日志时返回 null。 */
    public static Result analyze(Path logPath) {
        if (logPath == null || !Files.exists(logPath)) return null;
        String text;
        try { text = Files.readString(logPath); } catch (IOException e) { return null; }
        String t = text.toLowerCase();

        if (t.contains("duplicate mod id") || t.contains("modid conflict")
                || t.contains("mod id conflict") || t.contains("duplicate item id"))
            return new Result(logPath, "模组ID冲突",
                    "检测到模组 ID 冲突！不要同时安装同名不同版本的模组。把这份崩溃日志发给模组作者或玩家协助排查。");
        if (t.contains("missing dependency") || t.contains("required dependency")
                || t.contains("dependency not found") || t.contains("missing prerequisites"))
            return new Result(logPath, "缺少前置模组",
                    "检测到缺少前置模组！某模组依赖其他模组，但你没有安装对应前置。带上这份崩溃日志去求助。");
        if (t.contains("outofmemoryerror"))
            return new Result(logPath, "内存不足",
                    "分配内存不足导致崩溃！尝试用更大的 --mem 参数启动（如 --mem 8G）。");
        if (t.contains("mixin") && (t.contains("mixin crash") || t.contains("mixin apply failed")
                || t.contains("mixin transformation error") || t.contains("incompatible mixin")))
            return new Result(logPath, "Mixin 注入冲突",
                    "Mixin 报错，大概率是模组之间 Mixin 不兼容，属于模组兼容性冲突。将日志发给他人帮忙排查。");
        if (t.contains("nosuchmethod") || t.contains("nosuchmethoderror")
                || t.contains("classnotfound") || t.contains("noclassdeffounderror"))
            return new Result(logPath, "版本/入口不匹配",
                    "检测到类或方法缺失，通常是模组与当前 MC/加载器版本不匹配，或启动主类配置错误。请核对版本并把日志发给他人排查。");
        if (t.contains("java.lang.error") || t.contains("internal error")
                || t.contains("unexpected error"))
            return new Result(logPath, "Java 运行时错误",
                    "Java 运行时异常。请核对 Java 版本是否匹配该 MC 版本，并把完整日志发给他人协助排查。");

        return new Result(logPath, "未知类型崩溃",
                "未能自动识别报错类型。请阅读完整崩溃报告，把这份 txt 日志发给其他人协助排查。");
    }

    /** 输出完整引导块（供控制台在崩溃后展示）。 */
    public static void printGuide(Result r, String instId) {
        if (r == null) {
            Term.warn("⚠ 游戏进程异常退出，但未找到崩溃日志(crash-report)。");
            Term.info("  可手动查看日志目录：log self  /  log open " + instId);
            return;
        }
        Term.line("════════════════════ 错误信息 ════════════════════");
        Term.err("【识别结果】" + r.kind());
        Term.info("提示：");
        Term.info("  " + r.hint());
        Term.line("──────────────────────────────────────────────────");
        Term.info("👉 可以把这份崩溃日志直接发给其他玩家 / 模组制作者求助：");
        Term.info("  日志路径: " + r.logPath().toAbsolutePath());
        Term.info("  输入  log latest \"" + instId + "\"  一键打开这份日志文件");
        Term.line("══════════════════════════════════════════════════");
    }
}

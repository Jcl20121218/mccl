package mccl.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mccl.core.Loc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 组装 JVM + 游戏启动参数，通过 ProcessBuilder 拉起独立 MC 进程（离线模式）。
 * 游戏根目录固定为 <程序目录>/game（绿色便携）。
 * 支持 ModLauncher 端口注入（NeoForge / Forge 用 -Dmodlauncher.port；Fabric 无此概念则忽略）。
 * 支持 dry-run 预览。
 */
public class Launcher {

    /** v2 版本隔离：实例目录存在含 jar 的 mods 文件夹时，把该实例目录作为 gameDir（PCL 同款），
     *  否则回退到全局 .minecraft。这样实例自带的 mods/config/saves 会被游戏正常读取。 */
    private static Path resolveGameDir(Version ver) {
        Path inst = Loc.instanceDir(ver.id);
        Path mods = inst.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (var s = Files.list(mods)) {
                if (s.anyMatch(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")))
                    return inst;
            } catch (Exception ignored) {}
        }
        return Loc.game();
    }

    public static List<String> buildCommand(Version ver, String username, String mem, String javaPath, Integer modPort) {
        Path mcRoot = Loc.game();
        Path instanceDir = Loc.instanceDir(ver.id);
        Path librariesRoot = Loc.libraries();
        // v2 版本隔离：实例目录存在 mods 时，gameDir 切到实例目录（PCL 同款），实例 mods 才会被游戏加载
        Path effectiveGameDir = resolveGameDir(ver);

        List<String> classpath = new ArrayList<>();
        Path versionJar = instanceDir.resolve(ver.id + ".jar");
        if (Files.exists(versionJar)) classpath.add(versionJar.toString());
        for (Version.Library lib : ver.libraries) {
            if (!VersionResolver.libraryApplies(lib)) continue;
            String rel = VersionResolver.libraryPath(lib);
            if (rel == null) continue;
            Path jar = librariesRoot.resolve(rel);
            if (Files.exists(jar)) classpath.add(jar.toString());
        }
        if (classpath.isEmpty()) {
            System.err.println("classpath 为空，依赖库可能缺失。请先运行: lib download " + ver.id);
            return null;
        }

        Path nativesDir = instanceDir.resolve("natives");
        try { Files.createDirectories(nativesDir); } catch (Exception ignored) {}

        String accessToken = "0";
        String uuid = mccl.config.ProfileManager.offlineUuid(username);
        Map<String, String> tokens = new HashMap<>();
        tokens.put("auth_player_name", username);
        tokens.put("auth_uuid", uuid);
        tokens.put("auth_access_token", accessToken);
        tokens.put("auth_session", accessToken + ":" + uuid);
        tokens.put("user_type", "legacy");
        tokens.put("version_name", ver.id);
        tokens.put("version_type", ver.type != null ? ver.type : "release");
        tokens.put("game_directory", effectiveGameDir.toAbsolutePath().toString());
        tokens.put("assets_root", Loc.assets().toAbsolutePath().toString());
        tokens.put("assets_index_name", ver.assetIndex != null ? ver.assetIndex.id : ver.id);
        tokens.put("launcher_name", "mccl");
        tokens.put("launcher_version", "1.0");
        tokens.put("natives_directory", nativesDir.toAbsolutePath().toString());
        tokens.put("library_directory", librariesRoot.toAbsolutePath().toString());
        tokens.put("classpath", String.join(File.pathSeparator, classpath));
        tokens.put("classpath_separator", File.pathSeparator);
        tokens.put("resolution_width", "854");
        tokens.put("resolution_height", "480");
        tokens.put("clientid", UUID.randomUUID().toString());
        tokens.put("auth_xuid", "0");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);
        cmd.add("-Xmx" + mem);
        cmd.add("-Xms" + mem);

        // 注入 ModLauncher 端口（NeoForge / Forge）
        if (modPort != null) {
            String lc = ver.id.toLowerCase();
            if (lc.contains("neoforge") || lc.contains("forge")) {
                cmd.add("-Dmodlauncher.port=" + modPort);
            } else {
                System.out.println("  (实例非 NeoForge/Forge，忽略 --mod-port)");
            }
        }

        // v2: 以 version.json 的 arguments.jvm 为唯一事实来源，按启动模式原样透传，不做智能重组。
        List<String> jvmTokens = extractArgs(ver.arguments != null ? ver.arguments.jvm : null, tokens);
        boolean moduleMode = false;  // -m 模块启动（NeoForge / 新版 Forge 的 Jigsaw 启动）
        boolean jsonHasCp = false;   // json 自带 -cp / -classpath
        for (String a : jvmTokens) {
            if (a.equals("-m") || a.equals("--module")) moduleMode = true;
            else if (a.equals("-cp") || a.equals("-classpath")) jsonHasCp = true;
        }
        cmd.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
        cmd.addAll(jvmTokens);
        if (!moduleMode) {
            // 模块模式已由 json 的 -m 给出主类；非模块模式需要补主类。
            if (!jsonHasCp) {
                // Fabric / 原版 / 老 Forge（json 未给 -cp）：启动器拼 classpath + 顶层主类
                cmd.add("-cp");
                cmd.add(String.join(File.pathSeparator, classpath));
            }
            cmd.add(ver.mainClass != null ? ver.mainClass : "net.minecraft.client.main.Main");
        }

        List<String> gameArgs = extractArgs(ver.arguments != null ? ver.arguments.game : null, tokens);
        cmd.addAll(gameArgs);
        return cmd;
    }

    public static int launch(Version ver, String username, String mem, String javaPath, Integer modPort) {
        List<String> cmd = buildCommand(ver, username, mem, javaPath, modPort);
        if (cmd == null) return -1;
        mccl.core.Term.line(">>> 启动命令:");
        mccl.core.Term.info("  " + String.join(" ", cmd));
        mccl.core.Term.line(">>> 启动中...（游戏进程退出后命令行才返回）");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(resolveGameDir(ver).toFile());
            pb.inheritIO();
            Process proc = pb.start();
            int code = proc.waitFor();
            if (code == 0) {
                mccl.core.Term.succ(">>> 游戏进程正常退出，退出码: " + code);
            } else {
                mccl.core.Term.err(">>> 游戏进程异常退出，退出码: " + code);
                crashGuide(ver.id);
            }
            return code;
        } catch (Exception e) {
            mccl.core.Term.err("启动失败: " + e.getMessage());
            return -1;
        }
    }

    /** 游戏异常退出后，读取最新崩溃日志做关键词粗分类并给出引导话术。 */
    private static void crashGuide(String instId) {
        try {
            var r = mccl.core.CrashAnalyzer.analyze(mccl.core.CrashAnalyzer.findLatestCrash(instId));
            mccl.core.CrashAnalyzer.printGuide(r, instId);
        } catch (Throwable t) {
            mccl.core.Term.warn("  (崩溃日志分析未执行: " + t.getMessage() + ")");
        }
    }

    private static List<String> extractArgs(List<Object> args, Map<String, String> tokens) {
        List<String> out = new ArrayList<>();
        if (args == null) return out;
        for (Object o : args) {
            if (o instanceof String s) {
                out.add(VersionResolver.replaceTokens(s, tokens));
            } else if (o instanceof JsonObject jo) {
                if (!rulesApply(jo.get("rules"))) continue;
                JsonElement val = jo.get("value");
                if (val == null) continue;
                if (val.isJsonArray()) {
                    for (JsonElement e : val.getAsJsonArray())
                        if (e.isJsonPrimitive()) out.add(VersionResolver.replaceTokens(e.getAsString(), tokens));
                } else if (val.isJsonPrimitive()) {
                    out.add(VersionResolver.replaceTokens(val.getAsString(), tokens));
                }
            }
        }
        return out;
    }

    private static boolean rulesApply(JsonElement rulesEl) {
        if (rulesEl == null || !rulesEl.isJsonArray()) return true;
        JsonArray rules = rulesEl.getAsJsonArray();
        String current = currentOs();
        boolean allowed = false;
        for (JsonElement r : rules) {
            if (!r.isJsonObject()) continue;
            JsonObject jo = r.getAsJsonObject();
            String action = jo.has("action") ? jo.get("action").getAsString() : null;
            String osName = null;
            if (jo.has("os") && jo.get("os").isJsonObject() && jo.get("os").getAsJsonObject().has("name"))
                osName = jo.get("os").getAsJsonObject().get("name").getAsString();
            if (osName != null && !osName.equalsIgnoreCase(current)) continue;
            if ("deny".equalsIgnoreCase(action)) return false;
            if ("allow".equalsIgnoreCase(action)) allowed = true;
        }
        return allowed;
    }

    private static String currentOs() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        return "linux";
    }
}

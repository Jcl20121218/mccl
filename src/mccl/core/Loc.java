package mccl.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 绿色便携路径管理。
 * 两种数据目录布局：
 *  - MCCL 原生：根目录/game/{versions,libraries,assets}
 *  - Vanilla：  根目录/{versions,libraries,assets}（原版 .minecraft 结构）
 * 配置/缓存等便携文件始终在程序自身目录(defaultBase)，不随数据目录迁移。
 */
public final class Loc {
    private Loc() {}

    private static volatile Path override = null;
    private static volatile boolean vanilla = false;

    public static void setBase(Path p, boolean isVanilla) {
        override = p.toAbsolutePath().normalize();
        vanilla = isVanilla;
        try {
            Files.createDirectories(defaultBase().resolve("config"));
            Files.writeString(defaultBase().resolve("config").resolve("home.json"), override.toString());
            Files.writeString(defaultBase().resolve("config").resolve("vanilla.json"), vanilla ? "1" : "0");
        } catch (Exception ignored) {}
    }

    public static void resetBase() {
        override = null;
        vanilla = false;
        try {
            Files.deleteIfExists(defaultBase().resolve("config").resolve("home.json"));
            Files.deleteIfExists(defaultBase().resolve("config").resolve("vanilla.json"));
        } catch (Exception ignored) {}
    }

    public static Path defaultBase() {
        String env = System.getenv("MCCL_HOME");
        String sys = System.getProperty("mccl.home", "");
        String dir = System.getProperty("user.dir");
        String chosen = (env != null && !env.isBlank()) ? env : (!sys.isBlank() ? sys : dir);
        return Paths.get(chosen).toAbsolutePath().normalize();
    }

    public static Path base() {
        if (override != null) return override;
        Path f = defaultBase().resolve("config").resolve("home.json");
        if (Files.exists(f)) {
            try {
                String s = Files.readString(f).trim();
                Path vf = defaultBase().resolve("config").resolve("vanilla.json");
                vanilla = Files.exists(vf) && Files.readString(vf).trim().equals("1");
                if (!s.isBlank()) return Paths.get(s).toAbsolutePath().normalize();
            } catch (Exception ignored) {}
        }
        return defaultBase();
    }

    public static boolean isVanilla() { return vanilla; }

    // ---- 数据目录（versions/libraries/assets）----
    public static Path game()    { return vanilla ? base() : base().resolve("game"); }
    public static Path versions(){ return game().resolve("versions"); }
    public static Path libraries(){ return game().resolve("libraries"); }
    public static Path assets()  { return game().resolve("assets"); }

    // ---- 便携文件（配置/缓存/日志/工具，始终在程序自身目录）----
    public static Path configDir(){ return defaultBase().resolve("config"); }
    public static Path cache()   { return defaultBase().resolve("cache"); }
    public static Path logs()    { return defaultBase().resolve("logs"); }
    public static Path tools()   { return defaultBase().resolve("tools"); }
    public static Path jreBin()  { return tools().resolve("jre").resolve("bin").resolve("java.exe"); }
    public static Path aria2Exe(){ return tools().resolve("aria2").resolve("aria2c.exe"); }

    public static Path instanceDir(String instId) { return versions().resolve(instId); }

    public static void ensureDirs() throws java.io.IOException {
        if (!vanilla) Files.createDirectories(game());
        Files.createDirectories(versions());
        Files.createDirectories(libraries());
        Files.createDirectories(assets());
        Files.createDirectories(configDir());
        Files.createDirectories(cache());
        Files.createDirectories(logs());
        Files.createDirectories(tools());
    }
}

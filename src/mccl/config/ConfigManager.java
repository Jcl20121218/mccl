package mccl.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动器配置：绿色便携，全部存于程序目录 config/mccl.json。
 * 游戏根目录固定为 <程序目录>/game（不再需要多 root 切换）。
 */
public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class Data {
        public String defaultMem = "4G";
        public String defaultJavaPath = ""; // 空 = 自动探测(便携JRE优先)
        public int defaultThreads = 8;
        public String lastMcVersion = "";   // 记住上次启动的实例，供便捷再次启动
        public String defaultInstance = ""; // 默认实例：不带实例名的 launch/check/log/open 等自动使用
        public int downloadThreads = 8;     // 全局下载线程数(默认8，上限16)
        public java.util.Map<String, InstanceSetting> instances = new java.util.LinkedHashMap<>(); // 实例专属参数记忆
    }

    /** 实例专属参数：记住内存与 mod-port，启动时自动带入（可在命令里临时覆盖）。 */
    public static class InstanceSetting {
        public String mem = "";       // 空 = 用全局 defaultMem
        public Integer modPort = null;
        public InstanceSetting() {}
        public InstanceSetting(String mem, Integer modPort) { this.mem = mem; this.modPort = modPort; }
    }

    private final Path file = mccl.core.Loc.configDir().resolve("mccl.json");
    public Data data = new Data();

    public void load() {
        try {
            if (!Files.exists(file)) { save(); return; }
            Data d = GSON.fromJson(Files.readString(file), Data.class);
            if (d != null) data = d;
        } catch (Exception e) {
            System.err.println("配置读取失败: " + e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data));
        } catch (Exception ignored) {}
    }
}

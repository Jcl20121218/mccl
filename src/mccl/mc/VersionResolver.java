package mccl.mc;

import com.google.gson.Gson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 版本扫描与解析：读取 version.json，递归合并 inheritsFrom 继承链，
 * 过滤 OS 相关 library rules，计算 library 在本地库中的相对路径。
 */
public class VersionResolver {
    private static final Gson GSON = new Gson();
    private static final String OS_NAME;

    static {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) OS_NAME = "windows";
        else if (os.contains("mac") || os.contains("darwin")) OS_NAME = "osx";
        else OS_NAME = "linux";
    }

    public static String currentOs() {
        return OS_NAME;
    }

    /** 扫描 versions 目录，返回所有存在 {id}.json 的版本 id */
    public static List<String> listVersionIds(Path mcRoot) {
        List<String> ids = new ArrayList<>();
        Path verDir = mcRoot.resolve("versions");
        if (!Files.isDirectory(verDir)) return ids;
        try (var stream = Files.list(verDir)) {
            stream.filter(Files::isDirectory).forEach(p -> {
                String vid = p.getFileName().toString();
                if (Files.exists(p.resolve(vid + ".json"))) ids.add(vid);
            });
        } catch (Exception ignored) {
        }
        Collections.sort(ids);
        return ids;
    }

    /** 读取单个版本原始 json（不合并继承） */
    public static Version loadRaw(Path mcRoot, String verId) {
        Path jsonPath = mcRoot.resolve("versions").resolve(verId).resolve(verId + ".json");
        if (!Files.exists(jsonPath)) return null;
        try {
            return GSON.fromJson(Files.readString(jsonPath), Version.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 递归合并 inheritsFrom，返回可直接用于启动的完整版本 */
    public static Version resolve(Path mcRoot, String verId) {
        return resolveInner(mcRoot, verId, new HashSet<>());
    }

    private static Version resolveInner(Path mcRoot, String verId, Set<String> visited) {
        if (!visited.add(verId)) return new Version(); // 防循环继承
        Version v = loadRaw(mcRoot, verId);
        if (v == null) return new Version();

        // 收集继承链：子 -> 父 -> ... -> 祖先
        List<Version> chain = new ArrayList<>();
        chain.add(v);
        Version cur = v;
        int guard = 0;
        while (cur != null && cur.inheritsFrom != null && !cur.inheritsFrom.isBlank() && guard++ < 10) {
            Version parent = loadRaw(mcRoot, cur.inheritsFrom);
            if (parent == null) break;
            chain.add(parent);
            cur = parent;
        }
        Collections.reverse(chain); // 祖先 -> ... -> 子

        Version merged = new Version();
        Map<String, Version.Library> libMap = new LinkedHashMap<>();
        List<Object> game = new ArrayList<>();
        List<Object> jvm = new ArrayList<>();

        for (Version cv : chain) {
            if (cv.libraries != null) {
                for (Version.Library lib : cv.libraries) {
                    if (lib != null && lib.name != null && !libMap.containsKey(lib.name)) {
                        libMap.put(lib.name, lib);
                    }
                }
            }
            if (cv.arguments != null) {
                if (cv.arguments.game != null) game.addAll(cv.arguments.game);
                if (cv.arguments.jvm != null) jvm.addAll(cv.arguments.jvm);
            }
            if (merged.mainClass == null || "net.minecraft.client.main.Main".equals(merged.mainClass)) {
                if (cv.mainClass != null) merged.mainClass = cv.mainClass;
            }
            if (merged.assetIndex == null && cv.assetIndex != null) merged.assetIndex = cv.assetIndex;
            if (merged.minecraftArguments == null && cv.minecraftArguments != null) merged.minecraftArguments = cv.minecraftArguments;
            if (merged.id == null) merged.id = cv.id;
        }

        merged.id = merged.id != null ? merged.id : verId;
        merged.libraries = new ArrayList<>(libMap.values());
        merged.arguments = new Version.Arguments();
        merged.arguments.game = game;
        merged.arguments.jvm = jvm;
        return merged;
    }

    /** 根据当前 OS 判断 library 是否适用（rules 语义） */
    public static boolean libraryApplies(Version.Library lib) {
        List<Version.Rule> rules = lib.rules;
        if (rules == null || rules.isEmpty()) return true;
        boolean allowed = false;
        for (Version.Rule r : rules) {
            if (r == null) continue;
            boolean osMatch = r.os == null || r.os.name == null || r.os.name.equalsIgnoreCase(OS_NAME);
            if (!osMatch) continue;
            if ("deny".equalsIgnoreCase(r.action)) return false;
            if ("allow".equalsIgnoreCase(r.action)) allowed = true;
        }
        return allowed;
    }

    /** 把 Maven 坐标转为 libraries 目录下的相对 jar 路径 */
    public static String libraryPath(Version.Library lib) {
        String[] c = lib.coords();
        if (c.length < 3) return null;
        String group = c[0].replace('.', '/');
        String artifact = c[1];
        String version = c[2];
        StringBuilder fname = new StringBuilder(artifact).append('-').append(version);
        if (c.length >= 4) fname.append('-').append(c[3]);
        fname.append(".jar");
        return group + "/" + artifact + "/" + version + "/" + fname;
    }

    /** 替换参数模板中的 ${token} 占位符 */
    public static String replaceTokens(String input, Map<String, String> tokens) {
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            input = input.replace("${" + e.getKey() + "}", e.getValue());
        }
        return input;
    }
}

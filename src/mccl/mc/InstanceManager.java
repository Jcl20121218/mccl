package mccl.mc;

import mccl.core.Loc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * 版本实例隔离管理：每个实例是 game/versions/<文件夹名> 下的独立环境。
 * 实例标识 = 文件夹名；改名/克隆会同步修正加载器路径（路径由启动时按当前文件夹名计算，天然适配）。
 * 资源库(libraries/assets)全局共享节省空间；实例内 mods/config/loader缓存独立隔离。
 */
public class InstanceManager {
    private static final java.util.Set<Character> ILLEGAL = java.util.Set.of('\\','/',':','*','?','"','<','>','|');

    public static List<String> list() {
        List<String> out = new ArrayList<>();
        Path v = Loc.versions();
        if (!Files.isDirectory(v)) return out;
        try (Stream<Path> s = Files.list(v)) {
            s.filter(Files::isDirectory).forEach(p -> out.add(p.getFileName().toString()));
        } catch (IOException ignored) {}
        Collections.sort(out);
        return out;
    }

    public static boolean exists(String id) {
        return Files.isDirectory(Loc.instanceDir(id));
    }

    public static boolean rename(String oldName, String newName) {
        if (!valid(newName) || !exists(oldName) || exists(newName)) return false;
        try {
            Files.move(Loc.instanceDir(oldName), Loc.instanceDir(newName));
            postRenameFix(newName);
            return true;
        } catch (Exception e) { return false; }
    }

    /**
     * 改名后修复加载器路径缓存：NeoForge/Forge 会把 jar 的绝对路径写进实例内
     * 的 .fml / 缓存目录，改名后旧路径失效会炸。这里只删除可再生的加载器缓存，
     * 不碰 mods / config / 内核文件（重启会自动重建缓存）。
     */
    private static void postRenameFix(String newName) {
        Path dir = Loc.instanceDir(newName);
        String[] cacheNames = { ".fml", ".cache", "cache", "config-junk", "crash-reports", "logs" };
        for (String cn : cacheNames) {
            Path p = dir.resolve(cn);
            try {
                if (Files.isDirectory(p)) {
                    try (var s = Files.walk(p)) {
                        for (Path f : s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList())
                            Files.deleteIfExists(f);
                    }
                } else Files.deleteIfExists(p);
            } catch (IOException ignored) {}
        }
    }

    /** 克隆：复制整个实例文件夹（含 mods/config），生成完全独立的新实例。 */
    public static boolean clone(String src, String newName) {
        if (!valid(newName) || !exists(src) || exists(newName)) return false;
        try {
            copyTree(Loc.instanceDir(src), Loc.instanceDir(newName));
            return true;
        } catch (Exception e) { return false; }
    }

    /** 新建空实例（内核文件靠下载），创建独立 mods/config 目录。 */
    public static boolean newEmpty(String name) {
        if (!valid(name) || exists(name)) return false;
        try {
            Files.createDirectories(Loc.instanceDir(name).resolve("mods"));
            Files.createDirectories(Loc.instanceDir(name).resolve("config"));
            return true;
        } catch (Exception e) { return false; }
    }

    public static boolean valid(String name) {
        if (name == null || name.isBlank()) return false;
        for (char c : name.toCharArray()) if (ILLEGAL.contains(c) || c < 32) return false;
        return !name.equals(".") && !name.equals("..");
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : s.toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }
}

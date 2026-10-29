package mccl.mc;

import mccl.core.Loc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 模组管理（版本隔离）：mods 与 mods-disabled 都在实例目录内。
 * 启用 -> <实例>/mods/xxx.jar，禁用 -> <实例>/mods-disabled/xxx.jar。状态存于文件系统。
 */
public class ModManager {

    public static List<String> listEnabled(String instId) {
        return listJars(Loc.instanceDir(instId).resolve("mods"));
    }

    public static List<String> listDisabled(String instId) {
        return listJars(Loc.instanceDir(instId).resolve("mods-disabled"));
    }

    private static List<String> listJars(Path d) {
        if (!Files.isDirectory(d)) return new ArrayList<>();
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        } catch (IOException e) { return new ArrayList<>(); }
    }

    public static boolean disable(String instId, String modFile) {
        return move(Loc.instanceDir(instId).resolve("mods").resolve(modFile),
                    Loc.instanceDir(instId).resolve("mods-disabled").resolve(modFile));
    }

    public static boolean enable(String instId, String modFile) {
        return move(Loc.instanceDir(instId).resolve("mods-disabled").resolve(modFile),
                    Loc.instanceDir(instId).resolve("mods").resolve(modFile));
    }

    private static boolean move(Path src, Path dst) {
        try {
            if (!Files.exists(src)) return false;
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) { return false; }
    }
}

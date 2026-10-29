package mccl.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 本地玩家档案系统（离线昵称）。
 * 文件：<程序目录>/config/profiles.json，绿色便携，跟随文件夹带走。
 * 不做任何正版/外置登录，仅本地玩家名 + 自动派生 UUID。
 */
public class ProfileManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class Profile {
        public String name;
        public String uuid;
        public Profile() {}
        public Profile(String name, String uuid) { this.name = name; this.uuid = uuid; }
    }

    public static class Data {
        public String current = "";
        public List<Profile> profiles = new ArrayList<>();
    }

    private Data data = new Data();
    private final java.nio.file.Path file;

    public ProfileManager() {
        file = mccl.core.Loc.configDir().resolve("profiles.json");
    }

    public void load() {
        try {
            if (!Files.exists(file)) { save(); return; }
            Data d = GSON.fromJson(Files.readString(file), Data.class);
            if (d != null) data = d;
        } catch (Exception e) {
            System.err.println("玩家档案读取失败: " + e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data));
        } catch (Exception ignored) {}
    }

    public List<Profile> list() { return data.profiles; }
    public String currentName() { return data.current; }
    public boolean hasCurrent() {
        for (Profile p : data.profiles) if (p.name.equals(data.current)) return true;
        return false;
    }

    public boolean add(String name) {
        if (name == null || name.isBlank()) return false;
        if (find(name) != null) return false;
        data.profiles.add(new Profile(name, offlineUuid(name)));
        if (data.profiles.size() == 1 || data.current.isBlank()) data.current = name;
        save();
        return true;
    }

    public boolean set(String name) {
        if (find(name) == null) return false;
        data.current = name;
        save();
        return true;
    }

    public boolean rename(String oldName, String newName) {
        if (oldName == null || newName == null || newName.isBlank()) return false;
        Profile p = find(oldName);
        if (p == null || find(newName) != null) return false;
        p.name = newName;
        p.uuid = offlineUuid(newName);
        if (data.current.equals(oldName)) data.current = newName;
        save();
        return true;
    }

    public boolean remove(String name) {
        Profile p = find(name);
        if (p == null) return false;
        data.profiles.remove(p);
        if (data.current.equals(name)) data.current = data.profiles.isEmpty() ? "" : data.profiles.get(0).name;
        save();
        return true;
    }

    private Profile find(String name) {
        for (Profile p : data.profiles) if (p.name.equals(name)) return p;
        return null;
    }

    /** 离线 UUID：与 PCL/HMCL 相同的标准算法（基于 OfflinePlayer:名字）。 */
    public static String offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes()).toString();
    }
}

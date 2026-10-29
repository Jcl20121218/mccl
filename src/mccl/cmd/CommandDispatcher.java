package mccl.cmd;

import mccl.config.ConfigManager;
import mccl.config.ProfileManager;
import mccl.core.Cleaner;
import mccl.core.CrashAnalyzer;
import mccl.core.Loc;
import mccl.core.Term;
import mccl.dl.Downloader;
import mccl.dl.MirrorSource;
import mccl.mc.InstanceManager;
import mccl.mc.Launcher;
import mccl.mc.ModManager;
import mccl.mc.Version;
import mccl.mc.VersionResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令分发中心：REPL 交互 与 单次命令(脚本) 共用。
 * 彩色分层日志 / 实例隔离 / 默认实例 / 一键清理 / 崩溃日志识别 / 多线程下载。
 */
public class CommandDispatcher {

    /** 交互输入源：REPL 会在进入时注入同一个 Scanner，避免多个 reader 争抢 stdin。 */
    public interface LineReader { String readLine(); }
    public static volatile LineReader lineReader = null;

    public static boolean dispatch(ConfigManager cfg, String cmd, List<String> args) {
        // 参数引号剥除：去掉首尾包裹的英文引号，避免 "Illegal char <">" 解析错误
        for (int i = 0; i < args.size(); i++) args.set(i, stripQuotes(args.get(i)));
        // 命令别名
        String c = alias(cmd, args);

        switch (c.toLowerCase()) {
            case "player":   return cmdPlayer(args);
            case "home":     return cmdHome(args);
            case "open":     return cmdOpen(args);
            case "instance": return cmdInstance(args);
            case "version":  return cmdVersion(cfg, args);
            case "lib":      return cmdLib(cfg, args);
            case "asset":    return cmdAsset(args);
            case "mod":      return cmdMod(cfg, args);
            case "loader":   return cmdLoader(args);
            case "versions": return cmdVersions(args);
            case "scan":     return cmdVersions(List.of("scan"));
            case "launch":   return cmdLaunch(cfg, args);
            case "config":   return cmdConfig(cfg, args);
            case "default":  return cmdDefault(cfg, args);
            case "clean":    return cmdClean(args);
            case "log":      return cmdLog(args);
            case "setting":  return cmdSetting(cfg, args);
            case "help":
            case "?":
                help();
                return true;
            case "exit":
            case "quit":
                Term.info("再见！");
                System.exit(0);
                return true;
            default:
                Term.err("未知命令: " + cmd + "   输入 help 查看");
                return false;
        }
    }

    /** 命令别名映射；需要补子命令的别名会注入参数。 */
    private static String alias(String cmd, List<String> args) {
        switch (cmd.toLowerCase()) {
            case "il": args.add(0, "list"); return "instance";
            case "in": args.add(0, "new");  return "instance";
            case "irc": args.add(0, "rename"); return "instance";
            case "vd": args.add(0, "download"); return "version";
            case "ld": args.add(0, "download"); return "lib";
            case "ad": args.add(0, "download"); return "asset";
            case "ll": args.add(0, "latest"); return "log";
            case "om": args.add(0, "mods"); return "open";
            case "ss": return "scan";
            default: return cmd;
        }
    }

    private static String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        char a = s.charAt(0), b = s.charAt(s.length() - 1);
        if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) return s.substring(1, s.length() - 1);
        return s;
    }

    private static void help() {
        Term.info("""
                MCCL - Mini Craft Launcher (绿色便携 / 离线 / MIT)
                工作目录:
                  home get                              查看当前工作根目录
                  home set <路径> [--vanilla|--mccl]    切换根目录(自动识别原版.minecraft布局)
                  home reset                            重置为程序自身目录
                  open root / open mods <实例> / open config
                玩家:
                  player list|add|set|rename|remove
                版本实例(隔离):
                  instance list / new <名字> / clone <源> <新名> / rename <旧名> <新名>
                  scan        (versions scan)            扫描versions目录，刷新实例索引
                下载(手动触发, 启动不自动下载):
                  version list / version download <版本>
                  lib download <实例> / asset download <实例>
                  mod search / mod download / mod browse
                  loader download fabric|forge|neoforge   打开官方安装器下载页
                模组管理:
                  mod ls|enable|disable <实例>
                启动:
                  launch <实例> [--player 名字] [--mem 4G] [--mod-port 端口] [--dry-run]
                  不带 <实例> 时使用默认实例；无默认实例则弹出选择菜单
                默认实例 / 参数记忆:
                  default set <实例> | default get | default clear
                  setting <实例> mem <G> | setting <实例> mod-port <端口> | setting <实例> get
                  setting download-threads <数字>
                崩溃日志 / 清理:
                  log open <实例> / log latest <实例> / log self
                  clean <实例> / clean all       一键清理日志、崩溃报告、缓存(需确认)
                其他:
                  config / help / exit
                命令别名: il(in list) in(new) vd(version download) ld(lib download)
                          ad(asset download) ll(log latest) om(open mods) ss(scan)
                """);
    }

    // ---------- home ----------
    private static boolean cmdHome(List<String> args) {
        if (args.isEmpty() || "get".equalsIgnoreCase(args.get(0))) {
            Term.info("当前工作根目录: " + Loc.base());
            Term.info("  目录模式: " + (Loc.isVanilla() ? "原版.minecraft布局" : "MCCL便携布局"));
            Term.info("  game目录: " + Loc.game());
            Term.info("  config  : " + Loc.configDir());
            return true;
        }
        if ("set".equalsIgnoreCase(args.get(0))) {
            if (args.size() < 2) { Term.err("用法: home set <目录路径> [--vanilla|--mccl]"); return false; }
            Path p = Path.of(args.get(1)).toAbsolutePath().normalize();
            boolean isVanilla = Files.isDirectory(p.resolve("versions"));
            for (String a : args) {
                if ("--vanilla".equalsIgnoreCase(a)) isVanilla = true;
                else if ("--mccl".equalsIgnoreCase(a)) isVanilla = false;
            }
            try {
                Files.createDirectories(p);
                Loc.setBase(p, isVanilla);
                Loc.ensureDirs();
                Term.succ("已切换工作根目录到: " + Loc.base());
                Term.info("  目录模式: " + (Loc.isVanilla() ? "原版.minecraft布局" : "MCCL便携布局") + "  (实例目录: " + Loc.versions() + ")");
            } catch (Exception e) {
                Term.err("切换失败: " + e.getMessage());
                return false;
            }
            return true;
        }
        if ("reset".equalsIgnoreCase(args.get(0))) {
            Loc.resetBase();
            try { Loc.ensureDirs(); } catch (Exception ignored) {}
            Term.succ("已重置工作根目录为程序自身目录: " + Loc.base());
            return true;
        }
        return false;
    }

    // ---------- open ----------
    private static boolean cmdOpen(List<String> args) {
        if (args.isEmpty()) { Term.err("用法: open root | mods <实例> | config"); return false; }
        Path target = null;
        String sub = args.get(0).toLowerCase();
        switch (sub) {
            case "root": target = Loc.game(); break;
            case "mods": {
                if (args.size() < 2) { Term.err("用法: open mods <实例>"); return false; }
                target = Loc.instanceDir(args.get(1)).resolve("mods");
                try { Files.createDirectories(target); } catch (Exception ignored) {}
                break;
            }
            case "config":
                target = Loc.configDir();
                try { Files.createDirectories(target); } catch (Exception ignored) {}
                break;
            default:
                Term.err("未知: " + args.get(0) + "  用法: open root | mods <实例> | config");
                return false;
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(target.toFile());
            else new ProcessBuilder("explorer", target.toString()).start();
            Term.succ("已打开: " + target);
            return true;
        } catch (Exception e) {
            Term.err("打开失败: " + target + "  " + e.getMessage());
            return false;
        }
    }

    // ---------- player ----------
    private static boolean cmdPlayer(List<String> args) {
        ProfileManager pm = new ProfileManager();
        pm.load();
        if (args.isEmpty() || "list".equalsIgnoreCase(args.get(0))) {
            Term.info("当前玩家: " + (pm.currentName().isBlank() ? "(无)" : pm.currentName()));
            int i = 1;
            for (ProfileManager.Profile p : pm.list()) Term.info("  [" + (i++) + "] " + p.name + "  " + p.uuid);
            return true;
        }
        String sub = args.get(0).toLowerCase();
        switch (sub) {
            case "add": {
                if (args.size() < 2) { Term.err("用法: player add <名字>"); return false; }
                boolean ok = pm.add(args.get(1));
                Term.succ(ok ? "已新建玩家: " + args.get(1) : "失败(重名或空名): " + args.get(1));
                return ok;
            }
            case "set": {
                if (args.size() < 2) { Term.err("用法: player set <名字>"); return false; }
                boolean ok = pm.set(args.get(1));
                Term.succ(ok ? "已切换到: " + args.get(1) : "玩家不存在: " + args.get(1));
                return ok;
            }
            case "rename": {
                if (args.size() < 3) { Term.err("用法: player rename <旧名> <新名>"); return false; }
                boolean ok = pm.rename(args.get(1), args.get(2));
                Term.succ(ok ? "已改名: " + args.get(1) + " -> " + args.get(2) : "失败(重名/不存在)");
                return ok;
            }
            case "remove": {
                if (args.size() < 2) { Term.err("用法: player remove <名字>"); return false; }
                boolean ok = pm.remove(args.get(1));
                Term.succ(ok ? "已删除: " + args.get(1) : "玩家不存在: " + args.get(1));
                return ok;
            }
            default: return false;
        }
    }

    // ---------- instance ----------
    /** 实例类型判定：优先主类特征(Forge/NeoForge=FML launcher, Fabric=knot)，再继承链，再名称/目录特征。 */
    private static String classify(Version v, String id) {
        String lcId = id.toLowerCase();
        String mc = (v != null && v.mainClass != null) ? v.mainClass.toLowerCase() : "";
        // 加载器主类特征（含第三方打包工具/整合包的变体）
        boolean neo = mc.contains("neoforged") || mc.contains("neoforge");
        boolean forge = mc.contains("cpw.mods") || mc.contains("net.minecraftforge")
                || mc.contains("modlauncher") || mc.contains("securejarhandler");
        boolean fabric = mc.contains("fabric") || mc.contains("knot");
        // 名称特征
        if (lcId.contains("neoforge") || lcId.contains("neo_forge")) return "NeoForge";
        if (lcId.contains("fabric")) return "Fabric";
        if (lcId.contains("forge")) return "Forge";
        // 主类优先
        if (neo) return "NeoForge";
        if (fabric) return "Fabric";
        if (forge) return "Forge";
        // 继承链：仍无加载器特征且是集成包结构 => 标记"整合包(继承)"
        if (v != null && v.inheritsFrom != null && !v.inheritsFrom.isBlank())
            return "整合包(继承 " + v.inheritsFrom + ")";
        return "原版";
    }

    private static boolean cmdInstance(List<String> args) {
        if (args.isEmpty() || "list".equalsIgnoreCase(args.get(0))) {
            List<String> list = InstanceManager.list();
            if (list.isEmpty()) { Term.warn("尚无任何实例。可用: instance new <名字>"); return false; }
            Term.line("实例 (" + list.size() + " 个，目录 " + Loc.versions() + "):");
            for (String id : list) {
                Version v = VersionResolver.loadRaw(Loc.game(), id);
                String kind = classify(v, id);
                Term.info("  - " + id + "  [" + kind + "]");
            }
            return true;
        }
        String sub = args.get(0).toLowerCase();
        switch (sub) {
            case "new": {
                if (args.size() < 2) { Term.err("用法: instance new <名字>"); return false; }
                boolean ok = InstanceManager.newEmpty(args.get(1));
                Term.succ(ok ? "已创建空实例: " + args.get(1) : "失败(非法名或已存在)");
                return ok;
            }
            case "clone": {
                if (args.size() < 3) { Term.err("用法: instance clone <源> <新名>"); return false; }
                boolean ok = InstanceManager.clone(args.get(1), args.get(2));
                Term.succ(ok ? "已克隆独立实例: " + args.get(2) : "失败(源不存在/目标存在/非法名)");
                return ok;
            }
            case "rename": {
                if (args.size() < 3) { Term.err("用法: instance rename <旧名> <新名>"); return false; }
                boolean ok = InstanceManager.rename(args.get(1), args.get(2));
                Term.succ(ok ? "已改名: " + args.get(1) + " -> " + args.get(2) + " (已同步清理加载器缓存)"
                        : "失败(源不存在/目标存在/非法名)");
                return ok;
            }
            default: return false;
        }
    }

    // ---------- 原版下载 ----------
    private static boolean cmdVersion(ConfigManager cfg, List<String> args) {
        if (args.isEmpty() || "list".equalsIgnoreCase(args.get(0))) {
            Term.info("正在从 BMCLAPI 拉取原版版本清单...");
            List<String> vers = MirrorSource.listVanillaVersions();
            if (vers.isEmpty()) { Term.err("获取失败(网络或镜像不可达)。"); return false; }
            Term.info("可下载原版版本: " + vers);
            return true;
        }
        if ("download".equalsIgnoreCase(args.get(0))) {
            if (args.size() < 2) { Term.err("用法: version download <版本号> [--threads N]"); return false; }
            String ver = args.get(1);
            String backend = backendFlag(args);
            boolean dry = dryFlag(args);
            int threads = threadsFlag(cfg, args);
            Term.line("下载原版内核 " + ver + " (线程 " + threads + ") ...");
            boolean ok = Downloader.run(MirrorSource.vanillaCoreTasks(ver), backend, threads, dry);
            if (ok && !dry) Term.succ("内核已下载，可继续: lib download " + ver);
            return ok;
        }
        return false;
    }

    private static boolean cmdLib(ConfigManager cfg, List<String> args) {
        if (args.isEmpty() || !"download".equalsIgnoreCase(args.get(0)) || args.size() < 2) {
            Term.err("用法: lib download <实例> [--threads N --dry-run --backend auto]");
            return false;
        }
        String inst = args.get(1);
        List<mccl.dl.DownloadTask> tasks = MirrorSource.libraryTasks(inst);
        if (tasks.isEmpty()) { Term.warn("没有缺失的依赖库(或缺少 version.json)。"); return true; }
        Term.info("将补全 " + tasks.size() + " 个依赖库 (线程 " + threadsFlag(cfg, args) + "):");
        boolean ok = Downloader.run(tasks, backendFlag(args), threadsFlag(cfg, args), dryFlag(args));
        Term.succ(ok ? "依赖库补齐完成。" : "依赖库下载存在失败项。");
        return ok;
    }

    private static boolean cmdAsset(List<String> args) {
        Term.warn("asset 资源补全暂以浏览器手动引导为主（资源量大，避免擅自下载）。");
        mccl.core.BrowserGuide.open("https://bmclapi2.bangbang93.com/");
        return true;
    }

    // ---------- mod ----------
    private static boolean cmdMod(ConfigManager cfg, List<String> args) {
        if (args.isEmpty()) { Term.err("用法: mod ls|enable|disable|search|download|browse"); return false; }
        String sub = args.get(0).toLowerCase();
        switch (sub) {
            case "ls":
            case "list": {
                if (args.size() < 2) { Term.err("用法: mod ls <实例>"); return false; }
                List<String> en = ModManager.listEnabled(args.get(1));
                List<String> dis = ModManager.listDisabled(args.get(1));
                Term.line("[已启用] " + en.size() + " 个:"); en.forEach(m -> Term.info("  + " + m));
                Term.line("[已禁用] " + dis.size() + " 个:"); dis.forEach(m -> Term.warn("  - " + m));
                return true;
            }
            case "enable":
            case "disable": {
                if (args.size() < 3) { Term.err("用法: mod " + sub + " <实例> <文件>"); return false; }
                boolean ok = "enable".equals(sub) ? ModManager.enable(args.get(1), args.get(2)) : ModManager.disable(args.get(1), args.get(2));
                Term.succ(ok ? "成功: " + args.get(2) : "失败(文件不存在或状态已正确)");
                return ok;
            }
            case "search": {
                if (args.size() < 2) { Term.err("用法: mod search <关键词> [--game-version v] [--loader l]"); return false; }
                String q = args.get(1);
                String ver = flagValue(args, "--game-version");
                String loader = flagValue(args, "--loader");
                var hits = MirrorSource.modrinthSearch(q, ver, loader);
                if (hits.isEmpty()) { Term.warn("无结果。"); return false; }
                Term.info("Modrinth 搜索结果:");
                for (var h : hits) Term.info("  " + h.projectId() + "  " + h.title());
                Term.info("用 mod download <项目id> <mc版本> <加载器> 下载。");
                return true;
            }
            case "download": {
                if (args.size() < 4) { Term.err("用法: mod download <项目id> <mc版本> <加载器>"); return false; }
                mccl.dl.DownloadTask t = MirrorSource.modrinthFile(args.get(1), args.get(2), args.get(3));
                if (t == null) return false;
                boolean ok = Downloader.run(List.of(t), backendFlag(args), threadsFlag(cfg, args), dryFlag(args));
                Term.succ(ok ? "模组已下载到 game/mods/ (记得移到对应实例 mods 目录)" : "模组下载失败");
                return ok;
            }
            case "browse": {
                if (args.size() < 2) { Term.err("用法: mod browse <关键词>"); return false; }
                String url = "https://modrinth.com/mods?q=" + java.net.URLEncoder.encode(args.get(1), java.nio.charset.StandardCharsets.UTF_8);
                mccl.core.BrowserGuide.open(url);
                return true;
            }
            default: return false;
        }
    }

    // ---------- loader ----------
    private static boolean cmdLoader(List<String> args) {
        if (args.size() < 2) { Term.err("用法: loader download fabric|forge|neoforge | loader rename <实例>"); return false; }
        String which = args.get(1).toLowerCase();
        if ("rename".equalsIgnoreCase(which)) {
            return loaderRename(args);
        }
        Term.warn("加载器需通过官方安装器安装(不走自动下载)。安装完成后可用 loader rename <实例> 重命名实例。");
        switch (which) {
            case "fabric":    mccl.core.BrowserGuide.open(mccl.core.BrowserGuide.fabricUrl()); break;
            case "forge":     mccl.core.BrowserGuide.open(mccl.core.BrowserGuide.forgeUrl()); break;
            case "neoforge":  mccl.core.BrowserGuide.open(mccl.core.BrowserGuide.neoForgeUrl()); break;
            default: Term.err("未知加载器: " + which); return false;
        }
        return true;
    }

    /** loader rename <实例>：自动建议「版本号-加载器名」，询问是否重命名并修复缓存。 */
    private static boolean loaderRename(List<String> args) {
        if (args.size() < 3) { Term.err("用法: loader rename <实例>"); return false; }
        String old = args.get(2);
        if (!InstanceManager.exists(old)) { Term.err("实例不存在: " + old); return false; }
        Version v = VersionResolver.resolve(Loc.game(), old);
        String kind = classify(v, old);
        if (kind.equals("原版")) {
            Term.warn("该实例像是原版，无需自动改名。若想手动改名: instance rename <旧名> <新名>");
            return false;
        }
        String suggested = old + "-" + kind;
        Term.warn("是否将实例自动重命名为便于识别的名称？");
        Term.info("  原名称: " + old);
        Term.info("  建议名称: " + suggested);
        Term.info("  [y] 执行改名(自动修复加载器缓存)  [n] 保持原样");
        if (!confirmYes()) { Term.info("已取消，保持原样。"); return true; }
        if (InstanceManager.exists(suggested)) {
            Term.err("建议名称已存在，自动改名跳过。可用 instance rename 手动改名。");
            return false;
        }
        boolean ok = InstanceManager.rename(old, suggested);
        if (ok) { Term.succ("已将实例重命名为: " + suggested + " (加载器缓存已修复)"); return true; }
        Term.err("改名失败(可能被占用或非法名)。");
        return false;
    }

    // ---------- versions 检测 ----------
    private static boolean cmdVersions(List<String> args) {
        if (args.isEmpty() || "scan".equalsIgnoreCase(args.get(0))) {
            List<String> list = InstanceManager.list();
            Term.line("正在扫描 " + Loc.versions() + " ...");
            if (list.isEmpty()) { Term.warn("未扫描到实例。"); return false; }
            Term.succ("扫描完成，实例索引已刷新 (" + list.size() + " 个):");
            for (String id : list) {
                Version v = VersionResolver.loadRaw(Loc.game(), id);
                Term.info("  - " + id + "  [" + classify(v, id) + "]");
            }
            return true;
        }
        if (args.size() >= 2) {
            String inst = args.get(1);
            if ("info".equalsIgnoreCase(args.get(0))) {
                Version v = VersionResolver.resolve(Loc.game(), inst);
                if (v.id == null) { Term.err("实例无法解析: " + inst); return false; }
                Term.info("实例     : " + v.id);
                Term.info("主类     : " + (v.mainClass != null ? v.mainClass : "(默认)"));
                Term.info("资源索引 : " + (v.assetIndex != null ? v.assetIndex.id : v.id));
                Term.info("依赖库   : " + v.libraries.size() + " 个(合并后)");
                return true;
            }
            if ("check".equalsIgnoreCase(args.get(0))) {
                Version v = VersionResolver.resolve(Loc.game(), inst);
                if (v.id == null) { Term.err("实例无法解析: " + inst); return false; }
                int found = 0, missing = 0;
                for (Version.Library lib : v.libraries) {
                    if (!VersionResolver.libraryApplies(lib)) continue;
                    String rel = VersionResolver.libraryPath(lib);
                    if (rel == null) continue;
                    if (Files.exists(Loc.libraries().resolve(rel))) found++;
                    else { missing++; Term.err("  缺失: " + rel); }
                }
                Term.info("库文件: 存在 " + found + "，缺失 " + missing + "。缺失可运行: lib download " + inst);
                return true;
            }
        }
        return false;
    }

    // ---------- default ----------
    private static boolean cmdDefault(ConfigManager cfg, List<String> args) {
        if (args.isEmpty() || "get".equalsIgnoreCase(args.get(0))) {
            Term.info("当前默认实例: " + (cfg.data.defaultInstance.isBlank() ? "(未设置)" : cfg.data.defaultInstance));
            return true;
        }
        if ("set".equalsIgnoreCase(args.get(0))) {
            if (args.size() < 2) { Term.err("用法: default set <实例>"); return false; }
            if (!InstanceManager.exists(args.get(1))) { Term.err("实例不存在: " + args.get(1)); return false; }
            cfg.data.defaultInstance = args.get(1);
            cfg.save();
            Term.succ("已设置默认实例: " + args.get(1) + "  (不带实例名的 launch/check/log/open 将自动使用)");
            return true;
        }
        if ("clear".equalsIgnoreCase(args.get(0))) {
            cfg.data.defaultInstance = "";
            cfg.save();
            Term.succ("已清除默认实例。");
            return true;
        }
        return false;
    }

    // ---------- clean ----------
    private static boolean cmdClean(List<String> args) {
        if (args.isEmpty()) { Term.err("用法: clean <实例> | clean all"); return false; }
        String target = args.get(0).toLowerCase();
        List<Path> paths = new ArrayList<>();
        String label;
        if ("all".equalsIgnoreCase(target)) {
            label = "全部实例 + 游戏根目录";
            for (String id : InstanceManager.list()) paths.addAll(Cleaner.collectInstance(Loc.instanceDir(id)));
            paths.addAll(Cleaner.collectGameRoot());
        } else {
            label = "实例 [" + args.get(0) + "]";
            if (!InstanceManager.exists(args.get(0))) { Term.err("实例不存在: " + args.get(0)); return false; }
            paths.addAll(Cleaner.collectInstance(Loc.instanceDir(args.get(0))));
        }
        if (paths.isEmpty()) { Term.info("没有可清理的垃圾(日志/崩溃报告/缓存均不存在)。"); return true; }
        long total = 0;
        Term.line("即将清理 " + label + " 的垃圾文件:");
        for (Path p : paths) {
            long sz = Cleaner.sizeOf(p);
            total += sz;
            Term.info("  - " + Loc.game().relativize(p) + "  (" + fmtSize(sz) + ")");
        }
        Term.warn("确认删除？ [y/n]");
        if (!confirmYes()) { Term.info("已取消，未删除任何文件。"); return true; }
        int n = Cleaner.remove(paths);
        Term.succ("清理完成，已删除 " + n + " 项，释放约 " + fmtSize(total) + "。");
        return true;
    }

    private static String fmtSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return bytes + " B";
    }

    // ---------- log ----------
    private static boolean cmdLog(List<String> args) {
        if (args.isEmpty()) { Term.err("用法: log open <实例> | log latest <实例> | log self"); return false; }
        String sub = args.get(0).toLowerCase();
        switch (sub) {
            case "self": {
                Path d = Loc.logs();
                try { Files.createDirectories(d); } catch (Exception ignored) {}
                return openPath(d);
            }
            case "open": {
                if (args.size() < 2) { Term.err("用法: log open <实例>"); return false; }
                Path d = Loc.instanceDir(args.get(1)).resolve("crash-reports");
                if (!Files.isDirectory(d)) { Term.warn("该实例暂无崩溃报告目录: " + args.get(1)); return false; }
                return openPath(d);
            }
            case "latest": {
                if (args.size() < 2) { Term.err("用法: log latest <实例>"); return false; }
                Path p = CrashAnalyzer.findLatestCrash(args.get(1));
                if (p == null) { Term.warn("该实例暂无崩溃报告: " + args.get(1)); return false; }
                return openPath(p);
            }
            default:
                Term.err("未知: " + args.get(0) + "  用法: log open <实例> | log latest <实例> | log self");
                return false;
        }
    }

    private static boolean openPath(Path p) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(p.toFile());
            else new ProcessBuilder("explorer", p.toString()).start();
            Term.succ("已打开: " + p);
            return true;
        } catch (Exception e) {
            Term.err("打开失败: " + p + "  " + e.getMessage());
            return false;
        }
    }

    // ---------- setting ----------
    private static boolean cmdSetting(ConfigManager cfg, List<String> args) {
        if (args.isEmpty()) {
            Term.info("下载线程: " + cfg.data.downloadThreads + "  默认内存: " + cfg.data.defaultMem);
            Term.info("用法: setting <实例> mem <G> | mod-port <端口> | get | download-threads <数字>");
            return true;
        }
        if ("download-threads".equalsIgnoreCase(args.get(0))) {
            if (args.size() < 2) { Term.err("用法: setting download-threads <数字>"); return false; }
            int n = Integer.parseInt(args.get(1));
            if (n < 1) { Term.err("线程数至少为 1。"); return false; }
            cfg.data.downloadThreads = Math.min(n, 16);
            cfg.save();
            Term.succ("全局下载线程已设为: " + cfg.data.downloadThreads + " (上限16)");
            return true;
        }
        // setting <实例> mem|mod-port|get
        String inst = args.get(0);
        if (!InstanceManager.exists(inst)) { Term.err("实例不存在: " + inst); return false; }
        var set = cfg.data.instances.computeIfAbsent(inst, k -> new ConfigManager.InstanceSetting());
        if (args.size() < 2) {
            Term.info("实例 [" + inst + "] 参数: mem=" + (set.mem.isBlank() ? "(默认 " + cfg.data.defaultMem + ")" : set.mem)
                    + "  mod-port=" + (set.modPort == null ? "(未设置)" : set.modPort));
            return true;
        }
        switch (args.get(1).toLowerCase()) {
            case "mem": {
                if (args.size() < 3) { Term.err("用法: setting <实例> mem <G>"); return false; }
                set.mem = args.get(2);
                cfg.save();
                Term.succ("实例 [" + inst + "] 内存已记忆: " + set.mem);
                return true;
            }
            case "mod-port": {
                if (args.size() < 3) { Term.err("用法: setting <实例> mod-port <端口>"); return false; }
                set.modPort = Integer.parseInt(args.get(2));
                cfg.save();
                Term.succ("实例 [" + inst + "] mod-port 已记忆: " + set.modPort);
                return true;
            }
            case "get": {
                Term.info("实例 [" + inst + "] 参数: mem=" + (set.mem.isBlank() ? "(默认 " + cfg.data.defaultMem + ")" : set.mem)
                        + "  mod-port=" + (set.modPort == null ? "(未设置)" : set.modPort));
                return true;
            }
            default:
                Term.err("未知: " + args.get(1) + "  用法: mem|mod-port|get");
                return false;
        }
    }

    // ---------- launch ----------
    private static boolean cmdLaunch(ConfigManager cfg, List<String> args) {
        String inst;
        if (args.isEmpty()) {
            inst = cfg.data.defaultInstance.isBlank() ? null : cfg.data.defaultInstance;
            if (inst == null) {
                inst = pickInstanceMenu();
                if (inst == null) { Term.err("请先创建实例: instance new <名字>"); return false; }
            }
        } else {
            inst = args.get(0);
        }
        if (!InstanceManager.exists(inst)) {
            Term.err("实例不存在: " + inst + " (用 instance list 查看，或用 default set 设为默认)");
            return false;
        }
        // 从实例记忆取默认参数，命令传参覆盖
        var set = cfg.data.instances.get(inst);
        String player = new ProfileManager() {{ load(); }}.currentName();
        String mem = (set != null && !set.mem.isBlank()) ? set.mem : cfg.data.defaultMem;
        Integer modPort = (set != null) ? set.modPort : null;
        boolean dry = false;
        boolean memExplicit = false, portExplicit = false;
        for (int i = 1; i < args.size(); i++) {
            if ("--player".equals(args.get(i)) && i + 1 < args.size()) player = args.get(++i);
            else if ("--mem".equals(args.get(i)) && i + 1 < args.size()) { mem = args.get(++i); memExplicit = true; }
            else if ("--mod-port".equals(args.get(i)) && i + 1 < args.size()) { modPort = Integer.parseInt(args.get(++i)); portExplicit = true; }
            else if ("--dry-run".equals(args.get(i))) dry = true;
        }
        if (player == null || player.isBlank()) {
            Term.err("未选择玩家。请先: player list / player add <名字>");
            return false;
        }
        Version ver = VersionResolver.resolve(Loc.game(), inst);
        if (ver == null || ver.id == null || ver.libraries == null) { Term.err("实例无法解析: " + inst); return false; }

        String java = mccl.core.JavaEnv.locate();
        if (java == null) {
            Term.err("未检测到可用 Java 17+。请安装 JDK/JRE(便携JRE可放 tools/jre)。");
            mccl.core.BrowserGuide.open(mccl.core.BrowserGuide.temurinUrl());
            return false;
        }
        if (cfg.data.defaultJavaPath != null && !cfg.data.defaultJavaPath.isBlank()) java = cfg.data.defaultJavaPath;

        Term.line("启动 " + inst + "  玩家=" + player + "  内存=" + mem + (modPort != null ? "  ModLauncher端口=" + modPort : ""));
        if (dry) {
            List<String> cmd = Launcher.buildCommand(ver, player, mem, java, modPort);
            if (cmd == null) return false;
            Term.line(">>> 预演命令 (--dry-run):");
            Term.info("  " + String.join(" ", cmd));
            return true;
        }
        int code = Launcher.launch(ver, player, mem, java, modPort);
        return code == 0;
    }

    /** 无默认实例且未指定时，列出实例序号菜单供选择。 */
    private static String pickInstanceMenu() {
        List<String> list = InstanceManager.list();
        if (list.isEmpty()) return null;
        Term.line("未指定实例，请选择:");
        int i = 1;
        for (String id : list) {
            Version v = VersionResolver.loadRaw(Loc.game(), id);
            Term.info("  [" + (i++) + "] " + id + "  [" + classify(v, id) + "]");
        }
        Term.info("  输入序号选择(其他输入取消):");
        String sel = readLine();
        if (sel == null) return null;
        try {
            int idx = Integer.parseInt(sel.trim());
            if (idx >= 1 && idx <= list.size()) return list.get(idx - 1);
        } catch (NumberFormatException ignored) {}
        return null;
    }

    /** 交互式读取一行（菜单 / 确认共用）。优先用 REPL 注入的 reader，其次系统控制台。 */
    private static String readLine() {
        LineReader lr = lineReader;
        if (lr != null) {
            try { return lr.readLine(); } catch (Exception e) { return null; }
        }
        try {
            java.io.Console c = System.console();
            if (c != null) return c.readLine();
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean confirmYes() {
        LineReader lr = lineReader;
        if (lr == null && System.console() == null) {
            Term.warn("  (非交互环境，无法确认，已取消。请在交互终端运行。)");
            return false;
        }
        String s = readLine();
        return s != null && (s.trim().equalsIgnoreCase("y") || s.trim().equalsIgnoreCase("yes"));
    }

    // ---------- config ----------
    private static boolean cmdConfig(ConfigManager cfg, List<String> args) {
        Term.info("当前配置:");
        Term.info("  程序目录: " + Loc.base());
        Term.info("  游戏目录: " + Loc.game());
        Term.info("  内存    : " + cfg.data.defaultMem);
        Term.info("  Java    : " + (cfg.data.defaultJavaPath.isBlank() ? "(自动)" : cfg.data.defaultJavaPath));
        Term.info("  下载线程: " + cfg.data.downloadThreads);
        Term.info("  默认实例: " + (cfg.data.defaultInstance.isBlank() ? "(未设置)" : cfg.data.defaultInstance));
        Term.info("  配置文件: config/mccl.json  (绿色本地)");
        return true;
    }

    private static int threadsFlag(ConfigManager cfg, List<String> args) {
        for (int i = 0; i < args.size(); i++)
            if ("--threads".equals(args.get(i)) && i + 1 < args.size()) {
                try { return Math.min(Integer.parseInt(args.get(i + 1)), 16); } catch (NumberFormatException e) { return cfg.data.downloadThreads; }
            }
        return cfg.data.downloadThreads;
    }
    private static String backendFlag(List<String> args) {
        for (int i = 0; i < args.size(); i++) if ("--backend".equals(args.get(i)) && i + 1 < args.size()) return args.get(i + 1);
        return "auto";
    }
    private static boolean dryFlag(List<String> args) {
        for (String a : args) if ("--dry-run".equals(a)) return true;
        return false;
    }
    private static String flagValue(List<String> args, String flag) {
        for (int i = 0; i < args.size(); i++) if (flag.equals(args.get(i)) && i + 1 < args.size()) return args.get(i + 1);
        return null;
    }
}

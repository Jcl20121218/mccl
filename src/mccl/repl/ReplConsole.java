package mccl.repl;

import mccl.cmd.CommandDispatcher;
import mccl.config.ConfigManager;
import mccl.config.ProfileManager;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;

/**
 * 交互式 REPL 终端 mccl>。
 * 进入前先做玩家引导：无档案 -> 输入新建；有档案 -> 列序号选择(可输序号/新名字/回车用当前)。
 */
public class ReplConsole {
    private final ConfigManager cfg;
    private final Scanner scanner = new Scanner(System.in);
    private final ProfileManager pm = new ProfileManager();

    public ReplConsole(ConfigManager cfg) {
        this.cfg = cfg;
        pm.load();
    }

    public void start() {
        mccl.core.Term.line("==========================================");
        mccl.core.Term.info("   MCCL - Mini Craft Launcher (绿色便携)");
        mccl.core.Term.info("   离线昵称 / 版本隔离 / 多线程下载");
        mccl.core.Term.info("   输入 help 查看命令，exit 退出");
        mccl.core.Term.line("==========================================");
        userBootstrap();
        // 注入与 REPL 共用的输入源，供 clean 确认 / 实例选择菜单使用
        CommandDispatcher.lineReader = () -> { try { return scanner.nextLine(); } catch (NoSuchElementException e) { return null; } };
        mccl.core.Term.info("已进入命令行。当前玩家: " + (pm.currentName().isBlank() ? "(无)" : pm.currentName()));
        while (true) {
            System.out.print("mccl> ");
            String line;
            try {
                line = scanner.nextLine().trim();
            } catch (NoSuchElementException e) { break; }
            if (line.isBlank()) continue;
            if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) { mccl.core.Term.info("再见！"); break; }
            String[] parts = line.split("\\s+");
            String cmd = parts[0];
            List<String> rest = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) rest.add(parts[i]);
            try {
                CommandDispatcher.dispatch(cfg, cmd, rest);
            } catch (Exception e) {
                mccl.core.Term.err("命令执行出错: " + e.getMessage());
            }
        }
    }

    private void userBootstrap() {
        List<ProfileManager.Profile> list = pm.list();
        if (list.isEmpty()) {
            System.out.print("检测到没有玩家档案，请输入你的玩家名称: ");
            String name = readLine();
            if (name == null || name.isBlank()) name = "Player";
            pm.add(name);
            System.out.println("已创建玩家: " + name);
            return;
        }
        // 已有玩家：列序号选择
        System.out.println("现有玩家档案，请选择（输入序号 / 新名字创建 / 直接回车用当前）:");
        int i = 1;
        for (ProfileManager.Profile p : list) {
            String mark = p.name.equals(pm.currentName()) ? "  [当前]" : "";
            System.out.println("  [" + (i++) + "] " + p.name + mark);
        }
        System.out.print("选择> ");
        String sel = readLine();
        if (sel == null || sel.isBlank()) {
            if (pm.hasCurrent()) return;
            pm.set(list.get(0).name);
            return;
        }
        // 序号
        try {
            int idx = Integer.parseInt(sel.trim());
            if (idx >= 1 && idx <= list.size()) { pm.set(list.get(idx - 1).name); return; }
        } catch (NumberFormatException ignored) {}
        // 作为新名字
        if (pm.add(sel.trim())) return;
        // 已存在则切换
        pm.set(sel.trim());
    }

    private String readLine() {
        try { return scanner.nextLine(); } catch (NoSuchElementException e) { return null; }
    }
}

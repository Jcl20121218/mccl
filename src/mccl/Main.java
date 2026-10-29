package mccl;

import mccl.cmd.CommandDispatcher;
import mccl.config.ConfigManager;
import mccl.core.Loc;
import mccl.repl.ReplConsole;

import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        try {
            Loc.ensureDirs();
        } catch (Exception e) {
            System.err.println("初始化目录失败: " + e.getMessage());
        }
        ConfigManager cfg = new ConfigManager();
        cfg.load();

        if (args.length == 0) {
            new ReplConsole(cfg).start();
            return;
        }
        String cmd = args[0];
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) rest.add(args[i]);
        boolean ok = CommandDispatcher.dispatch(cfg, cmd, rest);
        System.exit(ok ? 0 : 1);
    }
}

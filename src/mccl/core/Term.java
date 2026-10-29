package mccl.core;

import com.sun.jna.ptr.IntByReference;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Wincon;

/**
 * 终端彩色分层日志（参考 PCL 风格，黑底）。
 *  默认普通信息 = 浅蓝 94 / 成功 = 亮绿 92 / 警告 = 亮黄 93 / 报错 = 亮红 91 / 分隔线 = 亮青 96
 *
 * 兼容策略（无感自适应，不弹窗、不装软件）：
 *  - Windows：启动时通过 SetConsoleMode 开启原生 cmd 的 VT(ANSI) 处理，开启成功才上色；
 *    开启失败（旧系统 / 受限机房 / 输出被重定向）则自动剥掉全部颜色码，输出干净纯文本，不出现方块乱码。
 *  - 非 Windows / Windows Terminal：终端原生支持 ANSI，直接上色。
 *  - 可用环境变量 MCCL_COLOR=0 强制关闭颜色。
 */
public final class Term {
    private Term() {}

    public static final boolean ENABLED;
    /** 是否成功开启（供外部判断）。 */
    public static final boolean VT_ENABLED;

    static {
        boolean on = true;
        boolean vt = false;
        String env = System.getenv("MCCL_COLOR");
        if ("0".equals(env) || "off".equalsIgnoreCase(env)) on = false;

        if (on) {
            java.io.Console c = System.console();
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                // 原生 cmd / conhost：必须显式开启 VT 处理才支持 ANSI
                if (c != null && tryEnableVT()) { on = true; vt = true; }
                else on = false; // 无法开启或输出被重定向 -> 剥色纯文本
            } else {
                // 非 Windows（macOS/Linux 终端）原生支持 ANSI；但重定向到文件时剥色
                on = (c != null);
            }
        }
        ENABLED = on;
        VT_ENABLED = vt;
    }

    /** 尝试为当前控制台开启 ENABLE_VIRTUAL_TERMINAL_PROCESSING。成功返回 true。 */
    private static boolean tryEnableVT() {
        try {
            var handle = Kernel32.INSTANCE.GetStdHandle(Wincon.STD_OUTPUT_HANDLE);
            IntByReference mode = new IntByReference();
            if (!Kernel32.INSTANCE.GetConsoleMode(handle, mode)) return false;
            int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;
            return Kernel32.INSTANCE.SetConsoleMode(handle, mode.getValue() | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
        } catch (Throwable t) {
            return false;
        }
    }

    public static final String RST  = ENABLED ? "\u001b[0m"  : "";
    public static final String INFO = ENABLED ? "\u001b[94m" : ""; // 浅蓝
    public static final String SUCC = ENABLED ? "\u001b[92m" : ""; // 亮绿
    public static final String WARN = ENABLED ? "\u001b[93m" : ""; // 亮黄
    public static final String ERR  = ENABLED ? "\u001b[91m" : ""; // 亮红
    public static final String LINE = ENABLED ? "\u001b[96m" : ""; // 亮青

    public static String color(String code, String s) {
        return ENABLED ? code + s + RST : s;
    }

    public static void info(String s)  { System.out.println(color(INFO, s)); }
    public static void succ(String s)  { System.out.println(color(SUCC, s)); }
    public static void warn(String s)  { System.out.println(color(WARN, s)); }
    public static void err(String s)   { System.out.println(color(ERR,  s)); }
    public static void line(String s)  { System.out.println(color(LINE, s)); }
}

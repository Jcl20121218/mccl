package mccl.core;

import java.awt.Desktop;
import java.net.URI;

/** 浏览器引导工具：找不到外部依赖时跳转官方下载页；无GUI/失败时只打印链接。 */
public final class BrowserGuide {
    private BrowserGuide() {}

    public static void open(String url) {
        System.out.println("  下载页面: " + url);
        if (System.getProperty("mccl.no-browser", "false").equalsIgnoreCase("true")) {
            System.out.println("  (--no-browser 已启用，请手动复制链接打开)");
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                System.out.println("  已自动打开浏览器。");
            } else {
                System.out.println("  当前环境无法自动唤起浏览器，请手动复制上面的链接。");
            }
        } catch (Exception e) {
            System.out.println("  打开链接失败，请手动复制上面的链接。");
        }
    }

    public static String temurinUrl() {
        return "https://adoptium.net/temurin/releases/?version=17";
    }

    public static String fabricUrl() {
        return "https://fabricmc.net/use/installer/";
    }

    public static String forgeUrl() {
        return "https://files.minecraftforge.net/";
    }

    public static String neoForgeUrl() {
        return "https://neoforged.net/";
    }
}

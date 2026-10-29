package mccl.mc;

import java.util.ArrayList;
import java.util.List;

/**
 * version.json 数据模型（与 MC 官方结构对应，仅保留启动所需字段）。
 */
public class Version {
    public String id;
    public String inheritsFrom;
    public String mainClass;
    public String type = "release";
    public AssetIndex assetIndex;
    /** 老版本 (<=1.12) 的启动参数字符串，本期暂不做专门解析 */
    public String minecraftArguments;
    /** 新版 (1.13+) 的结构化启动参数 */
    public Arguments arguments;
    public List<Library> libraries = new ArrayList<>();

    public static class AssetIndex {
        public String id;
    }

    public static class Arguments {
        public List<Object> game = new ArrayList<>();
        public List<Object> jvm = new ArrayList<>();
    }

    public static class Library {
        public String name; // group:artifact:version[:classifier]
        public List<Rule> rules;
        public String url;

        public String[] coords() {
            return name == null ? new String[0] : name.split(":");
        }
    }

    public static class Rule {
        public String action; // allow / deny
        public Os os;
    }

    public static class Os {
        public String name; // windows / osx / linux
    }
}

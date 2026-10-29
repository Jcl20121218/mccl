package mccl.dl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 下载数据源：
 * - BMCLAPI（国内镜像）拉原版版本清单 / version.json / jar / libraries
 * - Modrinth API 搜索与下载模组
 * 均为公开 API，返回结构解析成 DownloadTask 交给 Downloader 执行。
 */
public class MirrorSource {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    public static final String BMCLAPI = "https://bmclapi2.bangbang93.com";

    private static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode() + " " + url);
        return resp.body();
    }

    /** 列出 BMCLAPI 原版版本清单中的 release 版本号。 */
    public static List<String> listVanillaVersions() {
        List<String> out = new ArrayList<>();
        try {
            JsonObject m = GSON.fromJson(get(BMCLAPI + "/mc/game/version_manifest.json"), JsonObject.class);
            JsonArray versions = m.get("versions").getAsJsonArray();
            for (JsonElement v : versions) {
                JsonObject o = v.getAsJsonObject();
                if ("release".equals(o.get("type").getAsString())) out.add(o.get("id").getAsString());
            }
        } catch (Exception e) {
            System.err.println("  拉取版本清单失败: " + e.getMessage());
        }
        return out;
    }

    /** 组装下载原版版本的核心文件任务：version.json + 版本 jar。 */
    public static List<DownloadTask> vanillaCoreTasks(String verId) {
        List<DownloadTask> tasks = new ArrayList<>();
        Path inst = mccl.core.Loc.instanceDir(verId);
        tasks.add(new DownloadTask(BMCLAPI + "/version/" + verId + "/" + verId + ".json",
                inst.resolve(verId + ".json"), null, -1));
        tasks.add(new DownloadTask(BMCLAPI + "/version/" + verId + "/" + verId + ".jar",
                inst.resolve(verId + ".jar"), null, -1));
        return tasks;
    }

    /** 组装 libraries 下载任务（基于已下载的 version.json，缺哪个下哪个）。 */
    public static List<DownloadTask> libraryTasks(String verId) {
        List<DownloadTask> tasks = new ArrayList<>();
        try {
            Path json = mccl.core.Loc.instanceDir(verId).resolve(verId + ".json");
            if (!java.nio.file.Files.exists(json)) { System.out.println("  缺少 version.json，先执行 version download " + verId); return tasks; }
            JsonObject v = GSON.fromJson(java.nio.file.Files.readString(json), JsonObject.class);
            Path libRoot = mccl.core.Loc.libraries();
            for (JsonElement le : v.getAsJsonArray("libraries")) {
                JsonObject lo = le.getAsJsonObject();
                if (!libraryApplies(lo)) continue;
                JsonObject dl = lo.getAsJsonObject("downloads");
                if (dl == null || !dl.has("artifact")) continue;
                JsonObject art = dl.getAsJsonObject("artifact");
                String rel = art.get("path").getAsString();
                String sha1 = art.has("sha1") ? art.get("sha1").getAsString() : null;
                Path target = libRoot.resolve(rel);
                if (java.nio.file.Files.exists(target) && sha1 != null) {
                    if (sha1Ok(target, sha1)) continue;
                }
                tasks.add(new DownloadTask(BMCLAPI + "/libraries/" + rel, target, sha1, -1));
            }
        } catch (Exception e) {
            System.err.println("  解析 libraries 失败: " + e.getMessage());
        }
        return tasks;
    }

    private static boolean libraryApplies(JsonObject lib) {
        if (!lib.has("rules")) return true;
        String current = System.getProperty("os.name").toLowerCase().contains("win") ? "windows"
                : System.getProperty("os.name").toLowerCase().contains("mac") ? "osx" : "linux";
        boolean allowed = false;
        for (JsonElement r : lib.getAsJsonArray("rules")) {
            JsonObject ro = r.getAsJsonObject();
            String action = ro.has("action") ? ro.get("action").getAsString() : null;
            boolean match = true;
            if (ro.has("os") && ro.get("os").getAsJsonObject().has("name")) {
                match = current.equals(ro.get("os").getAsJsonObject().get("name").getAsString());
            }
            if (!match) continue;
            if ("deny".equals(action)) return false;
            if ("allow".equals(action)) allowed = true;
        }
        return allowed;
    }

    /** Modrinth 搜索模组。 */
    public static List<ModHit> modrinthSearch(String query, String mcVer, String loader) {
        List<ModHit> out = new ArrayList<>();
        try {
            StringBuilder url = new StringBuilder("https://api.modrinth.com/v2/search?query=")
                    .append(java.net.URLEncoder.encode(query, "UTF-8"))
                    .append("&limit=10");
            StringBuilder facets = new StringBuilder("[");
            if (mcVer != null && !mcVer.isBlank()) facets.append("[\"versions:").append(mcVer).append("\"]");
            if (loader != null && !loader.isBlank()) {
                if (!facets.toString().equals("[")) facets.append(",");
                facets.append("[\"categories:").append(loader).append("\"]");
            }
            facets.append("]");
            url.append("&facets=").append(java.net.URLEncoder.encode(facets.toString(), "UTF-8"));
            JsonObject r = GSON.fromJson(get(url.toString()), JsonObject.class);
            for (JsonElement h : r.getAsJsonArray("hits")) {
                JsonObject o = h.getAsJsonObject();
                out.add(new ModHit(o.has("project_id") ? o.get("project_id").getAsString() : "",
                        o.has("title") ? o.get("title").getAsString() : "",
                        o.has("slug") ? o.get("slug").getAsString() : ""));
            }
        } catch (Exception e) {
            System.err.println("  Modrinth 搜索失败: " + e.getMessage());
        }
        return out;
    }

    /** 取 Modrinth 项目指定版本/加载器的下载文件。 */
    public static DownloadTask modrinthFile(String projectId, String mcVer, String loader) {
        try {
            String url = "https://api.modrinth.com/v2/project/" + projectId + "/version"
                    + (mcVer != null ? "?game_versions=[\"" + mcVer + "\"]" : "")
                    + (loader != null ? (mcVer != null ? "&" : "?") + "loaders=[\"" + loader + "\"]" : "");
            JsonArray versions = GSON.fromJson(get(url), JsonArray.class);
            if (versions.isEmpty()) { System.out.println("  该模组没有匹配 版本=" + mcVer + " 加载器=" + loader + " 的文件"); return null; }
            JsonObject v = versions.get(0).getAsJsonObject();
            JsonObject file = v.getAsJsonArray("files").get(0).getAsJsonObject();
            String fname = file.get("filename").getAsString();
            String furl = file.get("url").getAsString();
            String sha1 = null;
            if (file.has("hashes") && file.get("hashes").getAsJsonObject().has("sha1"))
                sha1 = file.get("hashes").getAsJsonObject().get("sha1").getAsString();
            return new DownloadTask(furl, mccl.core.Loc.game().resolve("mods").resolve(fname), sha1, -1);
        } catch (Exception e) {
            System.err.println("  Modrinth 取文件失败: " + e.getMessage());
            return null;
        }
    }

    private static boolean sha1Ok(Path p, String expected) {
        try (var in = java.nio.file.Files.newInputStream(p)) {
            var md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 15]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return java.util.HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(expected);
        } catch (Exception e) { return false; }
    }

    public record ModHit(String projectId, String title, String slug) {}
}

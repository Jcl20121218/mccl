package mccl.dl;

import java.nio.file.Path;

/** 单个下载任务：URL、目标路径、可选 sha1 校验、可选已知大小。 */
public record DownloadTask(String url, Path outPath, String sha1, long size) {}

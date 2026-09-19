package cn.teamone.eng.infra.git;

/**
 * Git 文件内容投影（GitPort 接口契约，M2-INC-3 U1）。
 *
 * @param path     相对文件路径
 * @param size     内容字节数
 * @param content  文本内容（若为二进制文件则为 null）
 * @param isBinary 是否为二进制文件
 * @author Ivan Yang, 2026-09-13
 */
public record GitBlob(String path, long size, String content, boolean isBinary) {
}

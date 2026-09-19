package cn.teamone.eng.infra.git;

/**
 * Git 目录树条目投影（GitPort 接口契约，对应 git ls-tree -l 输出，M2-INC-3 U1）。
 *
 * @param mode 权限模式（如 100644, 040000）
 * @param type 类型：blob 或 tree
 * @param sha  对象 sha
 * @param size 文件大小（字节；tree 时为 null）
 * @param name 文件或目录名（如 pom.xml）
 * @param path 相对仓库根的完整路径（如 server/pom.xml）
 * @author Ivan Yang, 2026-09-13
 */
public record GitTreeItem(String mode, String type, String sha, Long size, String name, String path) {

    public boolean isDirectory() {
        return "tree".equalsIgnoreCase(type);
    }
}

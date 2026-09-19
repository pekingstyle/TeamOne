package cn.teamone.eng.infra.git;

import java.nio.file.Path;
import java.util.List;

/**
 * Git 内核唯一出墙口（07 §2.3 纪律：所有 git 调用统一经 GitPort——进程封装 + 超时 +
 * 参数白名单校验，退出码非零/超时抛 {@code BusinessException(SRV_5030)}；
 * 禁止业务代码散调 git 命令行，ArchUnit/CodeReview 双保险）。
 *
 * <p>实现：{@link GitCommandPort}（ProcessBuilder 直调 git 二进制，M1）。</p>
 *
 * @author Ivan Yang, 2026-09-12
 */
public interface GitPort {

    /**
     * 取 oldRev..newRev 区间（不含 oldRev）的非合并提交，按时间正序（git log 缺省序）。
     *
     * <p>oldRev 为全零（首次 push，无父提交）时等价于列出 newRev 可达的全部非合并提交。
     * 实现必须：白名单校验（repoKey/rev）、5s 超时强杀、退出码非零抛 SRV_5030。</p>
     *
     * @param repoKey 仓库键（相对 TEAMONE_GIT_ROOT，如 teamone/web.git）
     * @param oldRev  区间旧端 sha（全零表示首次 push）
     * @param newRev  区间新端 sha
     * @return 区间提交（空区间返回空表）
     */
    List<GitCommit> logRange(String repoKey, String oldRev, String newRev);

    /**
     * 列出仓库所有本地分支及其最新提交。
     *
     * @param repoKey 仓库键（相对 TEAMONE_GIT_ROOT，如 teamone/teamone.git）
     * @return 分支列表（空仓返回空表）
     */
    List<GitBranch> branches(String repoKey);

    /**
     * 在 bare 仓库创建分支（git branch &lt;name&gt; &lt;startRef&gt;，服务端分支管理）。
     *
     * <p>实现必须：分支名/起点 ref 白名单校验（防注入）、5s 超时强杀、退出码非零抛 SRV_5030；
     * 「已存在」的 409 预判由调用方先经 {@link #branches} 完成（进程出口保持单一职责）。</p>
     *
     * @param repoKey  仓库键
     * @param name     新分支名（ref 白名单，如 feature/x）
     * @param startRef 起点引用（分支/tag/sha；null/空白取 HEAD）
     */
    void createBranch(String repoKey, String name, String startRef);

    /**
     * 强删 bare 仓库分支（git branch -D）。
     *
     * <p>受保护分支（branch_protection 命中）的 409 预判由调用方完成——本方法不做策略判断；
     * 分支不存在时退出码非零抛 SRV_5030，调用方宜先经 {@link #branches} 预判给 404。</p>
     *
     * @param repoKey 仓库键
     * @param name    待删除分支名
     */
    void deleteBranch(String repoKey, String name);

    /**
     * 列出仓库所有标签。
     *
     * @param repoKey 仓库键（相对 TEAMONE_GIT_ROOT，如 teamone/teamone.git）
     * @return 标签列表（无标签返回空表）
     */
    List<GitTag> tags(String repoKey);

    /**
     * 分页查询指定 ref（分支/commit/tag）的历史提交（按时间倒序）。
     *
     * @param repoKey 仓库键
     * @param ref     引用名（分支/tag/sha，为空则默认为 HEAD/默认分支）
     * @param page    第几页（从 1 开始）
     * @param size    每页条目数
     * @return 提交列表
     */
    List<GitCommit> commits(String repoKey, String ref, int page, int size);

    /**
     * 统计指定 ref 的总提交数。
     *
     * @param repoKey 仓库键
     * @param ref     引用名
     * @return 提交总数
     */
    int commitCount(String repoKey, String ref);

    /**
     * 查询指定 ref 与路径下的目录树节点。
     *
     * @param repoKey 仓库键
     * @param ref     引用名（分支/tag/sha）
     * @param path    相对路径（空串或 null 表示仓库根目录）
     * @return 树节点列表（目录优先，后跟文件）
     */
    List<GitTreeItem> tree(String repoKey, String ref, String path);

    /**
     * 读取指定 ref 与路径下的文件内容。
     *
     * @param repoKey 仓库键
     * @param ref     引用名（分支/tag/sha）
     * @param path    相对文件路径
     * @return 文件内容投影
     */
    GitBlob blob(String repoKey, String ref, String path);

    /**
     * 对比两分支/Ref（U5）：计算共同祖先、提交列表、三路 Diff 与可合并性。
     *
     * @param repoKey 仓库键
     * @param target  目标分支/Ref
     * @param source  源分支/Ref
     * @return 对比聚合结果
     */
    GitCompareResult compare(String repoKey, String target, String source);

    /**
     * 获取源分支相对目标分支的三路 Diff 结果（target...source）。
     *
     * @param repoKey 仓库键
     * @param target  目标分支/Ref
     * @param source  源分支/Ref
     * @return 差异集
     */
    GitDiffResult diff(String repoKey, String target, String source);

    /**
     * 检测两分支的可合并性与冲突文件（U7）。
     *
     * @param repoKey 仓库键
     * @param target  目标分支
     * @param source  源分支
     * @return 可合并性与冲突文件清单
     */
    GitMergeCheckResult checkMerge(String repoKey, String target, String source);

    /**
     * 执行原生服务端合并（U7）：纯内存 tree 合并 + 提交节点生成 + 原子更新分支 Ref。
     *
     * @param repoKey     仓库键
     * @param target      目标分支
     * @param source      源分支
     * @param message     合并 Commit Message
     * @param authorName  合并人姓名
     * @param authorEmail 合并人邮箱
     * @return 生成的新合并提交 Commit SHA
     */
    String merge(String repoKey, String target, String source, String message, String authorName, String authorEmail);

    /**
     * 创建带签名的附注标签（Annotated Tag）并冻结基线（U10）。
     *
     * @param repoKey     仓库键
     * @param tagName     标签名称（如 v2.4.0-baseline）
     * @param target      目标 Ref 或 Commit SHA
     * @param message     标签附注说明
     * @param authorName  创建人姓名
     * @param authorEmail 创建人邮箱
     * @return 标签指向的 Commit SHA
     */
    String createTag(String repoKey, String tagName, String target, String message, String authorName, String authorEmail);

    /**
     * 删除标签。
     *
     * @param repoKey 仓库键
     * @param tagName 标签名称
     */
    void deleteTag(String repoKey, String tagName);

    /**
     * 对指定文件进行逐行代码历史溯源（git blame --porcelain）。
     * <p>
     * 支持裸仓（--git-dir）直接计算，输出按行排序的提交归属元数据。
     * </p>
     *
     * @param repoKey  仓库键（如 teamone/teamone.git）
     * @param revision 目标分支、Tag 或 Commit SHA
     * @param filePath 仓库内的相对文件路径（如 "src/main/App.java"）
     * @return 包含各行提交作者、时间与源码内容的 Blame 快照
     */
    GitBlameResult blame(String repoKey, String revision, String filePath);

    /**
     * 在指定版本下全文检索代码关键字（git grep -n -I）（V-21 / U4）。
     * <p>
     * 在服务端裸库中执行快速内容级文本检索，返回匹配的文件路径、行号与源码行。
     * </p>
     *
     * @param repoKey     仓库键（如 teamone/teamone.git）
     * @param ref         目标版本（分支名、Tag 或 Commit SHA，缺省 HEAD）
     * @param query       检索关键词
     * @param pathPattern 限定文件路径/通配符（可选，如 "*.java" 或 "server"）
     * @param maxResults  最大返回条数（上限通常为 100）
     * @return 结构化全文检索命中集
     */
    GitSearchResult search(String repoKey, String ref, String query, String pathPattern, int maxResults);

    /**
     * 初始化全新 bare 仓库（⑥h 建仓批）：{@code git init --bare <GIT_ROOT>/<name>/<name>.git}
     * 并把 HEAD 符号引用指向 defaultBranch（缺省 main）。
     *
     * <p>实现必须：name 白名单校验（字母/数字/_/-，1~64，对齐 repoKey 段规则）、defaultBranch
     * ref 白名单校验、目标目录已存在时拒绝（防覆盖既有仓库，409 由调用方先经数据查重预判，
     * 此处为文件系统层兜底）、每步超时强杀、退出码非零抛 SRV_5030。</p>
     *
     * @param name          仓库名（同时是 GIT_ROOT 下的目录名与 eng.repository.name）
     * @param defaultBranch 默认分支名（null/空白取 main）
     */
    void initRepo(String name, String defaultBranch);

    /**
     * cherry-pick（部分合并，分支治理批）：把一个提交的改动拣选到目标分支顶端并生成新提交。
     *
     * <p>bare 仓库不能直接 cherry-pick——实现须经临时 worktree：挂 detach worktree 到
     * 目标分支顶端 → 在 worktree 内 {@code cherry-pick -x}（留痕引用来源）→ 成功后以
     * CAS 方式（update-ref 带旧值）快进目标分支引用 → finally 强制摘除 worktree 并清理
     * 临时目录。实现必须：repoKey/commitSha/targetBranch 白名单校验、目标分支与源提交
     * 存在性预判、冲突抛 ENG_4251（附冲突文件清单）、每步超时强杀。</p>
     *
     * @param repoKey      仓库键（相对 TEAMONE_GIT_ROOT，如 teamone/teamone.git）
     * @param commitSha    待拣选的完整 40 位十六进制提交 SHA
     * @param targetBranch 落地目标分支（须已存在）
     * @return 拣选生成的新提交 SHA
     */
    String cherryPick(String repoKey, String commitSha, String targetBranch);

    /**
     * 导出指定 ref 的源码归档并解包到目标目录（M4-INC1 · docs/v2/11 §4.3/D5：
     * {@code git archive <ref>} 代替 clone，裸库免检出，作为 CI 作业工作区）。
     *
     * <p>实现必须：repoKey/ref 白名单校验、destDir 已存在且为目录、tar 条目路径越界
     * （.. / 绝对路径）拒绝、超时强杀、退出码非零抛 SRV_5030；目标目录内容物只增不查，
     * 清空与回收由调用方（Runner）负责。</p>
     *
     * @param repoKey 仓库键（相对 TEAMONE_GIT_ROOT，如 teamone/teamone.git）
     * @param ref     引用名（分支/tag/sha；通常为 run 的 commitSha，缺省语义不适用——须显式）
     * @param destDir 解包目标目录（须已存在；作业工作区）
     */
    void exportArchive(String repoKey, String ref, Path destDir);
}

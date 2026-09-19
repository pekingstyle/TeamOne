package cn.teamone.eng.infra.git;

import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link GitPort} 的进程实现（07 §2.3 纪律的落地件，设计蓝本 Gitea modules/git）。
 *
 * <p>红线：唯一的 git 出墙口——业务代码禁止散调 git；每次调用做
 * <b>参数白名单校验（防注入）→ ProcessBuilder（无 shell，参数数组直传）→ 5s 超时强杀 →
 * 退出码非零抛 {@code SRV_5030}</b>（错误码复用裁决：不新造 ENG 5xxx，05 §3.2 已把
 * 「Git 仓库目录超时或失联」注册在 SRV_5030）。</p>
 *
 * @author Ivan Yang, 2026-09-12
 */
@Component
public class GitCommandPort implements GitPort {

    private static final Logger log = LoggerFactory.getLogger(GitCommandPort.class);

    /** repoKey 白名单：段字符限字母/数字/下划线/连字符，多段用 /，且必须以 .git 结尾（无 . 无 \，杜绝 ../ 逃逸） */
    private static final String REPO_KEY_PATTERN = "[a-zA-Z0-9_\\-/]+\\.git";
    /** rev 白名单：sha 或短 sha（push hook 只传 sha） */
    private static final String REV_PATTERN = "[0-9a-f]{6,40}";
    /** ref 名称白名单：分支名/tag/短sha/HEAD 等 */
    private static final String REF_PATTERN = "^[a-zA-Z0-9_./\\-]{1,100}$";
    /** 文件路径白名单：相对路径，无 .. 无前导 / */
    private static final String PATH_PATTERN = "^[a-zA-Z0-9_./\\-]{1,300}$";
    /** git log 字段分隔符：NUL（%x00），subject/body 内不会出现 */
    private static final char NUL = '\0';
    /** 单命令超时（07 §2.2：hook 同步处理整体 <5s 的硬边界） */
    private static final long TIMEOUT_SECONDS = 5;

    /**
     * 首段 %x00 兼作<b>记录</b>分隔符：每条记录以 NUL 开头（%b 内不可能出现 NUL），
     * 记录间仅有换行也不会把下一条 sha 并入上一条 body（单测 parseLog_* 锁定该边界）。
     */
    private static final String LOG_FORMAT = "--format=%x00%H%x00%an%x00%aI%x00%s%x00%b";

    private final String gitRoot;

    public GitCommandPort(@Value("${teamone.git.root:}") String gitRoot) {
        this.gitRoot = gitRoot == null ? "" : gitRoot.trim();
    }

    @Override
    public List<GitCommit> logRange(String repoKey, String oldRev, String newRev) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        requireMatch(REV_PATTERN, oldRev, "oldRev");
        requireMatch(REV_PATTERN, newRev, "newRev");
        String gitDir = resolveGitDir(repoKey);
        String stdout = exec(buildLogCommand(gitDir, oldRev, newRev), repoKey);
        return parseLog(stdout);
    }

    @Override
    public List<GitBranch> branches(String repoKey) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        String gitDir = resolveGitDir(repoKey);
        List<String> cmd = List.of("git", "--git-dir", gitDir, "for-each-ref",
                "--format=%(refname:short)%00%(objectname)%00%(authordate:iso-strict)%00%(subject)%00",
                "refs/heads/");
        String stdout = exec(cmd, repoKey);
        return parseBranches(stdout);
    }

    @Override
    public List<GitTag> tags(String repoKey) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        String gitDir = resolveGitDir(repoKey);
        List<String> cmd = List.of("git", "--git-dir", gitDir, "for-each-ref",
                "--format=%(refname:short)%00%(objectname)%00%(authordate:iso-strict)%00%(contents:subject)%00",
                "refs/tags/");
        String stdout = exec(cmd, repoKey);
        return parseTags(stdout);
    }

    @Override
    public void createBranch(String repoKey, String name, String startRef) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "分支名称不能为空");
        }
        String cleanName = name.trim();
        validateRef(cleanName);
        String cleanStart = (startRef == null || startRef.isBlank()) ? "HEAD" : startRef.trim();
        validateRef(cleanStart);
        String gitDir = resolveGitDir(repoKey);
        exec(List.of("git", "--git-dir", gitDir, "branch", cleanName, cleanStart), repoKey);
    }

    @Override
    public void deleteBranch(String repoKey, String name) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "分支名称不能为空");
        }
        String cleanName = name.trim();
        validateRef(cleanName);
        String gitDir = resolveGitDir(repoKey);
        exec(List.of("git", "--git-dir", gitDir, "branch", "-D", cleanName), repoKey);
    }

    @Override
    public List<GitCommit> commits(String repoKey, String ref, int page, int size) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        String cleanRef = (ref == null || ref.isBlank()) ? "HEAD" : ref.trim();
        validateRef(cleanRef);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        int skip = (safePage - 1) * safeSize;
        String gitDir = resolveGitDir(repoKey);
        List<String> cmd = List.of("git", "--git-dir", gitDir, "log", cleanRef,
                "--skip=" + skip, "--max-count=" + safeSize, LOG_FORMAT);
        String stdout = exec(cmd, repoKey);
        return parseLog(stdout);
    }

    @Override
    public int commitCount(String repoKey, String ref) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        String cleanRef = (ref == null || ref.isBlank()) ? "HEAD" : ref.trim();
        validateRef(cleanRef);
        String gitDir = resolveGitDir(repoKey);
        List<String> cmd = List.of("git", "--git-dir", gitDir, "rev-list", "--count", cleanRef);
        String stdout = exec(cmd, repoKey);
        try {
            return Integer.parseInt(stdout.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public List<GitTreeItem> tree(String repoKey, String ref, String path) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        String cleanRef = (ref == null || ref.isBlank()) ? "HEAD" : ref.trim();
        validateRef(cleanRef);
        validatePath(path);
        String cleanPath = (path == null) ? "" : path.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        String gitDir = resolveGitDir(repoKey);
        List<String> cmd;
        if (cleanPath.isEmpty()) {
            cmd = List.of("git", "--git-dir", gitDir, "ls-tree", "-l", cleanRef);
        } else {
            cmd = List.of("git", "--git-dir", gitDir, "ls-tree", "-l", cleanRef + ":" + cleanPath);
        }
        String stdout = exec(cmd, repoKey);
        return parseTree(stdout, cleanPath);
    }

    @Override
    public GitBlob blob(String repoKey, String ref, String path) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        String cleanRef = (ref == null || ref.isBlank()) ? "HEAD" : ref.trim();
        validateRef(cleanRef);
        if (path == null || path.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "文件路径不能为空");
        }
        validatePath(path);
        String cleanPath = path.trim().replaceAll("^/+", "");
        String gitDir = resolveGitDir(repoKey);
        List<String> cmd = List.of("git", "--git-dir", gitDir, "show", cleanRef + ":" + cleanPath);
        String stdout = exec(cmd, repoKey);
        boolean isBinary = false;
        int checkLen = Math.min(stdout.length(), 8000);
        for (int i = 0; i < checkLen; i++) {
            if (stdout.charAt(i) == '\0') {
                isBinary = true;
                break;
            }
        }
        long size = stdout.getBytes(StandardCharsets.UTF_8).length;
        if (isBinary) {
            return new GitBlob(cleanPath, size, null, true);
        }
        return new GitBlob(cleanPath, size, stdout, false);
    }

    @Override
    public GitCompareResult compare(String repoKey, String target, String source) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        String cleanTarget = (target == null || target.isBlank()) ? "main" : target.trim();
        String cleanSource = (source == null || source.isBlank()) ? "main" : source.trim();
        validateRef(cleanTarget);
        validateRef(cleanSource);
        String gitDir = resolveGitDir(repoKey);

        String baseSha = null;
        ExecResult baseRes = execFull(List.of("git", "--git-dir", gitDir, "merge-base", cleanTarget, cleanSource), repoKey, null, false);
        if (baseRes.exitCode() == 0 && !baseRes.stdout().isBlank()) {
            baseSha = baseRes.stdout().trim();
        }

        String targetSha = exec(List.of("git", "--git-dir", gitDir, "rev-parse", cleanTarget), repoKey).trim();
        String sourceSha = exec(List.of("git", "--git-dir", gitDir, "rev-parse", cleanSource), repoKey).trim();

        List<String> logCmd = List.of("git", "--git-dir", gitDir, "log", cleanTarget + ".." + cleanSource, LOG_FORMAT);
        String logStdout = exec(logCmd, repoKey);
        List<GitCommit> commits = parseLog(logStdout);

        GitDiffResult diff = diff(repoKey, cleanTarget, cleanSource);
        GitMergeCheckResult mergeCheck = checkMerge(repoKey, cleanTarget, cleanSource);

        return new GitCompareResult(baseSha, targetSha, sourceSha, commits, diff, mergeCheck);
    }

    @Override
    public GitDiffResult diff(String repoKey, String target, String source) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        String cleanTarget = (target == null || target.isBlank()) ? "main" : target.trim();
        String cleanSource = (source == null || source.isBlank()) ? "main" : source.trim();
        validateRef(cleanTarget);
        validateRef(cleanSource);
        String gitDir = resolveGitDir(repoKey);

        List<String> cmd = List.of("git", "--git-dir", gitDir, "diff", "-U3", cleanTarget + "..." + cleanSource);
        String stdout = exec(cmd, repoKey);
        return parseUnifiedDiff(stdout);
    }

    @Override
    public GitMergeCheckResult checkMerge(String repoKey, String target, String source) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        String cleanTarget = (target == null || target.isBlank()) ? "main" : target.trim();
        String cleanSource = (source == null || source.isBlank()) ? "main" : source.trim();
        validateRef(cleanTarget);
        validateRef(cleanSource);
        String gitDir = resolveGitDir(repoKey);

        // 1. Rebase 检测：target 是否为 source 的祖先
        boolean rebaseRequired = false;
        ExecResult ancestorRes = execFull(List.of("git", "--git-dir", gitDir, "merge-base", "--is-ancestor", cleanTarget, cleanSource), repoKey, null, false);
        if (ancestorRes.exitCode() == 1) {
            rebaseRequired = true;
        }

        // 2. 冲突检测：git merge-tree --write-tree
        ExecResult mergeTreeRes = execFull(List.of("git", "--git-dir", gitDir, "merge-tree", "--write-tree", cleanTarget, cleanSource), repoKey, null, false);
        boolean canMerge = (mergeTreeRes.exitCode() == 0);
        List<String> conflictFiles = parseConflictFiles(mergeTreeRes.stdout() + "\n" + mergeTreeRes.stderr());

        return new GitMergeCheckResult(canMerge, rebaseRequired, conflictFiles);
    }

    @Override
    public String merge(String repoKey, String target, String source, String message, String authorName, String authorEmail) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        String cleanTarget = (target == null || target.isBlank()) ? "main" : target.trim();
        String cleanSource = (source == null || source.isBlank()) ? "main" : source.trim();
        validateRef(cleanTarget);
        validateRef(cleanSource);
        String gitDir = resolveGitDir(repoKey);

        GitMergeCheckResult check = checkMerge(repoKey, cleanTarget, cleanSource);
        if (!check.canMerge()) {
            throw new BusinessException(ErrorCode.ENG_4251, "存在未解决冲突，无法自动合并: " + check.conflictFiles());
        }
        if (check.rebaseRequired()) {
            throw new BusinessException(ErrorCode.ENG_4252, "源分支落后目标分支，请先完成 rebase");
        }

        ExecResult treeRes = execFull(List.of("git", "--git-dir", gitDir, "merge-tree", "--write-tree", cleanTarget, cleanSource), repoKey, null, false);
        String treeSha = treeRes.stdout().lines().findFirst().orElse("").trim();
        if (treeSha.length() != 40) {
            throw new BusinessException(ErrorCode.ENG_4251, "无法写入 tree 对象或存在未解决冲突");
        }

        String targetSha = exec(List.of("git", "--git-dir", gitDir, "rev-parse", cleanTarget), repoKey).trim();
        String sourceSha = exec(List.of("git", "--git-dir", gitDir, "rev-parse", cleanSource), repoKey).trim();

        Map<String, String> env = Map.of(
                "GIT_AUTHOR_NAME", (authorName != null && !authorName.isBlank()) ? authorName : "TeamOne",
                "GIT_AUTHOR_EMAIL", (authorEmail != null && !authorEmail.isBlank()) ? authorEmail : "teamone@teamone.cn",
                "GIT_COMMITTER_NAME", "TeamOne",
                "GIT_COMMITTER_EMAIL", "teamone@teamone.cn"
        );
        String commitMsg = (message != null && !message.isBlank())
                ? message
                : "Merge branch '" + cleanSource + "' into '" + cleanTarget + "'";
        List<String> commitCmd = List.of("git", "--git-dir", gitDir, "commit-tree", treeSha,
                "-p", targetSha, "-p", sourceSha, "-m", commitMsg);
        String newCommitSha = execFull(commitCmd, repoKey, env, true).stdout().trim();

        String refTarget = cleanTarget.startsWith("refs/heads/") ? cleanTarget : "refs/heads/" + cleanTarget;
        List<String> updateCmd = List.of("git", "--git-dir", gitDir, "update-ref", refTarget, newCommitSha, targetSha);
        exec(updateCmd, repoKey);

        return newCommitSha;
    }

    @Override
    public String createTag(String repoKey, String tagName, String target, String message, String authorName, String authorEmail) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        if (tagName == null || tagName.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "标签名称不能为空");
        }
        String cleanTagName = tagName.trim();
        validateRef(cleanTagName);
        String cleanTarget = (target == null || target.isBlank()) ? "main" : target.trim();
        validateRef(cleanTarget);
        String gitDir = resolveGitDir(repoKey);

        // 检查 tag 是否已存在
        ExecResult checkTag = execFull(List.of("git", "--git-dir", gitDir, "rev-parse", "-q", "--verify", "refs/tags/" + cleanTagName), repoKey, null, false);
        if (checkTag.exitCode() == 0) {
            throw new BusinessException(ErrorCode.ENG_4250, "标签已存在，基线禁止覆盖已定版标签: " + cleanTagName);
        }

        String targetSha = exec(List.of("git", "--git-dir", gitDir, "rev-parse", cleanTarget), repoKey).trim();
        String tagMsg = (message != null && !message.isBlank()) ? message.trim() : "Baseline tag " + cleanTagName;

        Map<String, String> env = Map.of(
                "GIT_AUTHOR_NAME", (authorName != null && !authorName.isBlank()) ? authorName : "TeamOne",
                "GIT_AUTHOR_EMAIL", (authorEmail != null && !authorEmail.isBlank()) ? authorEmail : "teamone@teamone.cn",
                "GIT_COMMITTER_NAME", (authorName != null && !authorName.isBlank()) ? authorName : "TeamOne",
                "GIT_COMMITTER_EMAIL", (authorEmail != null && !authorEmail.isBlank()) ? authorEmail : "teamone@teamone.cn"
        );

        List<String> tagCmd = List.of("git", "--git-dir", gitDir, "tag", "-a", cleanTagName, targetSha, "-m", tagMsg);
        execFull(tagCmd, repoKey, env, true);

        return targetSha;
    }

    @Override
    public void deleteTag(String repoKey, String tagName) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        if (tagName == null || tagName.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "标签名称不能为空");
        }
        String cleanTagName = tagName.trim();
        validateRef(cleanTagName);
        String gitDir = resolveGitDir(repoKey);

        exec(List.of("git", "--git-dir", gitDir, "tag", "-d", cleanTagName), repoKey);
    }

    // ---------- 内部：路径/执行/解析 ----------

    /** {TEAMONE_GIT_ROOT}/{repoKey}，越界复检（白名单已禁 ..，此处纵深防御） */
    private String resolveGitDir(String repoKey) {
        if (gitRoot.isEmpty()) {
            // 容错：.env 未配 TEAMONE_GIT_ROOT 不得炸启动，只在真正调用 git 时失败（W4 收口前总监配置）
            throw new BusinessException(ErrorCode.SRV_5030, "TEAMONE_GIT_ROOT 未配置，Git 仓库目录不可用");
        }
        Path root = Path.of(gitRoot).toAbsolutePath().normalize();
        Path dir = root.resolve(repoKey).normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            throw new BusinessException(ErrorCode.SRV_5030, "Git 仓库目录不存在或越界: " + repoKey);
        }
        return dir.toString();
    }

    record ExecResult(int exitCode, String stdout, String stderr) {}

    private String exec(List<String> cmd, String repoKey) {
        return execFull(cmd, repoKey, null, true, TIMEOUT_SECONDS).stdout();
    }

    private ExecResult execFull(List<String> cmd, String repoKey, Map<String, String> extraEnv, boolean throwOnError) {
        return execFull(cmd, repoKey, extraEnv, throwOnError, TIMEOUT_SECONDS);
    }

    /** 超时可变体：cherry-pick 的 worktree 检出在大仓库/慢盘上会超过常规 5s，须放宽 */
    private ExecResult execFull(List<String> cmd, String repoKey, Map<String, String> extraEnv,
                                boolean throwOnError, long timeoutSeconds) {
        log.debug("[git] repo={} cmd={}", repoKey, cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putIfAbsent("GIT_TERMINAL_PROMPT", "0"); // 绝不交互挂起
        if (extraEnv != null) {
            pb.environment().putAll(extraEnv);
        }
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SRV_5030, "git 进程启动失败（git 不可用？）");
        }
        try {
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outDrain = drain(proc.getInputStream(), stdout);
            Thread errDrain = drain(proc.getErrorStream(), stderr);
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new BusinessException(ErrorCode.SRV_5030,
                        "git 命令超时（>" + timeoutSeconds + "s），Git 仓库目录不可用");
            }
            outDrain.join(1000);
            errDrain.join(1000);
            int code = proc.exitValue();
            if (code != 0 && throwOnError) {
                String err = stderr.toString();
                log.warn("[git] repo={} exit={} stderr={}", repoKey, code, tail(err));
                if (err.contains("Not a valid object name") || err.contains("does not exist") || err.contains("fatal: path")) {
                    throw new BusinessException(ErrorCode.PLT_4040, "指定的分支、提交或路径不存在");
                }
                throw new BusinessException(ErrorCode.SRV_5030, "git 命令失败（exit " + code + "），Git 仓库目录不可用");
            }
            return new ExecResult(code, stdout.toString(), stderr.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SRV_5030, "git 命令等待被中断");
        } finally {
            if (proc.isAlive()) { // 超时/中断路径的兜底强杀；已退出进程为无害空操作
                proc.destroyForcibly();
            }
        }
    }

    /**
     * NUL 分列解析：format 首段 %x00 使每条记录以 NUL 开头，故按 \0 切分后首元素为空串、
     * 之后每 5 段 = sha | author | date | subject | body；段内换行（body 多行）安全保留。
     */
    static List<GitCommit> parseLog(String stdout) {
        List<GitCommit> commits = new ArrayList<>();
        if (stdout == null || stdout.isEmpty()) {
            return commits;
        }
        String[] f = stdout.split("\0", -1);
        int start = f[0].isEmpty() ? 1 : 0; // 首记录的前导 NUL 产生空首段
        for (int i = start; i + 4 < f.length; i += 5) {
            String sha = strip(f[i]);
            if (sha.length() < 6 || !sha.matches("[0-9a-f]{6,40}")) {
                continue; // 截断/异常记录保护
            }
            Instant at = null;
            try {
                at = OffsetDateTime.parse(strip(f[i + 2])).toInstant();
            } catch (DateTimeParseException ignored) {
                // 时间异常不致命：映射行 committed_at 留空
            }
            commits.add(new GitCommit(sha, strip(f[i + 1]), null, at, strip(f[i + 3]), strip(f[i + 4])));
        }
        return commits;
    }

    /**
     * 组装 log 命令（包内可见供纯单测）：全零 oldRev（首次 push，无对应对象）退化为
     * 仅 newRev——等价列出 newRev 可达提交；否则取 {@code old..new} 区间。
     */
    static List<String> buildLogCommand(String gitDir, String oldRev, String newRev) {
        List<String> cmd = new ArrayList<>(List.of("git", "--git-dir", gitDir, "log", "--no-merges"));
        if (isAllZero(oldRev)) {
            cmd.add(newRev);
        } else {
            cmd.add(oldRev + ".." + newRev);
        }
        cmd.add(LOG_FORMAT);
        return cmd;
    }

    /**
     * NUL 分列解析分支：每 4 段为 name | sha | date | subject
     */
    static List<GitBranch> parseBranches(String stdout) {
        List<GitBranch> branches = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) {
            return branches;
        }
        String[] parts = stdout.split("\0", -1);
        for (int i = 0; i + 3 < parts.length; i += 4) {
            String name = strip(parts[i]);
            String sha = strip(parts[i + 1]);
            if (name.isEmpty() || sha.length() < 6) {
                continue;
            }
            Instant at = null;
            try {
                at = OffsetDateTime.parse(strip(parts[i + 2])).toInstant();
            } catch (DateTimeParseException ignored) {
            }
            String subj = strip(parts[i + 3]);
            branches.add(new GitBranch(name, sha, at, subj));
        }
        return branches;
    }

    /**
     * NUL 分列解析标签：每 4 段为 name | sha | date | message
     */
    static List<GitTag> parseTags(String stdout) {
        List<GitTag> tags = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) {
            return tags;
        }
        String[] parts = stdout.split("\0", -1);
        for (int i = 0; i + 3 < parts.length; i += 4) {
            String name = strip(parts[i]);
            String sha = strip(parts[i + 1]);
            if (name.isEmpty() || sha.length() < 6) {
                continue;
            }
            Instant at = null;
            try {
                at = OffsetDateTime.parse(strip(parts[i + 2])).toInstant();
            } catch (DateTimeParseException ignored) {
            }
            String msg = strip(parts[i + 3]);
            tags.add(new GitTag(name, sha, at, msg));
        }
        return tags;
    }

    /**
     * 解析 git ls-tree -l 输出，构建目录树。
     * 行格式：<mode> <type> <sha> <size-or-dash>\t<name>
     */
    static List<GitTreeItem> parseTree(String stdout, String parentPath) {
        List<GitTreeItem> dirs = new ArrayList<>();
        List<GitTreeItem> files = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) {
            return List.of();
        }
        String[] lines = stdout.split("\\r?\\n");
        for (String line : lines) {
            if (line.isBlank()) continue;
            int tabIdx = line.indexOf('\t');
            if (tabIdx < 0) continue;
            String left = line.substring(0, tabIdx).trim();
            String name = line.substring(tabIdx + 1);
            String[] tokens = left.split("\\s+");
            if (tokens.length < 4) continue;
            String mode = tokens[0];
            String type = tokens[1];
            String sha = tokens[2];
            String sizeStr = tokens[3];
            Long size = "-".equals(sizeStr) ? null : parseLongSafe(sizeStr);
            String itemPath = (parentPath == null || parentPath.isBlank())
                    ? name
                    : (parentPath.endsWith("/") ? parentPath + name : parentPath + "/" + name);
            GitTreeItem item = new GitTreeItem(mode, type, sha, size, name, itemPath);
            if (item.isDirectory()) {
                dirs.add(item);
            } else {
                files.add(item);
            }
        }
        dirs.sort(Comparator.comparing(GitTreeItem::name, String.CASE_INSENSITIVE_ORDER));
        files.sort(Comparator.comparing(GitTreeItem::name, String.CASE_INSENSITIVE_ORDER));
        List<GitTreeItem> res = new ArrayList<>(dirs.size() + files.size());
        res.addAll(dirs);
        res.addAll(files);
        return res;
    }

    // ---------- 小工具 ----------

    private static void validateRef(String ref) {
        if (ref == null || !ref.matches(REF_PATTERN) || ref.contains("..") || ref.startsWith("-") || ref.endsWith(".lock")) {
            throw new BusinessException(ErrorCode.PLT_4000, "非法 Git ref 名称");
        }
    }

    private static void validatePath(String path) {
        if (path != null && !path.isBlank()) {
            if (!path.matches(PATH_PATTERN) || path.contains("..") || path.startsWith("/") || path.startsWith("-")) {
                throw new BusinessException(ErrorCode.PLT_4000, "非法文件路径");
            }
        }
    }

    private static Long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void requireMatch(String pattern, String value, String name) {
        if (value == null || !value.matches(pattern)) {
            // 不回显原值，防注入探测写日志
            throw new BusinessException(ErrorCode.SRV_5030, "git 参数白名单校验失败: " + name);
        }
    }

    static boolean isAllZero(String rev) {
        for (int i = 0; i < rev.length(); i++) {
            if (rev.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("^[\\r\\n]+", "").replaceAll("[\\r\\n]+$", "");
    }

    private static String tail(String s) {
        String t = s.trim();
        return t.length() <= 400 ? t : t.substring(t.length() - 400);
    }

    private static Thread drain(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sink.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // 进程被强杀时的流关闭，忽略
            }
        }, "git-stderr-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 解析 Git Unified Diff 输出，构建文件级与行级差异结果。
     */
    static GitDiffResult parseUnifiedDiff(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return GitDiffResult.empty();
        }
        List<GitFileDiff> fileDiffs = new ArrayList<>();
        int totalAdditions = 0;
        int totalDeletions = 0;

        Pattern hunkPattern = Pattern.compile("^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@.*$");

        String[] rawLines = stdout.split("\\r?\\n");
        String currentPath = null;
        String status = "modified";
        int fileAdditions = 0;
        int fileDeletions = 0;
        List<GitDiffLine> currentLines = new ArrayList<>();
        int curOldNo = 0;
        int curNewNo = 0;

        for (String line : rawLines) {
            if (line.startsWith("diff --git ")) {
                if (currentPath != null) {
                    fileDiffs.add(new GitFileDiff(currentPath, status, fileAdditions, fileDeletions, currentLines));
                    totalAdditions += fileAdditions;
                    totalDeletions += fileDeletions;
                }
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    String bPath = parts[3].startsWith("b/") ? parts[3].substring(2) : parts[3];
                    currentPath = bPath;
                } else {
                    currentPath = "unknown";
                }
                status = "modified";
                fileAdditions = 0;
                fileDeletions = 0;
                currentLines = new ArrayList<>();
                curOldNo = 0;
                curNewNo = 0;
            } else if (line.startsWith("new file mode ")) {
                status = "added";
            } else if (line.startsWith("deleted file mode ")) {
                status = "removed";
            } else if (line.startsWith("--- ")) {
                if (line.startsWith("--- /dev/null")) {
                    status = "added";
                }
            } else if (line.startsWith("+++ ")) {
                if (line.startsWith("+++ /dev/null")) {
                    status = "removed";
                } else {
                    String bPath = line.substring(4).trim();
                    if (bPath.startsWith("b/")) bPath = bPath.substring(2);
                    currentPath = bPath;
                }
            } else if (line.startsWith("@@ ")) {
                Matcher m = hunkPattern.matcher(line);
                if (m.matches()) {
                    curOldNo = Integer.parseInt(m.group(1));
                    curNewNo = Integer.parseInt(m.group(3));
                }
            } else if (currentPath != null && !line.startsWith("index ")) {
                if (line.startsWith("+")) {
                    fileAdditions++;
                    currentLines.add(new GitDiffLine(null, curNewNo++, "add", line.substring(1)));
                } else if (line.startsWith("-")) {
                    fileDeletions++;
                    currentLines.add(new GitDiffLine(curOldNo++, null, "del", line.substring(1)));
                } else if (line.startsWith(" ")) {
                    currentLines.add(new GitDiffLine(curOldNo++, curNewNo++, "ctx", line.substring(1)));
                }
            }
        }
        if (currentPath != null) {
            fileDiffs.add(new GitFileDiff(currentPath, status, fileAdditions, fileDeletions, currentLines));
            totalAdditions += fileAdditions;
            totalDeletions += fileDeletions;
        }
        return new GitDiffResult(totalAdditions, totalDeletions, fileDiffs);
    }

    /**
     * 从 git merge-tree 或合并输出中解析冲突文件清单。
     */
    static List<String> parseConflictFiles(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        Set<String> files = new LinkedHashSet<>();
        Pattern p1 = Pattern.compile("CONFLICT \\([^)]+\\): Merge conflict in (.+)");
        Pattern pStage = Pattern.compile("^\\d+\\s+[0-9a-f]{40}\\s+[123]\\t(.+)$");

        for (String line : output.split("\\r?\\n")) {
            Matcher m1 = p1.matcher(line.trim());
            if (m1.find()) {
                files.add(m1.group(1).trim());
                continue;
            }
            Matcher mStage = pStage.matcher(line.trim());
            if (mStage.find()) {
                files.add(mStage.group(1).trim());
            }
        }
        return new ArrayList<>(files);
    }

    @Override
    public GitBlameResult blame(String repoKey, String revision, String filePath) {
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        validateRef(revision);
        validatePath(filePath);
        String gitDir = resolveGitDir(repoKey);

        long start = System.currentTimeMillis();
        List<String> cmd = List.of(
                "git", "--git-dir", gitDir,
                "blame", "--porcelain", revision, "--", filePath
        );
        String stdout = exec(cmd, repoKey);
        long durationMs = System.currentTimeMillis() - start;

        List<GitBlameLine> lines = parsePorcelainBlame(stdout);
        return new GitBlameResult(
                repoKey,
                revision,
                filePath,
                lines,
                lines.size(),
                durationMs,
                false
        );
    }

    /**
     * 解析 git blame --porcelain 机器格式输出。
     *
     * @param stdout git blame 命令行标准输出
     * @return 结构化的逐行 Blame 信息列表
     */
    static List<GitBlameLine> parsePorcelainBlame(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return List.of();
        }

        class CommitHeader {
            String sha;
            String authorName = "";
            String authorEmail = "";
            Instant committedAt = Instant.EPOCH;
            String summary = "";
        }

        Map<String, CommitHeader> commitMap = new HashMap<>();
        List<GitBlameLine> result = new ArrayList<>();

        Pattern headerPattern = Pattern.compile("^([0-9a-f]{40})\\s+(\\d+)\\s+(\\d+)(?:\\s+\\d+)?$");

        String currentSha = null;
        int currentFinalLine = 0;

        String[] rawLines = stdout.split("\\r?\\n");
        for (String line : rawLines) {
            if (line.startsWith("\t")) {
                // 源码行内容（以制表符 \t 开头）
                String lineContent = line.substring(1);
                CommitHeader header = (currentSha != null) ? commitMap.get(currentSha) : null;
                String author = (header != null && header.authorName != null && !header.authorName.isBlank()) ? header.authorName : "Unknown";
                String email = (header != null && header.authorEmail != null) ? header.authorEmail : "";
                Instant at = (header != null && header.committedAt != null) ? header.committedAt : Instant.EPOCH;
                String summary = (header != null && header.summary != null) ? header.summary : "";
                String shortSha = (currentSha != null && currentSha.length() >= 7) ? currentSha.substring(0, 7) : (currentSha != null ? currentSha : "");

                result.add(new GitBlameLine(
                        currentFinalLine,
                        currentSha,
                        shortSha,
                        author,
                        email,
                        at,
                        summary,
                        lineContent
                ));
                continue;
            }

            Matcher m = headerPattern.matcher(line.trim());
            if (m.matches()) {
                currentSha = m.group(1);
                currentFinalLine = Integer.parseInt(m.group(3));
                commitMap.putIfAbsent(currentSha, new CommitHeader());
                commitMap.get(currentSha).sha = currentSha;
                continue;
            }

            if (currentSha != null) {
                CommitHeader h = commitMap.get(currentSha);
                if (line.startsWith("author ")) {
                    h.authorName = line.substring(7).trim();
                } else if (line.startsWith("author-mail ")) {
                    String mail = line.substring(12).trim();
                    if (mail.startsWith("<") && mail.endsWith(">")) {
                        mail = mail.substring(1, mail.length() - 1);
                    }
                    h.authorEmail = mail;
                } else if (line.startsWith("author-time ")) {
                    try {
                        long sec = Long.parseLong(line.substring(12).trim());
                        h.committedAt = Instant.ofEpochSecond(sec);
                    } catch (Exception ignored) {}
                } else if (line.startsWith("summary ")) {
                    h.summary = line.substring(8).trim();
                }
            }
        }

        return result;
    }

    /**
     * 在指定版本下全文检索代码关键字（git grep -n -I）（V-21 / U4）。
     */
    @Override
    public GitSearchResult search(String repoKey, String ref, String query, String pathPattern, int maxResults) {
        String gitDir = resolveGitDir(repoKey);
        if (query == null || query.trim().length() < 2) {
            throw new BusinessException(ErrorCode.PLT_4000, "搜索关键词长度至少为 2 个字符");
        }
        String cleanQuery = query.trim();
        String targetRef = (ref != null && !ref.isBlank()) ? ref.trim() : "HEAD";
        validateRef(targetRef);

        int limit = (maxResults > 0 && maxResults <= 200) ? maxResults : 50;

        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("--git-dir");
        cmd.add(gitDir);
        cmd.add("grep");
        cmd.add("-n");  // 输出行号
        cmd.add("-I");  // 忽略二进制文件
        cmd.add("--max-count");
        cmd.add(String.valueOf(limit));
        cmd.add("-e");
        cmd.add(cleanQuery);
        cmd.add(targetRef);

        if (pathPattern != null && !pathPattern.isBlank()) {
            String cleanPath = pathPattern.trim().replace('\\', '/');
            validatePath(cleanPath);
            cmd.add("--");
            cmd.add(cleanPath);
        }

        long start = System.currentTimeMillis();
        ExecResult res = execFull(cmd, repoKey, null, false);
        long duration = System.currentTimeMillis() - start;

        // git grep 返回码：0=有匹配，1=未匹配到任何结果，>1=错误
        if (res.exitCode == 1 || (res.stdout != null && res.stdout.isBlank())) {
            return new GitSearchResult(repoKey, targetRef, cleanQuery, pathPattern, List.of(), 0, duration);
        }

        if (res.exitCode != 0) {
            log.warn("[git-grep] failed exit={} stderr={}", res.exitCode, res.stderr);
            throw new BusinessException(ErrorCode.PLT_4000, "代码全文检索执行失败: " + res.stderr);
        }

        List<GitSearchMatch> matches = parseGrepOutput(res.stdout, targetRef);
        return new GitSearchResult(repoKey, targetRef, cleanQuery, pathPattern, matches, matches.size(), duration);
    }

    /**
     * 解析 git grep -n 输出文本。
     * <p>格式为：{@code <targetRef>:<filePath>:<lineNo>:<lineContent>}</p>
     */
    static List<GitSearchMatch> parseGrepOutput(String stdout, String targetRef) {
        List<GitSearchMatch> matches = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) {
            return matches;
        }
        String prefix = (targetRef != null && !targetRef.isBlank()) ? targetRef + ":" : "";
        for (String line : stdout.split("\r?\n")) {
            if (line.isBlank()) continue;
            String content = line;
            if (!prefix.isEmpty() && content.startsWith(prefix)) {
                content = content.substring(prefix.length());
            }
            int c1 = content.indexOf(':');
            if (c1 <= 0) continue;
            int c2 = content.indexOf(':', c1 + 1);
            if (c2 <= c1) continue;

            String filePath = content.substring(0, c1);
            String lineNoStr = content.substring(c1 + 1, c2);
            String lineContent = content.substring(c2 + 1);

            try {
                int lineNo = Integer.parseInt(lineNoStr);
                matches.add(new GitSearchMatch(filePath, lineNo, lineContent));
            } catch (NumberFormatException ignored) {}
        }
        return matches;
    }

    // ---------- 建仓（⑥h 建仓批：POST /repos 运行期真实现） ----------

    /** 仓库名白名单：字母/数字/_/-，1~64（对齐 REPO_KEY_PATTERN 单段规则，杜绝路径逃逸） */
    private static final String REPO_NAME_PATTERN = "[a-zA-Z0-9_\\-]{1,64}";

    @Override
    public void initRepo(String name, String defaultBranch) {
        // ---- 白名单校验（先于任何 IO，防注入/路径逃逸）----
        requireMatch(REPO_NAME_PATTERN, name, "repoName");
        String cleanBranch = (defaultBranch == null || defaultBranch.isBlank()) ? "main" : defaultBranch.trim();
        validateRef(cleanBranch);
        String repoKey = name + "/" + name + ".git";
        String gitDir = resolveParentAndGitDir(name, repoKey);

        // 1) 初始化 bare 库（git init --bare：只建对象库与引用目录，无工作区）
        execFull(buildInitCommand(gitDir), repoKey, null, true);

        // 2) HEAD 符号引用指向默认分支（空库 HEAD 为 unborn 引用，symbolic-ref 直接生效；
        //    不用 --initial-branch 以兼容 <2.28 的 git）
        execFull(buildHeadRefCommand(gitDir, cleanBranch), repoKey, null, true);
    }

    /** `git init --bare <gitDir>`（包内可见供纯单测） */
    static List<String> buildInitCommand(String gitDir) {
        return List.of("git", "init", "--bare", gitDir);
    }

    /** `git --git-dir <gitDir> symbolic-ref HEAD refs/heads/<branch>`（包内可见供纯单测） */
    static List<String> buildHeadRefCommand(String gitDir, String branch) {
        return List.of("git", "--git-dir", gitDir, "symbolic-ref", "HEAD", "refs/heads/" + branch);
    }

    /**
     * 解析新库落盘目录 <GIT_ROOT>/<name>/<name>.git 并确保父目录存在。
     * 与 {@link #resolveGitDir} 不同：新库尚不存在，不能复用其「目录必须已在」预判；
     * 白名单已禁「..」「\」，此处再做 startsWith 纵深防御。
     */
    private String resolveParentAndGitDir(String name, String repoKey) {
        if (gitRoot.isEmpty()) {
            throw new BusinessException(ErrorCode.SRV_5030, "TEAMONE_GIT_ROOT 未配置，Git 仓库目录不可用");
        }
        Path root = Path.of(gitRoot).toAbsolutePath().normalize();
        Path dir = root.resolve(repoKey).normalize();
        if (!dir.startsWith(root)) {
            throw new BusinessException(ErrorCode.SRV_5030, "Git 仓库目录越界: " + repoKey);
        }
        if (Files.exists(dir)) {
            // 数据查重（调用方）漏网时的文件系统兜底：绝不在既有仓库上重复 init
            throw new BusinessException(ErrorCode.PLT_4091, "仓库目录已存在，疑似同名仓库: " + name);
        }
        try {
            Files.createDirectories(dir.getParent());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SRV_5030, "创建仓库目录失败: " + e.getMessage());
        }
        return dir.toString();
    }

    // ---------- cherry-pick（部分合并，分支治理批） ----------

    /** commitSha 白名单：完整 40 位小写 hex（对齐 git 对象名，杜绝短 sha/大写/注入歧义） */
    private static final String SHA40_PATTERN = "[0-9a-f]{40}";

    /** cherry-pick 超时（秒）：worktree 检出含全量工作区落盘，比常规 git 读命令重一个量级 */
    private static final long CHERRY_PICK_TIMEOUT_SECONDS = 60;

    @Override
    public String cherryPick(String repoKey, String commitSha, String targetBranch) {
        // ---- 白名单校验（先于任何 IO，防注入探测）----
        requireMatch(REPO_KEY_PATTERN, repoKey, "repoKey");
        if (commitSha == null || !commitSha.matches(SHA40_PATTERN)) {
            throw new BusinessException(ErrorCode.PLT_4000, "commitSha 须为完整 40 位小写十六进制 SHA");
        }
        if (targetBranch == null || targetBranch.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "目标分支不能为空");
        }
        String cleanBranch = targetBranch.trim();
        validateRef(cleanBranch);
        String gitDir = resolveGitDir(repoKey);

        // ---- 目标分支存在性预判（404）+ 记录旧值供 CAS 快进 ----
        ExecResult verifyBranch = execFull(
                List.of("git", "--git-dir", gitDir, "rev-parse", "-q", "--verify", "refs/heads/" + cleanBranch),
                repoKey, null, false);
        if (verifyBranch.exitCode() != 0) {
            throw new BusinessException(ErrorCode.PLT_4040, "目标分支不存在: " + cleanBranch);
        }
        String oldHead = verifyBranch.stdout().trim();

        // ---- 源提交存在性预判（404）----
        ExecResult verifyCommit = execFull(
                List.of("git", "--git-dir", gitDir, "rev-parse", "-q", "--verify", commitSha + "^{commit}"),
                repoKey, null, false);
        if (verifyCommit.exitCode() != 0) {
            throw new BusinessException(ErrorCode.PLT_4040, "提交不存在: " + commitSha);
        }

        // 临时 worktree 落系统临时目录（容器本地盘）：GIT_ROOT 可能挂在网络/宿主映射盘上，
        // 检出全量工作区会慢到触发超时（实测 drvfs 上 worktree add >5s）
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("teamone-cp-");

            // 1) 挂临时 worktree（--detach）：detach 不占分支检出位，规避「分支已被其他
            //    worktree 检出」拒绝（服务端流水线 worktree 可能长期检出同分支）
            execFull(buildWorktreeAddCommand(gitDir, workDir.toString(), cleanBranch),
                    repoKey, null, true, CHERRY_PICK_TIMEOUT_SECONDS);

            // 2) 在 worktree 内 cherry-pick（-x 留痕 "cherry picked from"）。
            //    必须以 worktree 自身 HEAD 为基（git -C <workDir>）——裸库 HEAD 指向其默认
            //    分支，若按 --git-dir <bare> --work-tree <tmp> 直调会拣到默认分支上，故经
            //    worktree 的 .git 文件定位其私有 gitdir，HEAD 语义才正确。
            ExecResult pick = execFull(buildCherryPickCommand(workDir.toString(), commitSha),
                    repoKey, cherryPickIdentityEnv(), false, CHERRY_PICK_TIMEOUT_SECONDS);
            if (pick.exitCode() != 0) {
                String output = pick.stdout() + "\n" + pick.stderr();
                List<String> conflicts = parseConflictFiles(output);
                if (!conflicts.isEmpty()) {
                    throw new BusinessException(ErrorCode.ENG_4251,
                            "存在冲突，需手工处理。冲突文件: " + conflicts);
                }
                throw new BusinessException(ErrorCode.ENG_4251,
                        "cherry-pick 失败（该提交可能已被目标分支包含，或为空提交）");
            }

            // 3) 新提交 SHA（worktree 的 detached HEAD）
            String newHead = exec(buildHeadCommand(workDir.toString()), repoKey).trim();

            // 4) CAS 快进目标分支引用（update-ref 带旧值：并发 push 移动分支时失败防覆盖）。
            //    失败=并发竞争，非环境故障：显式 409 提示重试，避免孤儿提交被误报为「仓库不可用」
            ExecResult upd = execFull(buildUpdateRefCommand(gitDir, cleanBranch, newHead, oldHead),
                    repoKey, null, false);
            if (upd.exitCode() != 0) {
                throw new BusinessException(ErrorCode.PLT_4091,
                        "目标分支 " + cleanBranch + " 刚被并发更新，请重试摘取");
            }
            return newHead;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SRV_5030, "创建 cherry-pick 临时工作目录失败");
        } finally {
            cleanupWorktree(gitDir, workDir, repoKey);
        }
    }

    /** `git --git-dir <bare> worktree add --detach <tmpDir> <branch>`（包内可见供纯单测） */
    static List<String> buildWorktreeAddCommand(String gitDir, String tmpDir, String branch) {
        return List.of("git", "--git-dir", gitDir, "worktree", "add", "--detach", tmpDir, branch);
    }

    /** `git -c safe.directory=<tmpDir> -C <tmpDir> cherry-pick -x <sha>`（包内可见供纯单测）。
     *  safe.directory 按目录精确放行：容器内 /tmp 下挂出的 worktree 会触发 dubious ownership
     *  拦截（uid 映射差异），逐命令放行避免改全局配置。 */
    static List<String> buildCherryPickCommand(String tmpDir, String commitSha) {
        return List.of("git", "-c", "safe.directory=" + tmpDir, "-C", tmpDir,
                "cherry-pick", "-x", commitSha);
    }

    /** `git -c safe.directory=<tmpDir> -C <tmpDir> rev-parse HEAD`（包内可见供纯单测；worktree 同样受 dubious ownership 拦截） */
    static List<String> buildHeadCommand(String tmpDir) {
        return List.of("git", "-c", "safe.directory=" + tmpDir, "-C", tmpDir, "rev-parse", "HEAD");
    }

    /** `git --git-dir <bare> update-ref refs/heads/<branch> <new> <old>`（CAS，包内可见供纯单测） */
    static List<String> buildUpdateRefCommand(String gitDir, String branch, String newSha, String oldSha) {
        return List.of("git", "--git-dir", gitDir, "update-ref",
                "refs/heads/" + branch, newSha, oldSha);
    }

    /** cherry-pick 需提交人身份（裸库无 user.name/email 配置会拒提交）；作者身份由 -x 保留原提交 */
    private Map<String, String> cherryPickIdentityEnv() {
        return Map.of(
                "GIT_COMMITTER_NAME", "TeamOne",
                "GIT_COMMITTER_EMAIL", "teamone@teamone.cn",
                "GIT_AUTHOR_NAME", "TeamOne",
                "GIT_AUTHOR_EMAIL", "teamone@teamone.cn"
        );
    }

    /**
     * worktree 清理（finally 兜底，绝不抛出）：摘除 worktree → 直接删临时目录 → prune 元数据。
     */
    private void cleanupWorktree(String gitDir, Path workDir, String repoKey) {
        if (workDir == null) {
            return;
        }
        try {
            execFull(List.of("git", "--git-dir", gitDir, "worktree", "remove", "--force", workDir.toString()),
                    repoKey, null, false);
        } catch (Exception e) {
            log.debug("[git] worktree remove 失败（将由目录删除兜底）: {}", e.getMessage());
        }
        try (var walk = Files.walk(workDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception e) {
            log.warn("[git] cherry-pick 临时目录清理失败: {}（{}）", workDir, e.getMessage());
        }
        try {
            execFull(List.of("git", "--git-dir", gitDir, "worktree", "prune"), repoKey, null, false);
        } catch (Exception ignored) {
            // 元数据 prune 为尽力而为
        }
    }
}

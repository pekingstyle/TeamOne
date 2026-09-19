package cn.teamone.eng.infra.git;

import cn.teamone.shared.api.BusinessException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * cherry-pick（部分合并）测试：
 * <ul>
 *   <li>纯单测（不依赖真实 git）：worktree 方案四段命令组装的形态锁定 + 参数白名单；</li>
 *   <li>{@code @Disabled} 真库集成用例：完整走「临时 worktree 挂载 → cherry-pick -x →
 *       CAS 快进分支 → 清理」链路，逻辑正确性交由总监在本机 git 环境一键验证
 *       （去掉 @Disabled 或手动执行即可）。</li>
 * </ul>
 *
 * @author Ivan Yang, 2026-09-14
 */
class GitCommandPortCherryPickTest {

    private static final String SHA_SRC = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    // ---------- 命令组装形态（worktree 方案的正确性锚点） ----------

    @Test
    void buildWorktreeAddCommand_detachedToTargetBranch() {
        // --detach：不占分支检出位，规避「分支已被其他 worktree 检出」拒绝
        assertEquals(List.of("git", "--git-dir", "D:/bare/t.git", "worktree", "add", "--detach",
                "D:/tmp/cp-1", "release/1.2"),
                GitCommandPort.buildWorktreeAddCommand("D:/bare/t.git", "D:/tmp/cp-1", "release/1.2"));
    }

    @Test
    void buildCherryPickCommand_runsInsideWorktree_withX() {
        // 必须以 worktree 自身 HEAD 为基（git -C <tmp>）：裸库 HEAD 指向默认分支，
        // 若按 --git-dir <bare> --work-tree <tmp> 直调会把提交拣到默认分支上；
        // safe.directory 按目录放行：容器 /tmp 下 worktree 会触发 dubious ownership 拦截
        assertEquals(List.of("git", "-c", "safe.directory=D:/tmp/cp-1", "-C", "D:/tmp/cp-1",
                "cherry-pick", "-x", SHA_SRC),
                GitCommandPort.buildCherryPickCommand("D:/tmp/cp-1", SHA_SRC));
    }

    @Test
    void buildHeadAndCasUpdateRefCommands() {
        String SHA_NEW = "b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0a1";
        assertEquals(List.of("git", "-c", "safe.directory=D:/tmp/cp-1", "-C", "D:/tmp/cp-1",
                "rev-parse", "HEAD"),
                GitCommandPort.buildHeadCommand("D:/tmp/cp-1"));
        // CAS：update-ref 带旧值，并发 push 移动分支时失败防覆盖
        assertEquals(List.of("git", "--git-dir", "D:/bare/t.git", "update-ref",
                "refs/heads/release/1.2", SHA_NEW, SHA_SRC),
                GitCommandPort.buildUpdateRefCommand("D:/bare/t.git", "release/1.2", SHA_NEW, SHA_SRC));
    }

    // ---------- 参数白名单（先于任何 IO/进程） ----------

    @Test
    void cherryPick_whitelist_rejectsBeforeAnyGitCall() {
        GitCommandPort port = new GitCommandPort(""); // root 空：白名单先于 root/目录检查
        // commitSha：非 40 位 hex / 大写 / 短 sha 均拒
        assertTrue(assertThrows(BusinessException.class,
                () -> port.cherryPick("t/w.git", SHA_SRC.substring(0, 7), "release/1.2"))
                .getMessage().contains("commitSha"));
        assertTrue(assertThrows(BusinessException.class,
                () -> port.cherryPick("t/w.git", SHA_SRC.toUpperCase(), "release/1.2"))
                .getMessage().contains("commitSha"));
        // repoKey：无 .git 后缀拒
        assertTrue(assertThrows(BusinessException.class,
                () -> port.cherryPick("teamone/web", SHA_SRC, "release/1.2"))
                .getMessage().contains("repoKey"));
        // 目标分支：空 / 非法 ref（前导 - / .. 穿越）拒
        assertTrue(assertThrows(BusinessException.class,
                () -> port.cherryPick("t/w.git", SHA_SRC, "  "))
                .getMessage().contains("目标分支"));
        assertTrue(assertThrows(BusinessException.class,
                () -> port.cherryPick("t/w.git", SHA_SRC, "-oProxyCommand=x"))
                .getMessage().contains("Git ref"));
        assertTrue(assertThrows(BusinessException.class,
                () -> port.cherryPick("t/w.git", SHA_SRC, "a..b"))
                .getMessage().contains("Git ref"));
    }

    // ---------- 真库集成验证（@Disabled：总监在本机 git 环境执行） ----------

    /**
     * 完整链路验证：bare 仓 + work 种仓 → main 新提交 cherry-pick 到 release/1.2 →
     * 分支引用 CAS 快进到新提交 → worktree/临时目录零残留；再验证冲突路径 422。
     */
    @Test
    @Disabled("依赖本机 git 与真实仓库：worktree 方案正确性由总监集成时真库验证（本用例即验证脚本）")
    void cherryPick_realGit_endToEnd(@TempDir Path gitRoot) throws Exception {
        ProcessAssert git = new ProcessAssert();
        assumeGitAvailable(git);

        Path bare = gitRoot.resolve("teamone").resolve("t1.git");
        Files.createDirectories(bare.getParent());
        git.run(gitRoot, "git", "init", "--bare", "-b", "main", bare.toString());

        Path work = gitRoot.resolve("seed-work");
        git.run(gitRoot, "git", "init", "-b", "main", work.toString());
        git.run(work, "git", "config", "user.email", "seed@teamone.cn");
        git.run(work, "git", "config", "user.name", "Seed");
        Files.writeString(work.resolve("a.txt"), "hello\n");
        git.run(work, "git", "add", ".");
        git.run(work, "git", "commit", "-m", "init");
        git.run(work, "git", "remote", "add", "origin", bare.toString());
        git.run(work, "git", "push", "origin", "main");
        git.run(work, "git", "branch", "release/1.2");
        git.run(work, "git", "push", "origin", "release/1.2");

        // main 上的新提交（待拣选）
        Files.writeString(work.resolve("b.txt"), "picked\n");
        git.run(work, "git", "add", ".");
        git.run(work, "git", "commit", "-m", "feat: add b for pick");
        String srcSha = git.out(work, "git", "rev-parse", "HEAD").trim();

        GitCommandPort port = new GitCommandPort(gitRoot.toString());
        String newSha = port.cherryPick("teamone/t1.git", srcSha, "release/1.2");

        // 新提交非源提交、分支引用已快进、提交信息带来源留痕
        assertNotEquals(srcSha, newSha);
        assertEquals(newSha, git.out(gitRoot, "git", "--git-dir", bare.toString(),
                "rev-parse", "refs/heads/release/1.2").trim());
        assertTrue(git.out(gitRoot, "git", "--git-dir", bare.toString(), "log", "-1",
                "--format=%B", "refs/heads/release/1.2").contains("feat: add b for pick"));
        assertTrue(git.out(gitRoot, "git", "--git-dir", bare.toString(), "log", "-1",
                "--format=%B", "refs/heads/release/1.2").contains("cherry picked from commit " + srcSha));
        // main 未被误动（对照：拣选只落在目标分支）
        assertEquals(srcSha, git.out(gitRoot, "git", "--git-dir", bare.toString(),
                "rev-parse", "refs/heads/main").trim());
        // worktree 元数据与临时目录零残留（worktree list 不应残留 cp-* 临时项）
        String wtList = git.out(gitRoot, "git", "--git-dir", bare.toString(), "worktree", "list");
        assertFalse(wtList.contains("cp-"), "应无残留临时 worktree: " + wtList);
        // 临时 worktree 落系统临时目录（容器本地盘），断言 teamone-cp-* 前缀目录零残留
        try (var s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))
                .filter(p -> p.getFileName().toString().startsWith("teamone-cp-"))) {
            assertEquals(0, s.count(), "系统临时目录应无 teamone-cp-* 残留");
        }

        // 冲突路径：在 release/1.2 上改同一文件后拣选另一改动 → 422 + 冲突清单
        git.run(work, "git", "checkout", "release/1.2");
        Files.writeString(work.resolve("a.txt"), "conflicting\n");
        git.run(work, "git", "commit", "-am", "conflict on release");
        git.run(work, "git", "push", "origin", "release/1.2");
        git.run(work, "git", "checkout", "main");
        Files.writeString(work.resolve("a.txt"), "different change\n");
        git.run(work, "git", "commit", "-am", "change a on main");
        String conflictSha = git.out(work, "git", "rev-parse", "HEAD").trim();

        BusinessException e = assertThrows(BusinessException.class,
                () -> port.cherryPick("teamone/t1.git", conflictSha, "release/1.2"));
        assertEquals(422, e.errorCode().httpStatus());
        assertTrue(e.getMessage().contains("存在冲突"));
    }

    private static void assumeGitAvailable(ProcessAssert git) {
        try {
            git.run(Path.of("."), "git", "--version");
        } catch (Exception e) {
            throw new IllegalStateException("本机 git 不可用，跳过真库验证", e);
        }
    }

    /** 测试内 git 直调小工具（仅 @Disabled 用例使用；进程数组直传、限时强杀） */
    private static final class ProcessAssert {
        void run(Path cwd, String... cmd) {
            outOr(cwd, null, cmd);
        }

        String out(Path cwd, String... cmd) {
            return outOr(cwd, null, cmd);
        }

        String outOr(Path cwd, String def, String... cmd) {
            try {
                Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).start();
                if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IllegalStateException("测试 git 命令超时: " + String.join(" ", cmd));
                }
                String stdout = new String(p.getInputStream().readAllBytes()).trim();
                String stderr = new String(p.getErrorStream().readAllBytes()).trim();
                if (p.exitValue() != 0) {
                    String msg = "测试 git 命令失败(" + p.exitValue() + "): " + String.join(" ", cmd)
                            + " :: " + stderr;
                    if (def != null) {
                        return def;
                    }
                    throw new IllegalStateException(msg);
                }
                return stdout.isEmpty() ? stderr : stdout;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("测试 git 命令异常: " + String.join(" ", cmd), e);
            }
        }
    }
}

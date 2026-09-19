package cn.teamone.eng.infra.git;

import cn.teamone.shared.api.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GitCommandPort 纯逻辑单测（无 Spring、不调真实 git）：NUL 分列解析 / 参数白名单 / 全零特判。
 *
 * @author Ivan Yang, 2026-09-12
 */
class GitCommandPortTest {

    private static final String SHA1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String SHA2 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b1";
    private static final String FMT = "%x00%H%x00%an%x00%aI%x00%s%x00%b";
    // NUL 用常量拼接：字符串字面量里 "\0" 后跟数字会被当八进制转义吞掉（如 "\02"→U+0002），
    // 这是本测试第一版失败的原因——解析器本身无辜
    private static final String NUL = String.valueOf((char) 0);

    @Test
    void parseLog_nulSplit_twoCommits() {
        // 真实 git 输出形态：每条记录以 %x00 开头，记录间仅换行——下一条 sha 不得并入上一条 body
        String out = NUL + SHA1 + NUL + "Dev One" + NUL + "2026-09-11T21:10:42+08:00" + NUL
                + "fix: refs #D-88" + NUL + "body line\nmore\n"
                + NUL + SHA2 + NUL + "Dev Two" + NUL + "2026-09-11T21:11:00Z" + NUL + "subj2" + NUL + "\n";
        List<GitCommit> commits = GitCommandPort.parseLog(out);
        assertEquals(2, commits.size());
        GitCommit c1 = commits.get(0);
        assertEquals(SHA1, c1.sha());
        assertEquals("Dev One", c1.authorName());
        assertEquals(Instant.parse("2026-09-11T13:10:42Z"), c1.committedAt()); // +08:00 归一 UTC
        assertEquals("fix: refs #D-88", c1.subject());
        assertEquals("body line\nmore", c1.body()); // 段内换行保留、首尾换行剥离
        assertEquals(SHA2, commits.get(1).sha());
        assertEquals("subj2", commits.get(1).subject());
        assertEquals("", commits.get(1).body());
    }

    @Test
    void parseLog_toleratesMalformedTailAndBadRows() {
        // 截断记录（不足 5 段）整条丢弃，不致命
        assertTrue(GitCommandPort.parseLog(NUL + SHA1 + NUL + "Dev" + NUL + "2026-09-11T21:10:42Z" + NUL + "s").isEmpty());
        // 非法 sha 行被跳过
        assertTrue(GitCommandPort.parseLog(NUL + "zzNotSha" + NUL + "x" + NUL + "y" + NUL + "z" + NUL + "w").isEmpty());
        assertTrue(GitCommandPort.parseLog("").isEmpty());
        // 时间解析失败不致命：committedAt 置 null
        List<GitCommit> badDate = GitCommandPort.parseLog(NUL + SHA1 + NUL + "Dev" + NUL + "not-a-date" + NUL + "s" + NUL);
        assertEquals(1, badDate.size());
        assertNull(badDate.get(0).committedAt());
    }

    @Test
    void whitelist_rejectsTraversalAndBadRev_beforeAnyGitCall() {
        GitCommandPort port = new GitCommandPort(""); // root 空：白名单先于 root 检查
        String ok = SHA1;
        // 路径穿越 repoKey 拒
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> port.logRange("../etc/passwd.git", ok, ok));
        assertTrue(e1.getMessage().contains("repoKey"));
        // 无 .git 后缀拒
        assertTrue(assertThrows(BusinessException.class,
                () -> port.logRange("teamone/web", ok, ok)).getMessage().contains("repoKey"));
        // 非法 rev（非 hex / 超长 / 大写）拒
        assertTrue(assertThrows(BusinessException.class,
                () -> port.logRange("t/w.git", "ZZZZ" + ok.substring(4), ok)).getMessage().contains("oldRev"));
        assertTrue(assertThrows(BusinessException.class,
                () -> port.logRange("t/w.git", ok, "0".repeat(41))).getMessage().contains("newRev"));
        assertTrue(assertThrows(BusinessException.class,
                () -> port.logRange("t/w.git", ok, ok.toUpperCase())).getMessage().contains("newRev"));
    }

    @Test
    void buildLogCommand_allZeroOldRev_degradesToNewRevOnly() {
        String zeros = "0".repeat(40);
        List<String> first = GitCommandPort.buildLogCommand("D", zeros, SHA1);
        assertEquals(List.of("git", "--git-dir", "D", "log", "--no-merges", SHA1,
                "--format=" + FMT), first);
        List<String> range = GitCommandPort.buildLogCommand("D", SHA2, SHA1);
        assertEquals(List.of("git", "--git-dir", "D", "log", "--no-merges", SHA2 + ".." + SHA1,
                "--format=" + FMT), range);
    }

    // ---------- 建仓（⑥h 建仓批） ----------

    @Test
    void buildInitAndHeadRefCommands_expectedShape() {
        // git init --bare <dir> + symbolic-ref HEAD → 默认分支（不用 --initial-branch 以兼容 <2.28）
        assertEquals(List.of("git", "init", "--bare", "/data/x/x.git"),
                GitCommandPort.buildInitCommand("/data/x/x.git"));
        assertEquals(List.of("git", "--git-dir", "/data/x/x.git", "symbolic-ref",
                "HEAD", "refs/heads/main"), GitCommandPort.buildHeadRefCommand("/data/x/x.git", "main"));
        assertEquals(List.of("git", "--git-dir", "D", "symbolic-ref",
                "HEAD", "refs/heads/release/1.0"), GitCommandPort.buildHeadRefCommand("D", "release/1.0"));
    }

    @Test
    void initRepo_rejectsBadNameOrBranch_beforeAnyIo() {
        GitCommandPort port = new GitCommandPort(""); // root 空：白名单先于 root 检查
        // 路径逃逸 / 含点段 / 超长 / 白名单外字符，一律 4xx 且不落盘
        assertTrue(assertThrows(BusinessException.class, () -> port.initRepo("../evil", "main"))
                .getMessage().contains("repoName"));
        assertTrue(assertThrows(BusinessException.class, () -> port.initRepo("a/b", "main"))
                .getMessage().contains("repoName"));
        assertTrue(assertThrows(BusinessException.class, () -> port.initRepo("a".repeat(65), "main"))
                .getMessage().contains("repoName"));
        assertTrue(assertThrows(BusinessException.class, () -> port.initRepo("ok name", "main"))
                .getMessage().contains("repoName"));
        // 默认分支非法（.. 上跳走 ref 白名单拒绝）；随后验证合法参数在 root 未配置时才失败
        assertTrue(assertThrows(BusinessException.class, () -> port.initRepo("ok", "..evil"))
                .getMessage().contains("非法 Git ref"));
        assertTrue(assertThrows(BusinessException.class, () -> port.initRepo("ok", "main"))
                .getMessage().contains("TEAMONE_GIT_ROOT"));
    }

    @Test
    void parseBranches_validOutput_parsedCorrectly() {
        String out = "main" + NUL + SHA1 + NUL + "2026-09-12T10:00:00+08:00" + NUL + "feat: init" + NUL
                + "dev" + NUL + SHA2 + NUL + "2026-09-12T11:00:00Z" + NUL + "fix: bug" + NUL;
        List<GitBranch> branches = GitCommandPort.parseBranches(out);
        assertEquals(2, branches.size());
        GitBranch b1 = branches.get(0);
        assertEquals("main", b1.name());
        assertEquals(SHA1, b1.commitSha());
        assertEquals(Instant.parse("2026-09-12T02:00:00Z"), b1.committedAt());
        assertEquals("feat: init", b1.commitSubject());

        GitBranch b2 = branches.get(1);
        assertEquals("dev", b2.name());
        assertEquals(SHA2, b2.commitSha());
        assertEquals("fix: bug", b2.commitSubject());
    }

    @Test
    void parseTags_validOutput_parsedCorrectly() {
        String out = "v1.0.0" + NUL + SHA1 + NUL + "2026-09-12T10:00:00Z" + NUL + "Release v1.0.0" + NUL;
        List<GitTag> tags = GitCommandPort.parseTags(out);
        assertEquals(1, tags.size());
        assertEquals("v1.0.0", tags.get(0).name());
        assertEquals(SHA1, tags.get(0).commitSha());
        assertEquals("Release v1.0.0", tags.get(0).message());
    }

    @Test
    void parseTree_dirsFirst_thenFilesAlphabetical() {
        String out = """
                100644 blob 5cb3e3ec19f36486b812ca0109970284a71dd2d3     108\t.gitignore
                100644 blob b06030d8fc9ea2b9172d9f7f683bb6c192fa0c21    5954\tREADME.md
                040000 tree c0825c73562884d2cbd1ae9ee8999df735e9db97       -\tweb
                040000 tree f7e2f75e58e388f413d3b6d5ae912e32ede70608       -\tserver
                """;
        List<GitTreeItem> items = GitCommandPort.parseTree(out, "");
        assertEquals(4, items.size());
        // 目录排前，按字母序
        assertTrue(items.get(0).isDirectory());
        assertEquals("server", items.get(0).name());
        assertEquals("server", items.get(0).path());
        assertNull(items.get(0).size());

        assertTrue(items.get(1).isDirectory());
        assertEquals("web", items.get(1).name());

        // 文件排后，按字母序
        assertFalse(items.get(2).isDirectory());
        assertEquals(".gitignore", items.get(2).name());
        assertEquals(108L, items.get(2).size());

        assertFalse(items.get(3).isDirectory());
        assertEquals("README.md", items.get(3).name());
        assertEquals(5954L, items.get(3).size());
    }

    @Test
    void parseTree_withParentPath_prependsParent() {
        String out = "100644 blob f7c44a46784585bec92a16f0e437718b36b10d60     734\tDockerfile\n";
        List<GitTreeItem> items = GitCommandPort.parseTree(out, "server");
        assertEquals(1, items.size());
        assertEquals("Dockerfile", items.get(0).name());
        assertEquals("server/Dockerfile", items.get(0).path());
    }

    @Test
    void parseUnifiedDiff_variousFileStatuses_parsedCorrectly() {
        String diffOut = """
                diff --git a/README.md b/README.md
                index 1234567..89abcdef 100644
                --- a/README.md
                +++ b/README.md
                @@ -1,3 +1,4 @@
                 # TeamOne
                -Old Intro
                +New Intro
                +Extra Line
                 End
                diff --git a/new.txt b/new.txt
                new file mode 100644
                --- /dev/null
                +++ b/new.txt
                @@ -0,0 +1,2 @@
                +hello
                +world
                diff --git a/deleted.txt b/deleted.txt
                deleted file mode 100644
                --- a/deleted.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -bye
                """;
        GitDiffResult res = GitCommandPort.parseUnifiedDiff(diffOut);
        assertEquals(3, res.files().size());
        assertEquals(4, res.totalAdditions()); // 2 + 2
        assertEquals(2, res.totalDeletions()); // 1 + 1

        GitFileDiff f1 = res.files().get(0);
        assertEquals("README.md", f1.path());
        assertEquals("modified", f1.status());
        assertEquals(2, f1.additions());
        assertEquals(1, f1.deletions());
        assertEquals(5, f1.lines().size());

        GitFileDiff f2 = res.files().get(1);
        assertEquals("new.txt", f2.path());
        assertEquals("added", f2.status());
        assertEquals(2, f2.additions());
        assertEquals(0, f2.deletions());

        GitFileDiff f3 = res.files().get(2);
        assertEquals("deleted.txt", f3.path());
        assertEquals("removed", f3.status());
        assertEquals(0, f3.additions());
        assertEquals(1, f3.deletions());
    }

    @Test
    void parseConflictFiles_deduplicatesAndExtractsPaths() {
        String conflictOut = """
                c36dba782cd02cecf4255c91352bf65e82a6591b
                100644 ce013625030ba8dba906f756967f9e9ca394464a 1\tsrc/A.java
                100644 94954abda49de8615a048f8d2e64b5de848e27a1 2\tsrc/A.java
                100644 fb984da2e9cb47d1e43ace8f381e2b7788f09803 3\tsrc/A.java

                Auto-merging src/A.java
                CONFLICT (content): Merge conflict in src/A.java
                Auto-merging config/app.yml
                CONFLICT (content): Merge conflict in config/app.yml
                """;
        List<String> files = GitCommandPort.parseConflictFiles(conflictOut);
        assertEquals(2, files.size());
        assertTrue(files.contains("src/A.java"));
        assertTrue(files.contains("config/app.yml"));
    }

    @Test
    void createTag_invalidTagName_throwsException() {
        GitCommandPort port = new GitCommandPort("");
        assertThrows(BusinessException.class, () ->
                port.createTag("teamone/teamone.git", "../evil", "main", "msg", "Author", "author@test.com"));
        assertThrows(BusinessException.class, () ->
                port.createTag("teamone/teamone.git", "tag with spaces", "main", "msg", "Author", "author@test.com"));
        assertThrows(BusinessException.class, () ->
                port.createTag("teamone/teamone.git", "", "main", "msg", "Author", "author@test.com"));
    }

    @Test
    void deleteTag_invalidTagName_throwsException() {
        GitCommandPort port = new GitCommandPort("");
        assertThrows(BusinessException.class, () ->
                port.deleteTag("teamone/teamone.git", "../evil"));
        assertThrows(BusinessException.class, () ->
                port.deleteTag("teamone/teamone.git", ""));
    }

    @Test
    void parsePorcelainBlame_validOutput_parsedCorrectly() {
        String stdout = """
                a0ff5c5f7e5d199efb07721b0cb93776c31f1b43 1 1 2
                author Alice
                author-mail <alice@teamone.cn>
                author-time 1760000000
                author-tz +0000
                committer Alice
                committer-mail <alice@teamone.cn>
                committer-time 1760000000
                committer-tz +0000
                summary initial commit
                filename App.java
                \tpublic class App {
                a0ff5c5f7e5d199efb07721b0cb93776c31f1b43 2 2
                \t    // comment
                b1bb5c5f7e5d199efb07721b0cb93776c31f1b44 3 3 1
                author Bob
                author-mail <bob@teamone.cn>
                author-time 1760000100
                author-tz +0000
                summary update logic
                filename App.java
                \t    int x = 42;
                """;
        List<GitBlameLine> lines = GitCommandPort.parsePorcelainBlame(stdout);
        assertEquals(3, lines.size());

        GitBlameLine l1 = lines.get(0);
        assertEquals(1, l1.lineNo());
        assertEquals("a0ff5c5f7e5d199efb07721b0cb93776c31f1b43", l1.commitSha());
        assertEquals("a0ff5c5", l1.commitShort());
        assertEquals("Alice", l1.authorName());
        assertEquals("alice@teamone.cn", l1.authorEmail());
        assertEquals("initial commit", l1.summary());
        assertEquals("public class App {", l1.lineContent());

        GitBlameLine l2 = lines.get(1);
        assertEquals(2, l2.lineNo());
        assertEquals("a0ff5c5f7e5d199efb07721b0cb93776c31f1b43", l2.commitSha());
        assertEquals("Alice", l2.authorName());
        assertEquals("    // comment", l2.lineContent());

        GitBlameLine l3 = lines.get(2);
        assertEquals(3, l3.lineNo());
        assertEquals("b1bb5c5f7e5d199efb07721b0cb93776c31f1b44", l3.commitSha());
        assertEquals("Bob", l3.authorName());
        assertEquals("bob@teamone.cn", l3.authorEmail());
        assertEquals("update logic", l3.summary());
        assertEquals("    int x = 42;", l3.lineContent());
    }

    @Test
    void parsePorcelainBlame_emptyOrBlank_returnsEmptyList() {
        assertTrue(GitCommandPort.parsePorcelainBlame("").isEmpty());
        assertTrue(GitCommandPort.parsePorcelainBlame(null).isEmpty());
        assertTrue(GitCommandPort.parsePorcelainBlame("   \n   ").isEmpty());
    }

    @Test
    void parseGrepOutput_validOutput_parsedCorrectly() {
        String stdout = """
                HEAD:server/pom.xml:15:    <groupId>cn.teamone</groupId>
                HEAD:web/src/App.tsx:42:export default function App() {
                HEAD:README.md:1:# TeamOne 研发协同平台
                """;
        List<GitSearchMatch> matches = GitCommandPort.parseGrepOutput(stdout, "HEAD");
        assertEquals(3, matches.size());

        assertEquals("server/pom.xml", matches.get(0).filePath());
        assertEquals(15, matches.get(0).lineNo());
        assertEquals("    <groupId>cn.teamone</groupId>", matches.get(0).lineContent());

        assertEquals("web/src/App.tsx", matches.get(1).filePath());
        assertEquals(42, matches.get(1).lineNo());
        assertEquals("export default function App() {", matches.get(1).lineContent());

        assertEquals("README.md", matches.get(2).filePath());
        assertEquals(1, matches.get(2).lineNo());
        assertEquals("# TeamOne 研发协同平台", matches.get(2).lineContent());
    }

    @Test
    void parseGrepOutput_empty_returnsEmpty() {
        assertTrue(GitCommandPort.parseGrepOutput("", "HEAD").isEmpty());
        assertTrue(GitCommandPort.parseGrepOutput(null, "HEAD").isEmpty());
        assertTrue(GitCommandPort.parseGrepOutput("   \n   ", "HEAD").isEmpty());
    }
}

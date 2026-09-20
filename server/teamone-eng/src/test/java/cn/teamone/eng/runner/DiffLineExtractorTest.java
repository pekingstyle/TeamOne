package cn.teamone.eng.runner;

import cn.teamone.eng.infra.git.GitDiffLine;
import cn.teamone.eng.infra.git.GitDiffResult;
import cn.teamone.eng.infra.git.GitFileDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DiffLineExtractor 单测（M4-INC2 · patch 覆盖率口径）：多 hunk 变更行提取（行号经
 * GitCommandPort.parseUnifiedDiff 的 @@ 起始+上下文推进已为绝对行号）、删除行不计、
 * 非 .java / src/test/java 过滤、monorepo 前缀剥除的路径映射、重命名按 b 侧新路径计、
 * 交集分子分母（分母=变更行∩可执行行）与分母 0→null。
 *
 * @author Ivan Yang, 2026-09-20
 */
class DiffLineExtractorTest {

    private static GitDiffResult diffOf(GitFileDiff... files) {
        return new GitDiffResult(0, 0, List.of(files));
    }

    private static GitFileDiff file(String path, String status, GitDiffLine... lines) {
        return new GitFileDiff(path, status, 0, 0, List.of(lines));
    }

    // ==================== 变更行提取 ====================

    @Test
    void extractsAddLinesAcrossMultipleHunks() {
        // 两个 hunk：@@ -1,3 +1,3 @@（ctx 1, add 2,3）与 @@ -20,2 +22,2 @@（del 20, ctx 21, add 22）
        GitFileDiff twoHunks = file("src/main/java/p/Foo.java", "modified",
                new GitDiffLine(1, 1, "ctx", "a"),
                new GitDiffLine(null, 2, "add", "b"),
                new GitDiffLine(null, 3, "add", "c"),
                new GitDiffLine(20, null, "del", "old"),
                new GitDiffLine(21, 21, "ctx", "x"),
                new GitDiffLine(null, 22, "add", "y"));

        Map<String, Set<Integer>> changed = DiffLineExtractor.changedJavaMainLines(diffOf(twoHunks));

        assertEquals(Map.of("p/Foo.java", Set.of(2, 3, 22)), changed);
    }

    @Test
    void deletionOnlyFileContributesNothing() {
        GitFileDiff removed = file("src/main/java/p/Gone.java", "removed",
                new GitDiffLine(5, null, "del", "x"),
                new GitDiffLine(6, null, "del", "y"));

        assertTrue(DiffLineExtractor.changedJavaMainLines(diffOf(removed)).isEmpty(),
                "纯删除无新侧行——不产生变更行条目");
    }

    @Test
    void filtersNonJavaAndTestSources_andStripsMonorepoPrefix() {
        GitFileDiff mainJava = file(
                "server/teamone-eng/src/main/java/cn/teamone/eng/runner/PipelineRunner.java",
                "modified", new GitDiffLine(null, 88, "add", "x"));
        GitFileDiff testJava = file(
                "server/teamone-eng/src/test/java/cn/teamone/eng/runner/PipelineRunnerTest.java",
                "modified", new GitDiffLine(null, 9, "add", "x"));
        GitFileDiff yaml = file("deploy/app.yml", "modified",
                new GitDiffLine(null, 3, "add", "x"));

        Map<String, Set<Integer>> changed =
                DiffLineExtractor.changedJavaMainLines(diffOf(mainJava, testJava, yaml));

        // 只有 .java 且路径含 src/main/java/ 的计入；键剥掉 monorepo 前缀后与 jacoco 键同构
        assertEquals(Map.of("cn/teamone/eng/runner/PipelineRunner.java", Set.of(88)), changed);
    }

    @Test
    void renamedFileCountsAddsUnderNewSidePath() {
        // git rename（部分相似）：b 侧新路径 + 删除旧行/新增新行——新增行按新路径计
        GitFileDiff renamed = file(
                "server/teamone-eng/src/main/java/cn/teamone/eng/runner/NewName.java",
                "modified",
                new GitDiffLine(10, null, "del", "old impl"),
                new GitDiffLine(null, 10, "add", "new impl"),
                new GitDiffLine(null, 11, "add", "new impl 2"));

        Map<String, Set<Integer>> changed = DiffLineExtractor.changedJavaMainLines(diffOf(renamed));

        assertEquals(Map.of("cn/teamone/eng/runner/NewName.java", Set.of(10, 11)), changed);
    }

    @Test
    void pureRenameWithoutHunksProducesNoEntry() {
        // 100% 相似纯重命名：无内容 hunk（无 add 行）——不算涉代码变更
        GitFileDiff pureRename = file(
                "server/teamone-eng/src/main/java/cn/teamone/eng/runner/Moved.java", "modified");

        assertTrue(DiffLineExtractor.changedJavaMainLines(diffOf(pureRename)).isEmpty());
    }

    @Test
    void nullSafeOnEmptyDiff() {
        assertTrue(DiffLineExtractor.changedJavaMainLines(null).isEmpty());
        assertTrue(DiffLineExtractor.changedJavaMainLines(GitDiffResult.empty()).isEmpty());
    }

    // ==================== patch 覆盖率交集 ====================

    private static JacocoXmlParser.Result jacocoOf(Map<String, JacocoXmlParser.SourceLines> sources) {
        return new JacocoXmlParser.Result(60.0, sources);
    }

    @Test
    void patchCoverage_intersectsChangedWithExecutableAndCovered() {
        // 变更行 {10, 11, 12, 99}；jacoco 可执行 {10, 11, 12}（99 不在行集=非可执行行，不进分母）；
        // 覆盖 {10, 11} → 2/3
        Map<String, JacocoXmlParser.SourceLines> sources = Map.of(
                "p/Foo.java", new JacocoXmlParser.SourceLines(Set.of(10, 11, 12), Set.of(10, 11)));

        Double patch = DiffLineExtractor.patchCoveragePercent(
                Map.of("p/Foo.java", Set.of(10, 11, 12, 99)), jacocoOf(sources));

        assertEquals(66.666, patch, 0.001);
    }

    @Test
    void patchCoverage_multiFileAggregation() {
        Map<String, JacocoXmlParser.SourceLines> sources = Map.of(
                "p/A.java", new JacocoXmlParser.SourceLines(Set.of(1, 2), Set.of(1)),   // 1/2
                "p/B.java", new JacocoXmlParser.SourceLines(Set.of(5, 6, 7), Set.of(5, 6, 7))); // 3/3

        Double patch = DiffLineExtractor.patchCoveragePercent(
                Map.of("p/A.java", Set.of(1, 2), "p/B.java", Set.of(5, 6, 7)), jacocoOf(sources));

        assertEquals((1 + 3) * 100.0 / 5, patch, 0.0001, "跨文件合并分子分母后再求比");
    }

    @Test
    void patchCoverage_zeroDenominatorReturnsNull() {
        Map<String, JacocoXmlParser.SourceLines> sources = Map.of(
                "p/Foo.java", new JacocoXmlParser.SourceLines(Set.of(10), Set.of(10)));

        // 变更行 99 不在 jacoco 行集 → 可执行分母 0（无涉代码变更）→ null
        assertNull(DiffLineExtractor.patchCoveragePercent(
                Map.of("p/Foo.java", Set.of(99)), jacocoOf(sources)));
        // 文件键对不上（jacoco 无该文件字节码）→ 同样分母 0
        assertNull(DiffLineExtractor.patchCoveragePercent(
                Map.of("p/Other.java", Set.of(1)), jacocoOf(sources)));
        // 空变更集 / 无报告 → null
        assertNull(DiffLineExtractor.patchCoveragePercent(Map.of(), jacocoOf(sources)));
        assertNull(DiffLineExtractor.patchCoveragePercent(Map.of("p/Foo.java", Set.of(10)), null));
    }

    @Test
    void patchCoverage_allCoveredIsHundred() {
        Map<String, JacocoXmlParser.SourceLines> sources = Map.of(
                "p/Foo.java", new JacocoXmlParser.SourceLines(Set.of(1, 2, 3), Set.of(1, 2, 3)));

        Double patch = DiffLineExtractor.patchCoveragePercent(
                Map.of("p/Foo.java", Set.of(1, 2, 3)), jacocoOf(sources));

        assertEquals(100.0, patch, 0.0001);
        assertFalse(patch < 100.0);
    }
}

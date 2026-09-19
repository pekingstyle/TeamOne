package cn.teamone.eng.app;

import cn.teamone.eng.domain.MergeRequest;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.infra.git.GitBlameLine;
import cn.teamone.eng.infra.git.GitBlameResult;
import cn.teamone.eng.infra.git.GitCommit;
import cn.teamone.eng.infra.git.GitDiffResult;
import cn.teamone.eng.infra.git.GitFileDiff;
import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.repo.MergeRequestRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BlameService 单元与两级缓存契约测试（S-1'）。
 * 验证冷计算回填、L2 Redis 命中加速与回填 L1、以及 MR 变更文件异步预热调度逻辑。
 */
class BlameServiceTest {

    private RepositoryRepository repositoryRepo;
    private MergeRequestRepository mergeRequestRepo;
    private GitPort gitPort;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper objectMapper;

    private BlameService blameService;
    private Repository repo;
    private UUID repoId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repositoryRepo = mock(RepositoryRepository.class);
        mergeRequestRepo = mock(MergeRequestRepository.class);
        gitPort = mock(GitPort.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        repoId = UUID.randomUUID();
        repo = new Repository();
        repo.setId(repoId);
        repo.setName("teamone");
        repo.setRepoPath("teamone/teamone.git");
        repo.setDefaultBranch("main");

        when(repositoryRepo.findById(repoId)).thenReturn(Optional.of(repo));
        when(repositoryRepo.findByName("teamone")).thenReturn(Optional.of(repo));

        blameService = new BlameService(
                repositoryRepo,
                mergeRequestRepo,
                gitPort,
                objectMapper,
                redisTemplate
        );
    }

    @Test
    void getBlame_coldMiss_computesAndFillsCache() {
        String sha = "a0ff5c5f7e5d199efb07721b0cb93776c31f1b43";
        when(gitPort.commits(anyString(), eq("main"), eq(1), eq(1)))
                .thenReturn(List.of(new GitCommit(sha, "Author", "author@test.com", Instant.now(), "msg", "")));

        List<GitBlameLine> lines = List.of(
                new GitBlameLine(1, sha, "a0ff5c5", "Author", "author@test.com", Instant.now(), "msg", "line 1")
        );
        GitBlameResult computed = new GitBlameResult("teamone/teamone.git", sha, "src/App.java", lines, 1, 15, false);
        when(gitPort.blame(eq("teamone/teamone.git"), eq(sha), eq("src/App.java"))).thenReturn(computed);

        // 第一次调用：冷查（缓存未命中）
        GitBlameResult r1 = blameService.getBlame("teamone", "main", "src/App.java");
        assertNotNull(r1);
        assertEquals(1, r1.totalLines());
        assertFalse(r1.fromCache());
        verify(gitPort, times(1)).blame(anyString(), anyString(), anyString());

        // 第二次调用：L1 内存缓存命中（不再调用 gitPort.blame）
        GitBlameResult r2 = blameService.getBlame("teamone", "main", "src/App.java");
        assertNotNull(r2);
        assertTrue(r2.fromCache());
        assertEquals(0, r2.durationMs());
        verify(gitPort, times(1)).blame(anyString(), anyString(), anyString());
    }

    @Test
    void getBlame_l2RedisHit_deserializesAndFillsL1() throws Exception {
        String sha = "b0ff5c5f7e5d199efb07721b0cb93776c31f1b44";
        List<GitBlameLine> lines = List.of(
                new GitBlameLine(1, sha, "b0ff5c5", "Dev", "dev@test.com", Instant.parse("2026-09-13T00:00:00Z"), "commit", "content")
        );
        GitBlameResult cached = new GitBlameResult("teamone/teamone.git", sha, "config.yml", lines, 1, 10, true);
        String json = objectMapper.writeValueAsString(cached);

        when(valueOps.get(contains(sha))).thenReturn(json);

        // 经 40 位 SHA 精确查询
        GitBlameResult result = blameService.getBlame(repoId.toString(), sha, "config.yml");
        assertNotNull(result);
        assertTrue(result.fromCache());
        assertEquals(1, result.totalLines());
        assertEquals("Dev", result.lines().get(0).authorName());
        verify(gitPort, never()).blame(anyString(), anyString(), anyString());
    }

    @Test
    void preloadMrBlame_schedulesWarmupForChangedFiles() {
        UUID mrId = UUID.randomUUID();
        MergeRequest mr = new MergeRequest();
        mr.setId(mrId);
        mr.setRepoId(repoId);
        mr.setMrNumber(101);
        mr.setSourceBranch("feat/blame");
        mr.setTargetBranch("main");

        when(mergeRequestRepo.findById(mrId)).thenReturn(Optional.of(mr));
        when(gitPort.diff("teamone/teamone.git", "main", "feat/blame")).thenReturn(new GitDiffResult(
                10, 2,
                List.of(
                        new GitFileDiff("src/A.java", "modified", 5, 1, List.of()),
                        new GitFileDiff("src/B.java", "added", 5, 1, List.of()),
                        new GitFileDiff("src/Old.java", "removed", 0, 10, List.of()) // removed 不预热
                )
        ));

        int queued = blameService.preloadMrBlame(mrId);
        assertEquals(2, queued);
    }
}

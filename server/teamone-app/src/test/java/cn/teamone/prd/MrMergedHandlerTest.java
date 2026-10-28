package cn.teamone.prd;

import cn.teamone.prd.app.TransitionService;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.handler.MrMergedHandler;
import cn.teamone.prd.repo.WorkItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * mr.merged 消费端单测（⑦ Closes 语义 · docs/v2/17 §2）：仅默认分支+task+未完结流转 done；
 * requirement/已完结/非默认分支跳过（GitLab 口径）。
 *
 * @author Ivan Yang, 2026-09-30
 */
class MrMergedHandlerTest {

    private final WorkItemRepository workItems = mock(WorkItemRepository.class);
    private final TransitionService transitions = mock(TransitionService.class);
    private final MrMergedHandler handler = new MrMergedHandler(workItems, transitions);
    private final ObjectMapper om = new ObjectMapper();

    private ObjectNode payload(boolean defaultBranch, String type, String status, String mergedBy) {
        ObjectNode n = om.createObjectNode();
        n.put("mrNumber", 21);
        n.put("linkedWorkItemKey", "T-77");
        n.put("targetBranch", defaultBranch ? "main" : "project/a-group");
        n.put("isDefaultBranch", defaultBranch);
        n.put("mergedBy", mergedBy);
        return n;
    }

    private ObjectNode developPayload(String type, String status) {
        ObjectNode n = om.createObjectNode();
        n.put("mrNumber", 22);
        n.put("linkedWorkItemKey", "T-77");
        n.put("targetBranch", "develop");
        n.put("isDefaultBranch", false);
        n.put("mergedBy", UUID.randomUUID().toString());
        return n;
    }

    private WorkItem item(String type, String status) {
        WorkItem w = new WorkItem();
        w.setKey("T-77");
        w.setType(type);
        w.setStatus(status);
        ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        return w;
    }

    @Test
    void closesTaskOnDefaultBranchMerge() {
        WorkItem task = item("task", "in_progress");
        when(workItems.findByKey("T-77")).thenReturn(Optional.of(task));

        handler.handle(payload(true, "task", "in_progress", UUID.randomUUID().toString()));

        ArgumentCaptor<UUID> idCap = ArgumentCaptor.forClass(UUID.class);
        verify(transitions).transition(idCap.capture(), eq("done"), any(UUID.class));
        assertEquals(task.getId(), idCap.getValue());
    }

    @Test
    void closesOnDevelopIntegrationLine() {
        // 集成线语义：feature→develop 合并同样触发（develop 承担默认分支集成职责）
        WorkItem task = item("task", "in_progress");
        when(workItems.findByKey("T-77")).thenReturn(Optional.of(task));
        handler.handle(developPayload("task", "in_progress"));
        verify(transitions).transition(eq(task.getId()), eq("done"), any(UUID.class));
    }

    @Test
    void skipsOnDeliveryBranch() {
        // 交付分支（project/*）合并不触发
        when(workItems.findByKey("T-77")).thenReturn(Optional.of(item("task", "in_progress")));
        handler.handle(payload(false, "task", "in_progress", UUID.randomUUID().toString()));
        verify(transitions, never()).transition(any(), any(), any());
    }

    @Test
    void skipsRequirementType() {
        when(workItems.findByKey("T-77")).thenReturn(Optional.of(item("requirement", "in_dev")));
        handler.handle(payload(true, "requirement", "in_dev", UUID.randomUUID().toString()));
        verify(transitions, never()).transition(any(), any(), any());
    }

    @Test
    void idempotentOnFinishedTask() {
        when(workItems.findByKey("T-77")).thenReturn(Optional.of(item("task", "done")));
        handler.handle(payload(true, "task", "done", UUID.randomUUID().toString()));
        verify(transitions, never()).transition(any(), any(), any());
    }

    @Test
    void dataAccessFailureRethrowsForPelRedelivery() {
        // QA MUST-FIX ①锁语义：基础设施故障必须放行 PEL 重投（吞掉=瞬时故障即回写永久丢失）
        WorkItem task = item("task", "in_progress");
        when(workItems.findByKey("T-77")).thenReturn(Optional.of(task));
        when(transitions.transition(any(UUID.class), eq("done"), any(UUID.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db jitter"));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> handler.handle(payload(true, "task", "in_progress", UUID.randomUUID().toString())));
    }

    @Test
    void businessRejectDoesNotThrow() {
        WorkItem task = item("task", "todo");
        when(workItems.findByKey("T-77")).thenReturn(Optional.of(task));
        // todo → done 非法流转（状态机 422）：捕获跳过，不抛（PEL 不重投）
        when(transitions.transition(any(UUID.class), eq("done"), any(UUID.class)))
                .thenThrow(new cn.teamone.shared.api.BusinessException(
                        cn.teamone.shared.api.ErrorCode.PRD_4201, "状态不允许该流转"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                handler.handle(payload(true, "task", "todo", UUID.randomUUID().toString())));
    }
}

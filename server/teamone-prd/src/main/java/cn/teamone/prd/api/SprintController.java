package cn.teamone.prd.api;

import cn.teamone.prd.app.SprintService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 迭代端点（M2-INC-1 W1，05 §3.3 /api/v1/sprints）。
 * POST /{id}/complete：未完成项滚动（status 保持）+ completed_at 标记 + outbox(sprint.completed)
 * 同事务（红线④⑤，05 §6.1）；重复完成 422 T1-PRD-4201。
 */
@RestController
@RequestMapping("/api/v1/sprints")
public class SprintController {

    private final SprintService sprints;
    private final ObjectMapper om;

    public SprintController(SprintService sprints, ObjectMapper om) {
        this.sprints = sprints;
        this.om = om;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String productId) {
        Actor.require();
        return sprints.list(productId);
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody Map<String, Object> body) {
        UUID actor = Actor.require();
        Map<String, Object> view = sprints.create(
                om.convertValue(body, SprintService.CreateSpec.class), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PostMapping("/{id}/complete")
    public Map<String, Object> complete(@PathVariable String id) {
        return sprints.complete(id, Actor.require());
    }
}

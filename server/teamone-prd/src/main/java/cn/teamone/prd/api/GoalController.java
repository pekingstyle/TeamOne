package cn.teamone.prd.api;

import cn.teamone.prd.app.GoalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 战略目标端点（M2-INC-1 W1，05 §3.3 /api/v1/goals）。
 *
 * <p>读端点登录态（Actor.require + JWT 过滤器）；写在 service 内四步链鉴权。
 * GET /{id}/rollup 为查询侧聚合（05 §2.4 红线①），见 {@link GoalService#rollup}。</p>
 */
@RestController
@RequestMapping("/api/v1/goals")
public class GoalController {

    private final GoalService goals;
    private final ObjectMapper om;

    public GoalController(GoalService goals, ObjectMapper om) {
        this.goals = goals;
        this.om = om;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        Actor.require();
        return goals.list();
    }

    /** 全目标概览：{goals:[{goalId,name,total,done,completionRate,items:[{id,name,done,total,doneHours,inProgressHours,todoHours}]}]} */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Actor.require();
        return goals.overview();
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody Map<String, Object> body) {
        UUID actor = Actor.require();
        Map<String, Object> view = goals.create(om.convertValue(body, GoalService.CreateSpec.class), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** 目标进度下钻：{goalId, roadmapItems:[{id,name,workItemDone,total,releaseIds}]} */
    @GetMapping("/{id}/rollup")
    public Map<String, Object> rollup(@PathVariable String id) {
        Actor.require();
        return goals.rollup(id);
    }
}

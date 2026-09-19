package cn.teamone.prd.api;

import cn.teamone.prd.app.RoadmapService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RoadMap 条目端点（M2-INC-1 W1，05 §3.3 /api/v1/roadmap-items）。
 * 列表可选 goalId/productId/releaseId 过滤（uuid 或业务键）；创建 service 内四步链。
 */
@RestController
@RequestMapping("/api/v1/roadmap-items")
public class RoadmapItemController {

    private final RoadmapService roadmaps;
    private final ObjectMapper om;

    public RoadmapItemController(RoadmapService roadmaps, ObjectMapper om) {
        this.roadmaps = roadmaps;
        this.om = om;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String goalId,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String releaseId) {
        Actor.require();
        return roadmaps.list(goalId, productId, releaseId);
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody Map<String, Object> body) {
        UUID actor = Actor.require();
        Map<String, Object> view = roadmaps.create(
                om.convertValue(body, RoadmapService.CreateSpec.class), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }
}

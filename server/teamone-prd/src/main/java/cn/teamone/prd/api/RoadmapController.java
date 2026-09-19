package cn.teamone.prd.api;

import cn.teamone.prd.app.RoadmapService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RoadMap 双视角时间轴端点（M2-INC-1 W1 基础版，05 §3.3 /api/v1/roadmap/timeline）。
 * view=goal：目标列表+各目条目+条目关联版本时间窗；view=release：版本列表+条目。
 * W2 在此基础上补下钻树逐级返回；数据全来自查询侧聚合。
 */
@RestController
@RequestMapping("/api/v1/roadmap")
public class RoadmapController {

    private final RoadmapService roadmaps;

    public RoadmapController(RoadmapService roadmaps) {
        this.roadmaps = roadmaps;
    }

    @GetMapping("/timeline")
    public Map<String, Object> timeline(@RequestParam String view) {
        Actor.require();
        return roadmaps.timeline(view);
    }
}

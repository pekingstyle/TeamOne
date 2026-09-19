package cn.teamone.prd.api;

import cn.teamone.prd.app.ReleaseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 版本端点（05 §3.3 /api/v1/releases）：列表 / 详情 / publish 门禁事务。 */
@RestController
@RequestMapping("/api/v1/releases")
public class ReleaseController {

    private final ReleaseService releases;

    public ReleaseController(ReleaseService releases) {
        this.releases = releases;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        Actor.require();
        return releases.list();
    }

    /** id 或业务键（v2.4.0） */
    @GetMapping("/{idOrKey}")
    public Map<String, Object> get(@PathVariable String idOrKey) {
        Actor.require();
        return releases.get(idOrKey);
    }

    /** 发布门禁事务：阻塞 422/T1-PRD-4230 + 清单；成功 released + outbox + 审计 */
    @PostMapping("/{idOrKey}/publish")
    public Map<String, Object> publish(@PathVariable String idOrKey) {
        return releases.publish(idOrKey, Actor.require());
    }
}

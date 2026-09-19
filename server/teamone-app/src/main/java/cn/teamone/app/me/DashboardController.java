package cn.teamone.app.me;

import cn.teamone.prd.api.Actor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工作台聚合端点（GET /api/v1/me/summary，05 §3.3 platform 段已登记；M2-W4 体验修复 P1-1）。
 *
 * <p>跨域只读聚合，落位 app 组合根（R3/R4/R6 模块方向，见 {@link DashboardService}）；
 * 读端点登录态（Actor.require + JWT 过滤器），无写侧、无事件。</p>
 */
@RestController
@RequestMapping("/api/v1/me")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /** {greetingName, activeSprint, goals, myRedConflicts, releaseGate, myTodos, myTopics} */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return dashboard.summary(Actor.require());
    }
}

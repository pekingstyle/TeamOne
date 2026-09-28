package cn.teamone.prd.api;

import cn.teamone.prd.app.ProjectService;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 项目交付端点（⑥o 客制化双轨，docs/v2/15 §4 /api/v1/projects）。
 *
 * <p>鉴权：读端点登录态（JWT 过滤器）；写端点 Actor.require + service 内
 * 按项目交付产品动态鉴权（product edit）。</p>
 *
 * @author Ivan Yang, 2026-09-28
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projects;
    private final ObjectMapper om;

    public ProjectController(ProjectService projects, ObjectMapper om) {
        this.projects = projects;
        this.om = om;
    }

    /** 列表：status/productId 过滤，行含度量 + productName */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String productId) {
        Actor.require();
        return projects.list(status, productId);
    }

    /** 建项目（name 唯一，重复 422 T1-PRD-4257；status 缺省 delivering） */
    @PostMapping
    public ResponseEntity<Object> create(@RequestBody Map<String, Object> body) {
        UUID actor = Actor.require();
        Map<String, Object> view = projects.create(
                om.convertValue(body, ProjectService.ProjectSpec.class), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** 详情：项目字段 + 客制化需求条目 + 度量 */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Actor.require();
        return projects.get(id);
    }

    /** 升级冲突预警（⑥p · docs/v2/15 §9）：客制组件集 ∩ 产品演进组件集，红=in_dev 撞线 */
    @GetMapping("/{id}/upgrade-warnings")
    public Map<String, Object> upgradeWarnings(@PathVariable String id) {
        Actor.require();
        return projects.upgradeWarnings(id);
    }

    /** 更新（If-Match: <version> 乐观锁，同工作项惯例；可改 customer_name/status/manager_id/日期） */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return projects.update(id, om.convertValue(body, ProjectService.ProjectUpdateSpec.class),
                parseVersion(ifMatch), Actor.require());
    }

    // ==================== 内部 ====================

    private Integer parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PLT_4000, "If-Match 版本头非法: " + ifMatch);
        }
    }
}

package cn.teamone.prd.api;

import cn.teamone.prd.app.HierarchyService;
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

/** 组件端点（M2-INC-1 W1，05 §3.3 /api/v1/components）：可选 productId 过滤（uuid 或业务键）。 */
@RestController
@RequestMapping("/api/v1/components")
public class ComponentController {

    private final HierarchyService hierarchy;
    private final ObjectMapper om;

    public ComponentController(HierarchyService hierarchy, ObjectMapper om) {
        this.hierarchy = hierarchy;
        this.om = om;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String productId) {
        Actor.require();
        return hierarchy.listComponents(productId);
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody Map<String, Object> body) {
        UUID actor = Actor.require();
        Map<String, Object> view = hierarchy.createComponent(
                om.convertValue(body, HierarchyService.ComponentSpec.class), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }
}

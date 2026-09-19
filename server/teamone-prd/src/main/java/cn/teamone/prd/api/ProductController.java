package cn.teamone.prd.api;

import cn.teamone.prd.app.HierarchyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 产品端点（M2-INC-1 W1，05 §3.3 /api/v1/products）：列表登录态；创建 service 内四步链。 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final HierarchyService hierarchy;
    private final ObjectMapper om;

    public ProductController(HierarchyService hierarchy, ObjectMapper om) {
        this.hierarchy = hierarchy;
        this.om = om;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        Actor.require();
        return hierarchy.listProducts();
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody Map<String, Object> body) {
        UUID actor = Actor.require();
        Map<String, Object> view = hierarchy.createProduct(
                om.convertValue(body, HierarchyService.ProductSpec.class), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }
}

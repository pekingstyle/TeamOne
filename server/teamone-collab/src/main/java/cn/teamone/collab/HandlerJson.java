package cn.teamone.collab;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handler 载荷取值助手（collab 域内共享；payload 全部由 outbox jsonb 解析）。
 * 取值失败语义：text→""；uuidOrNull→null；uuidOrThrow→抛业务异常（载荷契约被破坏，
 * 让事件进 PEL 重投/死信而非静默吞掉）。
 */
public final class HandlerJson {

    private HandlerJson() {}

    public static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    public static UUID uuidOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(v.asText());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 载荷契约字段缺失/非法 → 抛 COL 域中性 400 语义异常（PLT_4000 已注册），事件进 PEL */
    public static UUID uuidOrThrow(JsonNode node, String field, String event) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new cn.teamone.shared.api.BusinessException(
                    cn.teamone.shared.api.ErrorCode.PLT_4000,
                    "事件载荷缺字段 " + field + " (" + event + ")");
        }
        try {
            return UUID.fromString(v.asText());
        } catch (IllegalArgumentException ex) {
            throw new cn.teamone.shared.api.BusinessException(
                    cn.teamone.shared.api.ErrorCode.PLT_4000,
                    "事件载荷字段 " + field + " 非法 UUID (" + event + "): " + v.asText());
        }
    }

    /** UUID 数组字段（如 stakeholderUserIds/reviewerIds），缺失或元素非法时安全过滤 */
    public static List<UUID> uuidList(JsonNode node, String field) {
        JsonNode v = node.get(field);
        List<UUID> ids = new ArrayList<>();
        if (v == null || !v.isArray()) {
            return ids;
        }
        for (JsonNode item : v) {
            if (item == null || item.isNull()) continue;
            try {
                ids.add(UUID.fromString(item.asText()));
            } catch (IllegalArgumentException ignored) {
                // 坏元素跳过（干系人清单宁缺毋滥）
            }
        }
        return ids;
    }
}

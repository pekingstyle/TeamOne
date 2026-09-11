package cn.teamone.shared.api;

/**
 * 统一错误码目录（架构设计 05 文档 §3.2 定稿）。
 *
 * <p>分段规范：{@code T1-{域}-{HTTP语义4位}}；域：PLT/PRD/COL/ENG/INS，服务端 5xxx 用 SRV。
 * 4xxx 可展示给终端用户；5xxx 只展示 traceId，不暴露内部细节。</p>
 *
 * <p><b>纪律：业务代码禁止手写错误码字符串，一律引用本目录；新增错误码必须先在此注册
 * （评审项，ArchUnit/CodeReview 双保险）。</b>W1 预注册 §3.2 全段，M1 各域按需启用。</p>
 *
 * @author Ivan Yang, 2026-09-11
 */
public enum ErrorCode {

    // ---------- PLT 平台/权限 ----------
    /** 请求不合法（参数校验失败、WS 帧不是合法 JSON/未知信令） */
    PLT_4000("T1-PLT-4000", 400, "请求不合法"),
    /** 未认证或凭证已失效（REST 401 入口；WS 未认证帧） */
    PLT_4010("T1-PLT-4010", 401, "未认证或凭证已失效"),
    /** 登录失败：用户名或密码错误（不区分二者，防枚举） */
    PLT_4011("T1-PLT-4011", 401, "用户名或密码错误"),
    /** 刷新令牌无效或已过期（旋转后的旧令牌/被吊销令牌） */
    PLT_4012("T1-PLT-4012", 401, "刷新令牌无效或已过期"),
    /** 授权链未通过（四步短路链默认拒绝） */
    PLT_4030("T1-PLT-4030", 403, "无权限"),
    /** 乐观锁版本冲突（If-Match 不匹配，响应携带最新实体） */
    PLT_4091("T1-PLT-4091", 409, "版本冲突，请刷新后重试"),

    // ---------- PRD 产品研发域 ----------
    /** 需求/任务/缺陷状态机非法流转（transition 唯一入口校验） */
    PRD_4201("T1-PRD-4201", 422, "状态不允许该流转"),
    /** 发布被致命/严重缺陷阻塞（响应携带阻塞清单） */
    PRD_4230("T1-PRD-4230", 422, "发布被致命/严重缺陷阻塞"),
    /** 工作项 Deadline 越级（CF-6，违反上层容器截止日） */
    PRD_4240("T1-PRD-4240", 422, "Deadline 越级"),

    // ---------- COL 协同域 ----------
    /** 话题已归档，写操作被拒（可重开后再写） */
    COL_4203("T1-COL-4203", 403, "话题已归档"),
    /** 非会话成员（WS sub/msg 与 REST 会话接口统一） */
    COL_4210("T1-COL-4210", 403, "非会话成员"),

    // ---------- ENG 工程底座域 ----------
    /** 单测门禁未通过（双阈值：整体>=60% / patch>=80%，或用例失败；可豁免+审计） */
    ENG_4250("T1-ENG-4250", 422, "单测门禁未通过"),
    /** MR 存在未解决冲突 */
    ENG_4251("T1-ENG-4251", 422, "存在未解决冲突"),
    /** MR rebase 未完成 */
    ENG_4252("T1-ENG-4252", 422, "rebase 未完成"),

    // ---------- INS 概览域 ----------
    /** 冲突/报表批任务未就绪（快照不存在或刷新中） */
    INS_4300("T1-INS-4300", 409, "批任务未就绪，请稍后"),

    // ---------- SRV 服务端 ----------
    /** 服务内部错误（只回 traceId，不暴露堆栈） */
    SRV_5000("T1-SRV-5000", 500, "服务内部错误，请联系管理员并提供 traceId"),
    /** 依赖不可用（Gitea/Valkey/MinIO 超时或失联，可重试） */
    SRV_5030("T1-SRV-5030", 503, "依赖服务暂不可用，请稍后重试");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    /** 错误码全文，如 T1-PLT-4030 */
    public String code() {
        return code;
    }

    /** 对应的 HTTP 状态码 */
    public int httpStatus() {
        return httpStatus;
    }

    /** 默认用户可读消息（可被调用方覆盖为更具体的描述） */
    public String defaultMessage() {
        return defaultMessage;
    }

    /** 是否为服务端 5xxx（对外只回 traceId） */
    public boolean isServerError() {
        return httpStatus >= 500;
    }
}

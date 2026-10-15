package com.srm.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.GlobalExceptionHandler;
import com.srm.core.dto.lifecycle.LifecycleDecisionRequest;
import com.srm.core.dto.lifecycle.LifecycleRequestCreateRequest;
import com.srm.core.dto.lifecycle.LifecycleRequestResponse;
import com.srm.core.service.LifecycleRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块二 §2.4/§2.5/§2.6 LifecycleRequestController 入参层契约测试
 * （standalone MockMvc + 真实 GlobalExceptionHandler，照 B1/B2 控制器测试范式）。
 *
 * 锁定行为（只锁路由层该管的事，业务闸门在 Service 单测与集成测试里锁）：
 * 1. 请求体绑定：{@code type}/{@code reason}/{@code decision}/{@code comment}/{@code version}
 *    的物理 snake_case 与驼峰字段一一对应；
 * 2. §2.4 请求体<b>结构上不存在 status 与 version</b>：即使前端塞了这两字段也只能被忽略，
 *    Service 收到的对象不可能携带状态或版本（两步流程铁律在入参层的物证）；
 * 3. 入参层只拦单字段格式（type/decision 值域、reason 必填、version 为正整数）→ 40001；
 *    <b>version 缺失必须放行到 Service</b>（并发语义折 40901，契约 §2.6 L512 + api-spec §3 L318）；
 * 4. 出参附录 A3 的 13 字段一个不能少且物理 snake_case；
 * 5. 传输口径（用户裁决 2026-10-14）：所有业务错误维持 HTTP 200 + 响应体 code。
 */
@ExtendWith(MockitoExtension.class)
class LifecycleRequestControllerTest {

    @Mock
    private LifecycleRequestService lifecycleRequestService;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PARAM_INVALID_MSG = "参数校验失败";
    private static final String VERSION_MSG = "版本号不合法（必须为正整数）";
    private static final String VERSION_CONFLICT_MSG = "数据已被修改，请刷新后重试";
    private static final String STATUS_NOT_ALLOWED_MSG = "当前状态不允许该操作";
    private static final String IN_FLIGHT_MSG = "已存在在途申请，请勿重复提交";

    /** 附录 A3 的 13 字段清单（顺序无关，集合必须完全一致） */
    private static final String[] A3_FIELDS = {
            "id", "supplier_id", "type", "reason", "status", "applied_by", "applied_by_name",
            "applied_at", "decided_by", "decided_by_name", "decided_at", "decision_comment", "version"
    };

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LifecycleRequestController(lifecycleRequestService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private LifecycleRequestResponse response(Long id, String type, String status, Integer version) {
        return LifecycleRequestResponse.builder()
                .id(id)
                .supplierId(1001L)
                .type(type)
                .reason("连续两期绩效 D 级，暂停新订单分配。")
                .status(status)
                .appliedBy(2L)
                .appliedByName("张三")
                .appliedAt("2026-10-15T09:30:00")
                .decidedBy("APPROVED".equals(status) || "REJECTED".equals(status) ? 3L : null)
                .decidedByName("APPROVED".equals(status) || "REJECTED".equals(status) ? "李四" : null)
                .decidedAt("APPROVED".equals(status) || "REJECTED".equals(status) ? "2026-10-15T11:00:00" : null)
                .decisionComment("APPROVED".equals(status) || "REJECTED".equals(status) ? "确认停用。" : null)
                .version(version)
                .build();
    }

    // ==================== §2.4 创建申请 ====================

    @Test
    void createBindsSnakeCaseBodyAndDelegatesToService() throws Exception {
        when(lifecycleRequestService.create(eq(1001L), any(LifecycleRequestCreateRequest.class)))
                .thenReturn(response(500L, "SUSPEND", "PENDING", 1));

        mockMvc.perform(post("/api/v1/suppliers/1001/lifecycle-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SUSPEND\",\"reason\":\"连续两期绩效 D 级，暂停新订单分配。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        ArgumentCaptor<LifecycleRequestCreateRequest> captor =
                ArgumentCaptor.forClass(LifecycleRequestCreateRequest.class);
        verify(lifecycleRequestService).create(eq(1001L), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("SUSPEND");
        assertThat(captor.getValue().getReason()).isEqualTo("连续两期绩效 D 级，暂停新订单分配。");
    }

    @ParameterizedTest
    @ValueSource(strings = {"suspend", "BLACKLIST", "SUSPEND_", "STOP"})
    void createRejectsIllegalTypeCodeWith40001AtInputLayer(String type) throws Exception {
        mockMvc.perform(post("/api/v1/suppliers/1001/lifecycle-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\",\"reason\":\"测试理由。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(PARAM_INVALID_MSG));

        verify(lifecycleRequestService, never()).create(anyLong(), any());
    }

    @Test
    void createRejectsMissingTypeAndBlankReasonWith40001() throws Exception {
        // type 缺失 + reason 纯空白：两个 @NotBlank/@Pattern 共用契约泛义文案
        mockMvc.perform(post("/api/v1/suppliers/1001/lifecycle-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"测试理由。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(PARAM_INVALID_MSG));

        mockMvc.perform(post("/api/v1/suppliers/1001/lifecycle-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SUSPEND\",\"reason\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(PARAM_INVALID_MSG));

        verify(lifecycleRequestService, never()).create(anyLong(), any());
    }

    @Test
    void createBodyStructurallyCannotCarryStatusOrVersionEvenIfClientSendsThem() throws Exception {
        // 两步流程铁律的入参层物证：客户端伪造 status/version 一律被丢弃，Service 不可能收到状态
        when(lifecycleRequestService.create(eq(1001L), any(LifecycleRequestCreateRequest.class)))
                .thenReturn(response(500L, "ELIMINATE", "PENDING", 1));

        mockMvc.perform(post("/api/v1/suppliers/1001/lifecycle-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ELIMINATE\",\"reason\":\"资不抵债，依据破产裁定。\","
                                + "\"status\":\"APPROVED\",\"version\":99,\"supplier_id\":9999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<LifecycleRequestCreateRequest> captor =
                ArgumentCaptor.forClass(LifecycleRequestCreateRequest.class);
        verify(lifecycleRequestService).create(eq(1001L), captor.capture());
        LifecycleRequestCreateRequest bound = captor.getValue();
        assertThat(java.util.Arrays.stream(LifecycleRequestCreateRequest.class.getDeclaredFields())
                .map(f -> f.getName()).filter(n -> !"type".equals(n) && !"reason".equals(n)).findFirst())
                .as("请求体 DTO 只允许 type/reason 两个字段").isEmpty();
        assertThat(bound.getType()).isEqualTo("ELIMINATE");
    }

    @Test
    void createSurfacesServiceSideGateErrorsAsHttp200WithBusinessCode() throws Exception {
        // 40302 / 40902 都是 Service 判定，路由层不得改传输口径（HTTP 恒 200，code 承载）
        when(lifecycleRequestService.create(eq(1001L), any(LifecycleRequestCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, STATUS_NOT_ALLOWED_MSG));
        mockMvc.perform(post("/api/v1/suppliers/1001/lifecycle-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SUSPEND\",\"reason\":\"测试理由。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302))
                .andExpect(jsonPath("$.message").value(STATUS_NOT_ALLOWED_MSG));

        when(lifecycleRequestService.create(eq(1001L), any(LifecycleRequestCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_IN_PROGRESS, IN_FLIGHT_MSG));
        mockMvc.perform(post("/api/v1/suppliers/1001/lifecycle-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SUSPEND\",\"reason\":\"测试理由。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40902))
                .andExpect(jsonPath("$.message").value(IN_FLIGHT_MSG));

        // 仅建档人闸（裁决 20261015）同为 40302，Service 文案「仅限本人操作」须原样透出
        when(lifecycleRequestService.create(eq(1001L), any(LifecycleRequestCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "仅限本人操作"));
        mockMvc.perform(post("/api/v1/suppliers/1001/lifecycle-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SUSPEND\",\"reason\":\"非建档人协同提单。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302))
                .andExpect(jsonPath("$.message").value("仅限本人操作"));

        when(lifecycleRequestService.create(eq(9999L), any(LifecycleRequestCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在"));
        mockMvc.perform(post("/api/v1/suppliers/9999/lifecycle-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SUSPEND\",\"reason\":\"测试理由。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ==================== §2.5 申请历史 ====================

    @Test
    void listExposesExactlyAppendixA3ThirteenFieldsInPhysicalSnakeCase() throws Exception {
        when(lifecycleRequestService.listBySupplier(1001L)).thenReturn(List.of(
                response(502L, "SUSPEND", "APPROVED", 2),
                response(501L, "ELIMINATE", "PENDING", 1)));

        String body = mockMvc.perform(get("/api/v1/suppliers/1001/lifecycle-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode rows = objectMapper.readTree(body).get("data");
        assertThat(rows.isArray()).isTrue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder(A3_FIELDS);
        assertThat(rows.get(1).fieldNames()).toIterable().containsExactlyInAnyOrder(A3_FIELDS);
        // 未决单的决策痕迹三枚原样 null（不得伪造空串或时间）
        assertThat(rows.get(1).get("decided_by").isNull()).isTrue();
        assertThat(rows.get(1).get("decided_by_name").isNull()).isTrue();
        assertThat(rows.get(1).get("decided_at").isNull()).isTrue();
        assertThat(rows.get(1).get("decision_comment").isNull()).isTrue();
        // §2.6 入参取值链：列表每行必须带 version
        assertThat(rows.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(rows.get(0).get("applied_by_name").asText()).isEqualTo("张三");
    }

    @Test
    void listDelegatesPathIdAndKeepsServiceOrderWithoutResorting() throws Exception {
        when(lifecycleRequestService.listBySupplier(1001L)).thenReturn(List.of(
                response(502L, "RESUME", "REJECTED", 2), response(501L, "SUSPEND", "APPROVED", 2)));

        mockMvc.perform(get("/api/v1/suppliers/1001/lifecycle-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(502))
                .andExpect(jsonPath("$.data[1].id").value(501));

        verify(lifecycleRequestService).listBySupplier(1001L);
    }

    @Test
    void listReturnsEmptyArrayWhenSupplierHasNoHistory() throws Exception {
        when(lifecycleRequestService.listBySupplier(1001L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/suppliers/1001/lifecycle-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void listSurfacesMissingSupplierAsHttp200With40401() throws Exception {
        when(lifecycleRequestService.listBySupplier(9999L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在"));

        mockMvc.perform(get("/api/v1/suppliers/9999/lifecycle-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.message").value("供应商不存在"));
    }

    // ==================== §2.6 终审决策 ====================

    @Test
    void decisionBindsThreeContractFieldsAndDelegatesRequestId() throws Exception {
        when(lifecycleRequestService.decide(eq(500L), any(LifecycleDecisionRequest.class)))
                .thenReturn(response(500L, "SUSPEND", "APPROVED", 2));

        mockMvc.perform(post("/api/v1/lifecycle-requests/500/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"确认停用。\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.decided_by_name").value("李四"));

        ArgumentCaptor<LifecycleDecisionRequest> captor =
                ArgumentCaptor.forClass(LifecycleDecisionRequest.class);
        verify(lifecycleRequestService).decide(eq(500L), captor.capture());
        assertThat(captor.getValue().getDecision()).isEqualTo("APPROVE");
        assertThat(captor.getValue().getComment()).isEqualTo("确认停用。");
        assertThat(captor.getValue().getVersion()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "PASS", "REVIEW_APPROVE", ""})
    void decisionRejectsIllegalDecisionCodeWith40001(String decision) throws Exception {
        mockMvc.perform(post("/api/v1/lifecycle-requests/500/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"" + decision + "\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(PARAM_INVALID_MSG));

        verify(lifecycleRequestService, never()).decide(anyLong(), any());
    }

    @Test
    void decisionRejectsNonPositiveVersionAtInputLayerWith40001() throws Exception {
        for (String version : List.of("0", "-5")) {
            mockMvc.perform(post("/api/v1/lifecycle-requests/500/decision")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"REJECT\",\"comment\":\"理由不足。\","
                                    + "\"version\":" + version + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value(VERSION_MSG));
        }
        verify(lifecycleRequestService, never()).decide(anyLong(), any());
    }

    @Test
    void missingVersionMustReachServiceBecauseItIsOptimisticLockSemantics() throws Exception {
        // 关键分工：version <b>缺失</b>属并发语义（40901），入参层绝不能提前折成 40001 拦下
        when(lifecycleRequestService.decide(eq(500L), any(LifecycleDecisionRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, VERSION_CONFLICT_MSG));

        mockMvc.perform(post("/api/v1/lifecycle-requests/500/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"确认停用。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.message").value(VERSION_CONFLICT_MSG));

        ArgumentCaptor<LifecycleDecisionRequest> captor =
                ArgumentCaptor.forClass(LifecycleDecisionRequest.class);
        verify(lifecycleRequestService).decide(eq(500L), captor.capture());
        assertThat(captor.getValue().getVersion()).isNull();
    }

    @Test
    void decisionCommentIsOptionalSoRejectWithoutCommentReachesService() throws Exception {
        // 契约 §2.6 字段表 comment 必填=否：本域不得套用 §2.2/§2.3 的"驳回必须给理由"
        when(lifecycleRequestService.decide(eq(500L), any(LifecycleDecisionRequest.class)))
                .thenReturn(response(500L, "SUSPEND", "REJECTED", 2));

        mockMvc.perform(post("/api/v1/lifecycle-requests/500/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        ArgumentCaptor<LifecycleDecisionRequest> captor =
                ArgumentCaptor.forClass(LifecycleDecisionRequest.class);
        verify(lifecycleRequestService).decide(eq(500L), captor.capture());
        assertThat(captor.getValue().getComment()).isNull();
    }

    @Test
    void decisionSurfacesGateErrorsAsHttp200WithBusinessCode() throws Exception {
        when(lifecycleRequestService.decide(eq(500L), any(LifecycleDecisionRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, STATUS_NOT_ALLOWED_MSG));
        mockMvc.perform(post("/api/v1/lifecycle-requests/500/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40302));

        when(lifecycleRequestService.decide(eq(9999L), any(LifecycleDecisionRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "资源不存在"));
        mockMvc.perform(post("/api/v1/lifecycle-requests/9999/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.message").value("资源不存在"));

        when(lifecycleRequestService.decide(eq(500L), any(LifecycleDecisionRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, VERSION_CONFLICT_MSG));
        mockMvc.perform(post("/api/v1/lifecycle-requests/500/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"version\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.message").value(VERSION_CONFLICT_MSG));
    }

    @Test
    void decisionRequestStructurallyCannotCarryTargetSupplierStatus() throws Exception {
        // 供应商目标态只能由 type 在后端硬映射，入参层不给任何覆盖通道
        assertThat(java.util.Arrays.stream(LifecycleDecisionRequest.class.getDeclaredFields())
                .map(f -> f.getName()).filter(n -> !"decision".equals(n) && !"comment".equals(n)
                        && !"version".equals(n)).findFirst())
                .as("决策 DTO 只允许 decision/comment/version 三个字段").isEmpty();
    }
}

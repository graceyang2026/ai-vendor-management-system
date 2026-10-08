package com.srm.core.controller;

import com.srm.core.common.PageResult;
import com.srm.core.dto.auditlog.AuditLogResponse;
import com.srm.core.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块 4 AuditLogController 契约测试：
 * GET /api/v1/audit-logs（docs/api-spec.md 第 5 节），snake_case 查询参数 + 默认分页。
 */
@ExtendWith(MockitoExtension.class)
class AuditLogControllerTest {

    @Mock
    private AuditLogService auditLogService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuditLogController(auditLogService)).build();
    }

    @Test
    void listPassesSnakeCaseParamsAndDefaults() throws Exception {
        when(auditLogService.list(null, null, 1, 10))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 10));

        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.page_size").value(10));

        verify(auditLogService).list(null, null, 1, 10);
    }

    @Test
    void listPassesExplicitFiltersAndPagingThrough() throws Exception {
        AuditLogResponse row = AuditLogResponse.builder()
                .id(1L).entityType("SUPPLIER").entityId(9L)
                .operatorId(3L).operatorName("审核员李四").operatorRole("AUDITOR")
                .action("AUDIT_REJECT").oldStatus("PENDING_AUDIT").newStatus("RETURNED")
                .result("REJECTED").comment("资质过期")
                .createdAt(LocalDateTime.of(2026, 10, 8, 12, 0))
                .build();
        when(auditLogService.list("SUPPLIER", 9L, 2, 20))
                .thenReturn(new PageResult<>(List.of(row), 1L, 2, 20));

        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("entity_type", "SUPPLIER")
                        .param("entity_id", "9")
                        .param("page", "2")
                        .param("page_size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].entity_type").value("SUPPLIER"))
                .andExpect(jsonPath("$.data.list[0].operator_name").value("审核员李四"))
                .andExpect(jsonPath("$.data.list[0].created_at").exists())
                .andExpect(jsonPath("$.data.page_size").value(20));

        verify(auditLogService).list("SUPPLIER", 9L, 2, 20);
    }
}

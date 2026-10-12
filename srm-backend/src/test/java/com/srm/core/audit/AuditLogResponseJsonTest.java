package com.srm.core.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.srm.core.dto.auditlog.AuditLogResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约锁定：AuditLogResponse 全部字段显式 @JsonProperty 下划线格式，
 * 且不依赖全局 SNAKE_CASE 配置（裸 ObjectMapper 也输出 snake_case）。
 */
class AuditLogResponseJsonTest {

    @Test
    void allFieldsSerializeAsSnakeCase() throws Exception {
        ObjectMapper plainMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        AuditLogResponse response = AuditLogResponse.builder()
                .id(1L).entityType("SUPPLIER").entityId(9L)
                .operatorId(3L).operatorName("审核员李四").operatorRole("AUDITOR")
                .action("AUDIT_APPROVE").oldStatus("PENDING_AUDIT").newStatus("APPROVED")
                .result("SUCCESS").comment("通过")
                .createdAt(LocalDateTime.of(2026, 10, 8, 12, 0))
                .build();

        String json = plainMapper.writeValueAsString(response);

        assertThat(json)
                .contains("\"entity_type\":\"SUPPLIER\"", "\"entity_id\":9",
                        "\"operator_id\":3", "\"operator_name\":\"审核员李四\"",
                        "\"operator_role\":\"AUDITOR\"",
                        "\"old_status\":\"PENDING_AUDIT\"", "\"new_status\":\"APPROVED\"",
                        "\"created_at\":")
                .doesNotContain("entityType", "entityId", "operatorId", "operatorName",
                        "operatorRole", "oldStatus", "newStatus", "createdAt");
    }

    @Test
    void auditEnumsCoverContractValues() {
        // 契约源：docs/api-spec.md §0「目标态 DDL 增量」③ + 第 5 节 entity_type（现 5 个值）。
        // 裁决 20261010（api-spec §4.1）：一步式评价不设草稿，PERFORMANCE_EVALUATION_DRAFT 已移除。
        // 裁决 20261012（api-spec §0③）：增 SUPPLIER_QUALIFICATION，排在既有四值之后。
        assertThat(EntityType.values()).extracting(Enum::name).containsExactly(
                "SUPPLIER", "PERFORMANCE_EVALUATION", "LIFECYCLE_REQUEST", "USER",
                "SUPPLIER_QUALIFICATION");
        assertThat(EntityType.values()).hasSize(5);
        // 新增值的 name() 就是落库/过滤用的 wire 值，必须与契约字符串逐字一致（大小写敏感）
        assertThat(EntityType.valueOf("SUPPLIER_QUALIFICATION").name()).isEqualTo("SUPPLIER_QUALIFICATION");
        assertThat(AuditResult.values()).extracting(Enum::name).containsExactly("SUCCESS", "REJECTED");
        assertThat(AuditAction.values()).extracting(Enum::name).containsExactly(
                "SUBMIT", "AUDIT_APPROVE", "AUDIT_REJECT", "REVIEW_APPROVE", "REVIEW_REJECT",
                "DECISION_APPROVE", "DECISION_REJECT",
                "CREATE_USER", "UPDATE_USER", "ENABLE_USER", "DISABLE_USER");
    }
}

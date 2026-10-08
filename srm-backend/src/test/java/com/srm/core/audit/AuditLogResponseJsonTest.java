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
        assertThat(EntityType.values()).extracting(Enum::name).containsExactly(
                "SUPPLIER", "PERFORMANCE_EVALUATION", "PERFORMANCE_EVALUATION_DRAFT", "LIFECYCLE_REQUEST");
        assertThat(AuditResult.values()).extracting(Enum::name).containsExactly("SUCCESS", "REJECTED");
        assertThat(AuditAction.values()).extracting(Enum::name).containsExactly(
                "SUBMIT", "AUDIT_APPROVE", "AUDIT_REJECT", "REVIEW_APPROVE", "REVIEW_REJECT",
                "DECISION_APPROVE", "DECISION_REJECT");
    }
}

package com.srm.core.dto.supplier;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.util.Map;

/**
 * 供应商档案导出行模型（GET /api/v1/suppliers/export，xlsx 一行一家供应商）。
 *
 * 口径（需求定稿 2026-10-16）：
 * - 字段<b>全部来源 SupplierResponse（附录 A1）业务可见字段</b>，不新增任何物理字段、不改表；
 *   有意排除的三枚：{@code version}/{@code created_by}（并发/内部 ID，无业务阅读价值，
 *   创建人已用 {@code created_by_name} 呈现）、{@code business_license_url}（派生归二期恒 null）。
 * - <b>码值→中文在后端完成</b>（本类是唯一转换点）：状态六态、类型三码、风险标 Boolean→是/否。
 *   中文文案与前端字典（auditDictionary.ts 的 SUPPLIER_STATUS_LABEL / SUPPLIER_TYPE_LABEL_CN）
 *   逐字同源，兜底口径一致：<b>未知码原样回显、null 落空串</b>，绝不抛错吞行。
 * - 列序由 {@code @ExcelProperty(index)} 显式钉死，与前端列表页业务可读列对齐；
 *   日期时间沿用 SupplierResponse 的 String 化口径（yyyy-MM-dd / ISO 本地日期时间）。
 */
@Data
public class SupplierExportRow {

    /** 供应商六态中文（api-spec §状态机 L99 口径，前端 SUPPLIER_STATUS_LABEL 同源） */
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "DRAFT", "草稿/待提交",
            "PENDING_REVIEW", "待审核",
            "NORMAL", "正常/合作中",
            "RETURNED", "待修改/已驳回",
            "SUSPENDED", "停用",
            "ELIMINATED", "淘汰");

    /** 供应商类型三码中文（Supplier 实体注释码表，前端 SUPPLIER_TYPE_LABEL_CN 同源） */
    private static final Map<String, String> TYPE_LABELS = Map.of(
            "MANUFACTURING", "生产制造业",
            "SERVICE_OUTSOURCING", "服务外包商",
            "DISTRIBUTOR", "经销商或代理商");

    @ExcelProperty(value = "供应商ID", index = 0)
    @ColumnWidth(12)
    private Long id;

    @ExcelProperty(value = "供应商名称", index = 1)
    @ColumnWidth(30)
    private String name;

    @ExcelProperty(value = "统一社会信用代码", index = 2)
    @ColumnWidth(24)
    private String taxNo;

    /** 已转中文的类型标签（原始码见 SupplierResponse.type） */
    @ExcelProperty(value = "供应商类型", index = 3)
    @ColumnWidth(16)
    private String type;

    @ExcelProperty(value = "联系人", index = 4)
    @ColumnWidth(12)
    private String contactName;

    @ExcelProperty(value = "联系电话", index = 5)
    @ColumnWidth(14)
    private String contactPhone;

    @ExcelProperty(value = "联系邮箱", index = 6)
    @ColumnWidth(22)
    private String contactEmail;

    @ExcelProperty(value = "地址", index = 7)
    @ColumnWidth(24)
    private String address;

    @ExcelProperty(value = "备注", index = 8)
    @ColumnWidth(24)
    private String remark;

    @ExcelProperty(value = "合作生效日期", index = 9)
    @ColumnWidth(14)
    private String effectiveDate;

    @ExcelProperty(value = "合作到期日期", index = 10)
    @ColumnWidth(14)
    private String expiryDate;

    /** 已转中文的状态标签（原始码见 SupplierResponse.status） */
    @ExcelProperty(value = "状态", index = 11)
    @ColumnWidth(14)
    private String status;

    /** 归档列存量透出（裁决 2026-10-16 同口径）；未归档落空串 */
    @ExcelProperty(value = "最新绩效评级", index = 12)
    @ColumnWidth(14)
    private String latestPerformanceGrade;

    /** 风险预警三态：true→是 / false→否 / null（历史未计算）→空串 */
    @ExcelProperty(value = "风险预警", index = 13)
    @ColumnWidth(10)
    private String riskWarning;

    @ExcelProperty(value = "创建人", index = 14)
    @ColumnWidth(14)
    private String createdByName;

    @ExcelProperty(value = "创建时间", index = 15)
    @ColumnWidth(20)
    private String createdAt;

    @ExcelProperty(value = "更新时间", index = 16)
    @ColumnWidth(20)
    private String updatedAt;

    /** 唯一装配入口：SupplierResponse → 导出行。转换只碰展示口径，不产生新事实。 */
    public static SupplierExportRow from(SupplierResponse response) {
        SupplierExportRow row = new SupplierExportRow();
        row.setId(response.getId());
        row.setName(nullToEmpty(response.getName()));
        row.setTaxNo(nullToEmpty(response.getTaxNo()));
        row.setType(typeLabel(response.getType()));
        row.setContactName(nullToEmpty(response.getContactName()));
        row.setContactPhone(nullToEmpty(response.getContactPhone()));
        row.setContactEmail(nullToEmpty(response.getContactEmail()));
        row.setAddress(nullToEmpty(response.getAddress()));
        row.setRemark(nullToEmpty(response.getRemark()));
        row.setEffectiveDate(nullToEmpty(response.getEffectiveDate()));
        row.setExpiryDate(nullToEmpty(response.getExpiryDate()));
        row.setStatus(statusLabel(response.getStatus()));
        row.setLatestPerformanceGrade(nullToEmpty(response.getLatestPerformanceGrade()));
        row.setRiskWarning(riskLabel(response.getRiskWarning()));
        row.setCreatedByName(nullToEmpty(response.getCreatedByName()));
        row.setCreatedAt(nullToEmpty(response.getCreatedAt()));
        row.setUpdatedAt(nullToEmpty(response.getUpdatedAt()));
        return row;
    }

    /** 状态码→中文；null 落空串、未知码原样回显（与前端字典兜底一致） */
    public static String statusLabel(String code) {
        return code == null ? "" : STATUS_LABELS.getOrDefault(code, code);
    }

    /** 类型码→中文；兜底口径同 statusLabel */
    public static String typeLabel(String code) {
        return code == null ? "" : TYPE_LABELS.getOrDefault(code, code);
    }

    /** 风险标三态；null（从未计算）与 false（计算过且无风险）语义不同，分别落空串与"否" */
    public static String riskLabel(Boolean flag) {
        if (flag == null) {
            return "";
        }
        return flag ? "是" : "否";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

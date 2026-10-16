package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.supplier.SupplierExportRow;
import com.srm.core.dto.supplier.SupplierResponse;
import com.srm.core.entity.Supplier;
import com.srm.core.entity.SupplierQualification;
import com.srm.core.entity.User;
import com.srm.core.mapper.SupplierMapper;
import com.srm.core.mapper.SupplierQualificationMapper;
import com.srm.core.mapper.UserMapper;
import com.srm.core.security.CurrentUserProvider;
import com.srm.core.security.RoleGuard;
import com.srm.core.security.UserPrincipal;
import com.srm.core.service.AuditLogService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GET /api/v1/suppliers/export 服务层导出查询单元测试（新增能力，需求定稿 2026-10-16）。
 *
 * 锁定四条硬行为（其中 ①② 是本能力的红线验收点）：
 * 1. <b>§0 行级隔离与 §1.3 list 完全同源</b>：STAFF 导出 wrapper 与同参 list wrapper 的
 *    SQL 段与绑定参数<b>逐字节相等</b>——导出不得另起一套绕过隔离的查询；
 * 2. 隔离条件下推：status/keyword 过滤拼进 SQL（非内存过滤），AUDITOR/ADMIN 全量（裁决 2026-10-14 晚）；
 * 3. 行数上限 5000：查询侧 Page(1, 5000) 且 searchCount=false（防 COUNT 开销），
 *    结果侧再设一道防御性截断（分页插件万一失效也不越界）；
 * 4. 码值→中文在后端完成：六态/三类型/风险标全部转中文，未知码回显原值、空值落空串，
 *    行字段全部来自 SupplierResponse（不新增物理字段口径）。
 *
 * 只读能力：导出全程不得触碰 AuditLog（与 §2.5 只读列表同口径），用例显式验证。
 */
@ExtendWith(MockitoExtension.class)
class SupplierServiceImplExportTest {

    @Mock
    private SupplierMapper supplierMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private SupplierQualificationMapper qualificationMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    private SupplierServiceImpl service;

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Supplier.class);
    }

    @BeforeEach
    void setUp() {
        service = new SupplierServiceImpl(supplierMapper, userMapper, qualificationMapper,
                auditLogService, new RoleGuard(currentUserProvider), currentUserProvider);
    }

    // ==================== 夹具 ====================

    private static UserPrincipal staff() {
        return new UserPrincipal(2L, "staff01", null, "张三", Role.STAFF, true);
    }

    private static UserPrincipal auditor() {
        return new UserPrincipal(3L, "auditor01", null, "李四", Role.AUDITOR, true);
    }

    private static Supplier existing(Long id, String status, String type, Long createdBy) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setName("杭州之江电镀有限公司");
        s.setTaxNo("91330100MA27XXXX8X");
        s.setType(type);
        s.setContactName("王强");
        s.setContactPhone("13800138000");
        s.setContactEmail("wang@example.com");
        s.setAddress("杭州市西湖区");
        s.setRemark("测试备注");
        s.setEffectiveDate(LocalDate.of(2026, 1, 1));
        s.setExpiryDate(LocalDate.of(2028, 12, 31));
        s.setStatus(status);
        s.setCreatedBy(createdBy);
        s.setVersion(2);
        s.setCreatedAt(LocalDateTime.of(2026, 10, 1, 9, 30));
        s.setUpdatedAt(LocalDateTime.of(2026, 10, 2, 10, 0));
        s.setDeleted(0);
        return s;
    }

    private static User zhangSan() {
        User u = new User();
        u.setId(2L);
        u.setUsername("staff01");
        u.setRealName("张三");
        u.setRole("STAFF");
        u.setEnabled(true);
        return u;
    }

    private Page<Supplier> pageOf(long current, long size, long total, List<Supplier> records) {
        Page<Supplier> p = new Page<>(current, size);
        p.setRecords(records);
        p.setTotal(total);
        return p;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<LambdaQueryWrapper<Supplier>> capturedListWrappers(int expectedInvocations) {
        ArgumentCaptor<Wrapper<Supplier>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(supplierMapper, times(expectedInvocations)).selectPage(any(), captor.capture());
        return captor.getAllValues().stream()
                .map(w -> (LambdaQueryWrapper<Supplier>) w)
                .collect(Collectors.toList());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Page<Supplier>> capturedPages(int expectedInvocations) {
        // searchCount 只在 Page 实现类上（IPage 接口无此方法），selectPage 形参声明为 IPage，捕获后窄化
        ArgumentCaptor<IPage<Supplier>> captor = ArgumentCaptor.forClass((Class) IPage.class);
        verify(supplierMapper, times(expectedInvocations)).selectPage(captor.capture(), any());
        return captor.getAllValues().stream()
                .map(p -> (Page<Supplier>) p)
                .collect(Collectors.toList());
    }

    // ==================== 红线①：隔离与 list 完全同源 ====================

    @Test
    void exportRowsForStaffReuseIsolationWrapperVerbatimIdenticalToList() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 20, 0, List.of()));

        service.list("NORMAL", "杭州", 1, 20);
        service.exportRows("NORMAL", "杭州");

        List<LambdaQueryWrapper<Supplier>> wrappers = capturedListWrappers(2);
        LambdaQueryWrapper<Supplier> listWrapper = wrappers.get(0);
        LambdaQueryWrapper<Supplier> exportWrapper = wrappers.get(1);
        // 同参下两条路径的查询条件必须逐字节一致：隔离只有一个事实源，导出不得旁路
        assertThat(exportWrapper.getSqlSegment()).isEqualTo(listWrapper.getSqlSegment());
        assertThat(exportWrapper.getParamNameValuePairs()).isEqualTo(listWrapper.getParamNameValuePairs());
        // 且该同源条件确实是 §0 规则 A：公有区（status NOT IN DRAFT/RETURNED）∪ 本人 created_by
        assertThat(exportWrapper.getSqlSegment())
                .contains("status NOT IN")
                .contains("created_by =");
        assertThat(exportWrapper.getParamNameValuePairs().containsValue(2L)).isTrue();
    }

    @Test
    void exportRowsForStaffKeepsRuleAWhenNoFilters() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 5000, 0, List.of()));

        service.exportRows(null, null);

        LambdaQueryWrapper<Supplier> wrapper = capturedListWrappers(1).get(0);
        String sqlSegment = wrapper.getSqlSegment();
        assertThat(sqlSegment).contains("status NOT IN").contains("created_by =");
        assertThat(wrapper.getParamNameValuePairs().containsValue(2L)).isTrue();
    }

    @Test
    void exportRowsForAuditorOmitsIsolationConditionLikeList() {
        // 用户裁决 2026-10-14 晚：AUDITOR 不受 §0 规则 A 约束，导出与 §1.3 同口径全量
        when(currentUserProvider.require()).thenReturn(auditor());
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 5000, 0, List.of()));

        service.exportRows(null, null);

        assertThat(capturedListWrappers(1).get(0).getSqlSegment()).doesNotContain("created_by");
    }

    @Test
    void exportRowsPushesStatusAndKeywordFiltersDownToSql() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 5000, 0, List.of()));

        service.exportRows("NORMAL", "杭州");

        LambdaQueryWrapper<Supplier> wrapper = capturedListWrappers(1).get(0);
        String sqlSegment = wrapper.getSqlSegment();
        Map<String, Object> pairs = wrapper.getParamNameValuePairs();
        assertThat(sqlSegment)
                .contains("status =")
                .contains("name LIKE")
                .contains("tax_no LIKE")
                .doesNotContain("'NORMAL'")
                .doesNotContain("杭州");
        assertThat(pairs.containsValue("NORMAL")).isTrue();
        assertThat(pairs.containsValue("%杭州%")).isTrue();
    }

    // ==================== 红线③：5000 行上限 ====================

    @Test
    void exportRowsQueryCappedAtFiveThousandFirstPageWithoutCount() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 5000, 0, List.of()));

        service.exportRows(null, null);

        Page<Supplier> page = capturedPages(1).get(0);
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(5000);
        // 导出只要前 5000 行，COUNT 全表是纯浪费
        assertThat(page.searchCount()).isFalse();
    }

    @Test
    void exportRowsDefensivelyTruncatesOversizedResultToFiveThousand() {
        // 分页插件万一失效（方言/配置漂移）时，服务层自身仍必须截断到 5000，绝不整表进内存/文件
        when(currentUserProvider.require()).thenReturn(auditor());
        List<Supplier> oversized = IntStream.rangeClosed(1, 5002)
                .mapToObj(i -> existing((long) i, "NORMAL", "DISTRIBUTOR", 2L))
                .collect(Collectors.toList());
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 5000, 6000, oversized));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of());

        List<SupplierExportRow> rows = service.exportRows(null, null);

        assertThat(rows).hasSize(5000);
    }

    // ==================== 红线②：码值→中文在后端完成，字段源自 SupplierResponse ====================

    @Test
    void exportRowsTranslatesSixStatusAndThreeTypeCodesToChinese() {
        when(currentUserProvider.require()).thenReturn(auditor());
        List<Supplier> records = List.of(
                existing(1L, "DRAFT", "MANUFACTURING", 2L),
                existing(2L, "PENDING_REVIEW", "SERVICE_OUTSOURCING", 2L),
                existing(3L, "NORMAL", "DISTRIBUTOR", 2L),
                existing(4L, "RETURNED", "MANUFACTURING", 2L),
                existing(5L, "SUSPENDED", "SERVICE_OUTSOURCING", 2L),
                existing(6L, "ELIMINATED", "DISTRIBUTOR", 2L));
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 5000, 6, records));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(zhangSan()));

        List<SupplierExportRow> rows = service.exportRows(null, null);

        assertThat(rows).extracting(SupplierExportRow::getStatus).containsExactly(
                "草稿/待提交", "待审核", "正常/合作中", "待修改/已驳回", "停用", "淘汰");
        assertThat(rows).extracting(SupplierExportRow::getType).containsExactly(
                "生产制造业", "服务外包商", "经销商或代理商",
                "生产制造业", "服务外包商", "经销商或代理商");
    }

    @Test
    void exportRowsPassesThroughUnknownCodesAndBlankForNullFlags() {
        // 未知码回显原值、NULL 风险标/评级落空串：与前端字典兜底口径一致，不报错不吞行
        when(currentUserProvider.require()).thenReturn(auditor());
        Supplier legacy = existing(9L, "LEGACY_STATE", "OTHER_KIND", 2L);
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 5000, 1, List.of(legacy)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(zhangSan()));

        List<SupplierExportRow> rows = service.exportRows(null, null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo("LEGACY_STATE");
        assertThat(rows.get(0).getType()).isEqualTo("OTHER_KIND");
        assertThat(rows.get(0).getLatestPerformanceGrade()).isEmpty();
        assertThat(rows.get(0).getRiskWarning()).isEmpty();
    }

    @Test
    void exportRowsCarriesGradeAndRiskYesNoFromArchivedColumns() {
        when(currentUserProvider.require()).thenReturn(auditor());
        Supplier archived = existing(8L, "NORMAL", "MANUFACTURING", 2L);
        archived.setLatestPerformanceGrade("C");
        archived.setRiskWarning(true);
        Supplier safe = existing(7L, "NORMAL", "MANUFACTURING", 2L);
        safe.setLatestPerformanceGrade("A");
        safe.setRiskWarning(false);
        when(supplierMapper.selectPage(any(), any()))
                .thenReturn(pageOf(1, 5000, 2, List.of(archived, safe)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(zhangSan()));

        List<SupplierExportRow> rows = service.exportRows(null, null);

        assertThat(rows.get(0).getLatestPerformanceGrade()).isEqualTo("C");
        assertThat(rows.get(0).getRiskWarning()).isEqualTo("是");
        assertThat(rows.get(1).getLatestPerformanceGrade()).isEqualTo("A");
        assertThat(rows.get(1).getRiskWarning()).isEqualTo("否");
    }

    @Test
    void exportRowsMapBusinessVisibleFieldsSourcedFromSupplierResponse() {
        when(currentUserProvider.require()).thenReturn(auditor());
        Supplier row = existing(100L, "NORMAL", "MANUFACTURING", 2L);
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 5000, 1, List.of(row)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(zhangSan()));

        List<SupplierExportRow> rows = service.exportRows(null, null);

        SupplierExportRow exported = rows.get(0);
        assertThat(exported.getId()).isEqualTo(100L);
        assertThat(exported.getName()).isEqualTo("杭州之江电镀有限公司");
        assertThat(exported.getTaxNo()).isEqualTo("91330100MA27XXXX8X");
        assertThat(exported.getContactName()).isEqualTo("王强");
        assertThat(exported.getContactPhone()).isEqualTo("13800138000");
        assertThat(exported.getContactEmail()).isEqualTo("wang@example.com");
        assertThat(exported.getAddress()).isEqualTo("杭州市西湖区");
        assertThat(exported.getRemark()).isEqualTo("测试备注");
        assertThat(exported.getEffectiveDate()).isEqualTo("2026-01-01");
        assertThat(exported.getExpiryDate()).isEqualTo("2028-12-31");
        assertThat(exported.getCreatedByName()).isEqualTo("张三");
        assertThat(exported.getCreatedAt()).isEqualTo("2026-10-01T09:30:00");
        assertThat(exported.getUpdatedAt()).isEqualTo("2026-10-02T10:00:00");
    }

    // ==================== 只读口径：空结果与审计零触达 ====================

    @Test
    void exportRowsEmptyResultSkipsUserLookupAndWritesNoAudit() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 5000, 0, List.of()));

        List<SupplierExportRow> rows = service.exportRows(null, null);

        assertThat(rows).isEmpty();
        verify(userMapper, never()).selectBatchIds(any());
        // 只读导出与 §2.5 只读列表同口径：绝不写 AuditLog
        verifyNoInteractions(auditLogService);
    }

    @Test
    void exportRowsKeepsEmptyCreatorNameFallbackForDirtyRows() {
        when(currentUserProvider.require()).thenReturn(auditor());
        Supplier dirty = existing(11L, "NORMAL", "DISTRIBUTOR", null);
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 5000, 1, List.of(dirty)));

        List<SupplierExportRow> rows = service.exportRows(null, null);

        assertThat(rows.get(0).getCreatedByName()).isEmpty();
        verify(userMapper, never()).selectBatchIds(any());
    }
}

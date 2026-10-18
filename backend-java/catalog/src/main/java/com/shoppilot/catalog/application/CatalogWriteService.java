package com.shoppilot.catalog.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.catalog.application.dto.ProductView;
import com.shoppilot.catalog.application.dto.SkuView;
import com.shoppilot.catalog.application.dto.SupplierView;
import com.shoppilot.catalog.application.dto.WarehouseView;
import com.shoppilot.catalog.application.support.CatalogGuards;
import com.shoppilot.catalog.application.support.CatalogJson;
import com.shoppilot.catalog.infrastructure.persistence.entity.ProductEntity;
import com.shoppilot.catalog.infrastructure.persistence.entity.SkuEntity;
import com.shoppilot.catalog.infrastructure.persistence.entity.SupplierEntity;
import com.shoppilot.catalog.infrastructure.persistence.entity.WarehouseEntity;
import com.shoppilot.catalog.infrastructure.persistence.mapper.ProductMapper;
import com.shoppilot.catalog.infrastructure.persistence.mapper.SkuMapper;
import com.shoppilot.catalog.infrastructure.persistence.mapper.SupplierMapper;
import com.shoppilot.catalog.infrastructure.persistence.mapper.WarehouseMapper;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.tenant.TeamScopeValidator;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * catalog 写入服务——主数据写操作的<b>唯一入口</b>（{@code MODULE_BOUNDARIES §2.3}）。
 *
 * <h2>POST 与 PATCH 都经此、按 id 有无分流</h2>
 * {@code upsertXxx(id, body)}：{@code id == null} → 创建（POST）；{@code id != null} → <b>真·部分更新</b>（PATCH）。
 * Controller 不直接装配实体，全部写路径收敛于此，保证团队注入、关联校验、错误码映射口径一致。
 *
 * <h2>三条约束红线</h2>
 * <ol>
 *   <li><b>{@code team_id} 只来自服务端</b>：一律 {@link TeamContext} 注入，客户端传的 {@code team_id} 被忽略
 *       （{@code API_CONTRACT §5.3} / {@code DESIGN_BASIS §6} 规则 3）；INSERT 显式 set 双保险。</li>
 *   <li><b>关联对象同团队</b>：{@code default_supplier_id}/{@code supplier_id} 不存在或跨团队 → {@code CAT-3005}
 *       （{@code §6} 规则 4）。{@code skus.product_id} 须存在且同团队 → 否则 {@code CAT-3001}；路径/请求体
 *       {@code product_id} 不一致 → {@code CAT-3004}。</li>
 *   <li><b>PATCH 部分更新</b>：只把「传来的字段」写进 SET（{@code JsonNode.hasKey} 判存在），
 *       绝不整行覆盖 / 把未传字段 null 掉；请求体中的 {@code status} 被<b>忽略</b>
 *       （状态流转属 M2-T10，不得经 PATCH 绕过前置校验）。</li>
 * </ol>
 *
 * <p>唯一约束冲突（{@code uk_*_team_code}）由 {@code DuplicateKeyException} 全局翻译为 {@code 409 CAT-3002}
 * （见 {@code GlobalExceptionHandler}），不泄露为 500。
 *
 * <h2>审计埋点（M2-T11，{@code DATABASE_DESIGN §6-J.1} #6）</h2>
 * {@code DESIGN_BASIS §9.17}：{@code MASTER_DATA_STATUS_CHANGE} <b>覆盖</b>主数据的创建/更新/停用/启用四类，
 * 以 {@code biz_type}（{@code WAREHOUSE}/{@code SUPPLIER}/{@code PRODUCT}/{@code SKU}）区分。分工：
 * <ul>
 *   <li><b>创建（POST）与启停（DELETE / PATCH status）</b>：在本类内<b>显式</b> {@code record()}——
 *       这两类需要 {@code biz_no}（编号快照）与准确的操作摘要，只有拿到实体的本类能给；</li>
 *   <li><b>更新（PATCH /{id}）</b>：由 Controller 上的 {@code @Audited} 切面记录
 *       （{@code biz_id} 取自路径 {@code {id}}；切面不读业务表，故不填 {@code biz_no}）。</li>
 * </ul>
 */
@Service
public class CatalogWriteService {

    private static final Logger log = LoggerFactory.getLogger(CatalogWriteService.class);

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";

    /** 审计业务对象类型（{@code DATABASE_DESIGN §6-J.1} #6，与表名大写一致）。 */
    private static final String BIZ_TYPE_WAREHOUSE = "WAREHOUSE";
    private static final String BIZ_TYPE_SUPPLIER = "SUPPLIER";
    private static final String BIZ_TYPE_PRODUCT = "PRODUCT";
    private static final String BIZ_TYPE_SKU = "SKU";

    private final WarehouseMapper warehouseMapper;
    private final SupplierMapper supplierMapper;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final SkuQueryService skuQueryService;
    private final AuditWriteService auditWriteService;

    public CatalogWriteService(WarehouseMapper warehouseMapper,
                               SupplierMapper supplierMapper,
                               ProductMapper productMapper,
                               SkuMapper skuMapper,
                               SkuQueryService skuQueryService,
                               AuditWriteService auditWriteService) {
        this.warehouseMapper = warehouseMapper;
        this.supplierMapper = supplierMapper;
        this.productMapper = productMapper;
        this.skuMapper = skuMapper;
        this.skuQueryService = skuQueryService;
        this.auditWriteService = auditWriteService;
    }

    // =====================================================================
    // 仓库
    // =====================================================================

    /** 新建（{@code id==null}）/ 修改（{@code id!=null}）仓库。 */
    @Transactional(rollbackFor = Exception.class)
    public WarehouseView upsertWarehouse(Long id, JsonNode body) {
        return id == null ? createWarehouse(body) : updateWarehouse(id, body, true);
    }

    private WarehouseView createWarehouse(JsonNode body) {
        return createWarehouse(body, true);
    }

    /**
     * 新建仓库的<b>内核</b>（{@code B4-PERF}：把「写后重查视图」变为可选）。
     *
     * <h2>为什么 {@code withView=false} 能省一次往返</h2>
     * 旧实现固定 {@code WarehouseView.of(warehouseMapper.selectById(id))}：INSERT 之后再发一条
     * {@code SELECT} 仅仅为了拿到 DB 侧默认值（{@code created_at}）。而<b>导入落库</b>只用返回值的
     * {@code id}/{@code status}（判断要不要 {@code changeStatus}），根本不需要这条重查——
     * 实测单次 {@code selectById} 约 2.6 ms（{@code target/import-perf-profile.txt}），
     * 1 万行就是 26 s。故导入路径传 {@code false}，用刚插入的实体直接映射；
     * REST 路径（{@code upsertWarehouse}）保持 {@code true}，响应体字段<b>逐字节不变</b>。
     *
     * <p>⚠️ {@code withView=false} 时返回的视图里 {@code created_at} 为 {@code null}
     * （该值由 DB 默认值生成）——调用方<b>不得</b>把它直接回给客户端。
     *
     * @param withView {@code true} = 写后重查（REST 语义完整）；{@code false} = 用插入实体映射（导入路径）
     */
    private WarehouseView createWarehouse(JsonNode body, boolean withView) {
        requireAllNonBlank(body, "code", "name");
        long teamId = currentTeamId();
        WarehouseEntity entity = new WarehouseEntity();
        entity.setTeamId(teamId);
        entity.setCode(CatalogJson.text(body, "code"));
        entity.setName(CatalogJson.text(body, "name"));
        entity.setStatus(STATUS_ACTIVE);
        entity.setAddress(CatalogJson.text(body, "address"));
        entity.setContactName(CatalogJson.text(body, "contact_name"));
        entity.setContactPhone(CatalogJson.text(body, "contact_phone"));
        entity.setRemark(CatalogJson.text(body, "remark"));
        if (Boolean.TRUE.equals(CatalogJson.boolVal(body, "is_default"))) {
            clearOtherDefaults(null);
            entity.setIsDefault(true);
        } else {
            entity.setIsDefault(false);
        }
        stampCreate(entity);
        warehouseMapper.insert(entity);
        log.info("仓库已创建 teamId={} id={} code={}", teamId, entity.getId(), entity.getCode());
        recordMasterData(BIZ_TYPE_WAREHOUSE, entity.getId(), entity.getCode(), "创建仓库");
        return withView ? WarehouseView.of(warehouseMapper.selectById(entity.getId())) : WarehouseView.of(entity);
    }

    /**
     * 更新仓库内核。
     *
     * <h2>★ {@code R86}：{@code code} 是定位键，能否被改写由<b>调用方</b>显式决定</h2>
     * 本方法被 REST（{@link #upsertWarehouse}）与导入 by-code upsert
     * （{@link #doUpsertWarehouseByCode}）<b>共用</b>，而两者的 {@code code} 语义不同：
     * <ul>
     *   <li><b>REST</b>：{@code code} 是普通业务字段，可改（{@code API_CONTRACT §10.3} 未禁止，
     *       {@code DATABASE_DESIGN §5.4}「更新规则」也未标 {@code 不可变}——对比同文件
     *       {@code teams.code} 与 {@code skus.product_id} 都明确标了）⇒ 传 {@code writableCode=true}；</li>
     *   <li><b>导入</b>：{@code code} 只是<b>定位键</b>（{@code IMPORT_WORKFLOW §7.4} R86 裁定），
     *       命中既有行后必须<b>保留库内既有字面量</b>，不得改写成文件里的写法
     *       ⇒ 传 {@code writableCode=false}。</li>
     * </ul>
     * ★ 之所以做成<b>显式参数</b>而不是在导入路径上打标记（ThreadLocal / 请求头开关）：
     * 那是「同一内核两种语义」的必要表达点，参数让差异在调用处可见、可测、可被静态检视。
     *
     * @param writableCode 是否允许把请求体里的 {@code code} 写进库内（见上）
     */
    private WarehouseView updateWarehouse(Long id, JsonNode body, boolean writableCode) {
        CatalogGuards.requireInTeam(warehouseMapper.selectById(id),
                () -> warehouseMapper.selectTeamIdById(id), ErrorCodes.CAT_3003, "仓库不存在");

        requireNonBlankIfPresent(body, "code");
        requireNonBlankIfPresent(body, "name");
        UpdateWrapper<WarehouseEntity> w = new UpdateWrapper<>();
        w.eq("id", id);
        if (writableCode) {
            setString(w, body, "code", "code");
        }
        setString(w, body, "name", "name");
        setString(w, body, "address", "address");
        setString(w, body, "contact_name", "contact_name");
        setString(w, body, "contact_phone", "contact_phone");
        setString(w, body, "remark", "remark");
        if (CatalogJson.hasKey(body, "is_default")) {
            if (Boolean.TRUE.equals(CatalogJson.boolVal(body, "is_default"))) {
                clearOtherDefaults(id);
                w.set("is_default", true);
            } else {
                w.set("is_default", false);
            }
        }
        w.set("updated_by", currentUserId());
        warehouseMapper.update(null, w);
        log.info("仓库已更新 id={}", id);
        return WarehouseView.of(warehouseMapper.selectById(id));
    }

    /** 同团队默认仓唯一：把其它行的 {@code is_default} 清 0（{@code DATABASE_DESIGN §5.4} 设计要点）。 */
    private void clearOtherDefaults(Long exceptId) {
        UpdateWrapper<WarehouseEntity> w = new UpdateWrapper<>();
        w.eq("is_default", true).set("is_default", false);
        if (exceptId != null) {
            w.ne("id", exceptId);
        }
        warehouseMapper.update(null, w);
    }

    // =====================================================================
    // 供应商
    // =====================================================================

    /** 新建（{@code id==null}）/ 修改（{@code id!=null}）供应商。 */
    @Transactional(rollbackFor = Exception.class)
    public SupplierView upsertSupplier(Long id, JsonNode body) {
        return id == null ? createSupplier(body) : updateSupplier(id, body, true);
    }

    private SupplierView createSupplier(JsonNode body) {
        return createSupplier(body, true);
    }

    /** 新建供应商内核；{@code withView=false} 省掉写后重查（语义见 {@link #createWarehouse(JsonNode, boolean)}）。 */
    private SupplierView createSupplier(JsonNode body, boolean withView) {
        requireAllNonBlank(body, "code", "name");
        Integer leadTimeDays = CatalogJson.intVal(body, "lead_time_days");
        BigDecimal orderMultiple = CatalogJson.decimal(body, "order_multiple");
        validateSupplierConfig(leadTimeDays, orderMultiple);

        SupplierEntity entity = new SupplierEntity();
        entity.setTeamId(currentTeamId());
        entity.setCode(CatalogJson.text(body, "code"));
        entity.setName(CatalogJson.text(body, "name"));
        entity.setStatus(STATUS_ACTIVE);
        entity.setContactName(CatalogJson.text(body, "contact_name"));
        entity.setContactPhone(CatalogJson.text(body, "contact_phone"));
        entity.setEmail(CatalogJson.text(body, "email"));
        entity.setAddress(CatalogJson.text(body, "address"));
        entity.setLeadTimeDays(leadTimeDays);
        entity.setMoq(CatalogJson.decimal(body, "moq"));
        entity.setOrderMultiple(orderMultiple);
        entity.setPaymentTerms(CatalogJson.text(body, "payment_terms"));
        entity.setRemark(CatalogJson.text(body, "remark"));
        stampCreate(entity);
        supplierMapper.insert(entity);
        log.info("供应商已创建 teamId={} id={} code={}", entity.getTeamId(), entity.getId(), entity.getCode());
        recordMasterData(BIZ_TYPE_SUPPLIER, entity.getId(), entity.getCode(), "创建供应商");
        return withView ? SupplierView.of(supplierMapper.selectById(entity.getId())) : SupplierView.of(entity);
    }

    /**
     * 更新供应商内核。
     *
     * <p>★ {@code R86}：{@code code} 是 by-code upsert 的<b>定位键、不可更新</b>
     * （{@code IMPORT_WORKFLOW §7.3}）；REST 管理端仍可改（{@code API_CONTRACT §10.3} 未禁止，
     * {@code DATABASE_DESIGN §5.5}「更新规则」未标 {@code 不可变}）⇒ 由调用方显式传
     * {@code writableCode}，理由与取舍见 {@link #updateWarehouse(Long, JsonNode, boolean)}。
     */
    private SupplierView updateSupplier(Long id, JsonNode body, boolean writableCode) {
        CatalogGuards.requireInTeam(supplierMapper.selectById(id),
                () -> supplierMapper.selectTeamIdById(id), ErrorCodes.CAT_3001, "供应商不存在");

        requireNonBlankIfPresent(body, "code");
        requireNonBlankIfPresent(body, "name");
        // 交期 / 采购倍数配置校验（CAT-3011）；仅对「本次传入且非 null」的字段生效
        validateSupplierConfig(
                CatalogJson.has(body, "lead_time_days") ? CatalogJson.intVal(body, "lead_time_days") : null,
                CatalogJson.has(body, "order_multiple") ? CatalogJson.decimal(body, "order_multiple") : null);

        UpdateWrapper<SupplierEntity> w = new UpdateWrapper<>();
        w.eq("id", id);
        if (writableCode) {
            setString(w, body, "code", "code");
        }
        setString(w, body, "name", "name");
        setString(w, body, "contact_name", "contact_name");
        setString(w, body, "contact_phone", "contact_phone");
        setString(w, body, "email", "email");
        setString(w, body, "address", "address");
        setInteger(w, body, "lead_time_days", "lead_time_days");
        setDecimal(w, body, "moq", "moq");
        setDecimal(w, body, "order_multiple", "order_multiple");
        setString(w, body, "payment_terms", "payment_terms");
        setString(w, body, "remark", "remark");
        w.set("updated_by", currentUserId());
        supplierMapper.update(null, w);
        log.info("供应商已更新 id={}", id);
        return SupplierView.of(supplierMapper.selectById(id));
    }

    /** {@code CAT-3011}：交期为负、采购倍数 ≤ 0（{@code ERROR_CODES §2.4}）。 */
    private static void validateSupplierConfig(Integer leadTimeDays, BigDecimal orderMultiple) {
        if (leadTimeDays != null && leadTimeDays < 0) {
            throw new BizException(ErrorCodes.CAT_3011, "供应商交期不能为负数");
        }
        if (orderMultiple != null && orderMultiple.signum() <= 0) {
            throw new BizException(ErrorCodes.CAT_3011, "供应商采购倍数必须大于 0");
        }
    }

    // =====================================================================
    // 商品
    // =====================================================================

    /** 新建（{@code id==null}）/ 修改（{@code id!=null}）商品。 */
    @Transactional(rollbackFor = Exception.class)
    public ProductView upsertProduct(Long id, JsonNode body) {
        return id == null ? createProduct(body) : updateProduct(id, body, true, true);
    }

    private ProductView createProduct(JsonNode body) {
        return createProduct(body, true);
    }

    /** 新建商品内核；{@code withView=false} 省掉写后重查（语义见 {@link #createWarehouse(JsonNode, boolean)}）。 */
    private ProductView createProduct(JsonNode body, boolean withView) {
        requireAllNonBlank(body, "product_code", "name");
        Long supplierId = CatalogJson.longVal(body, "default_supplier_id");
        assertSupplierSameTeam(supplierId, "default_supplier_id");

        ProductEntity entity = new ProductEntity();
        entity.setTeamId(currentTeamId());
        entity.setProductCode(CatalogJson.text(body, "product_code"));
        entity.setName(CatalogJson.text(body, "name"));
        entity.setCategory(CatalogJson.text(body, "category"));
        entity.setBrand(CatalogJson.text(body, "brand"));
        entity.setUnit(CatalogJson.text(body, "unit"));
        entity.setStatus(STATUS_ACTIVE);
        entity.setDefaultSupplierId(supplierId);
        entity.setSpecSummary(CatalogJson.text(body, "spec_summary"));
        entity.setRemark(CatalogJson.text(body, "remark"));
        stampCreate(entity);
        productMapper.insert(entity);
        log.info("商品已创建 teamId={} id={} productCode={}", entity.getTeamId(), entity.getId(),
                entity.getProductCode());
        recordMasterData(BIZ_TYPE_PRODUCT, entity.getId(), entity.getProductCode(), "创建商品");
        return withView ? ProductView.of(productMapper.selectById(entity.getId())) : ProductView.of(entity);
    }

    /**
     * 更新内核。
     *
     * @param withView {@code true}＝写后重查一次 {@code selectById} 取最新视图（REST 用）；
     *                 {@code false}＝复用 guard 已取到的实体（导入用，省掉 1 万次写后重查 ≈ 24 s）
     * @param writableCode ★ {@code R86}：{@code product_code} 是 by-code upsert 的<b>定位键、
     *                 不可更新</b>（{@code IMPORT_WORKFLOW §7.1}「{@code product_code} 为业务键不可改」）；
     *                 REST 管理端仍可改（{@code API_CONTRACT §10.3} 未禁止，
     *                 {@code DATABASE_DESIGN §5.6}「更新规则」未标 {@code 不可变}）。
     *                 理由与取舍见 {@link #updateWarehouse(Long, JsonNode, boolean)}。
     */
    private ProductView updateProduct(Long id, JsonNode body, boolean withView, boolean writableCode) {
        ProductEntity existing = productMapper.selectById(id);
        CatalogGuards.requireInTeam(existing,
                () -> productMapper.selectTeamIdById(id), ErrorCodes.CAT_3001, "商品不存在");

        requireNonBlankIfPresent(body, "product_code");
        requireNonBlankIfPresent(body, "name");
        UpdateWrapper<ProductEntity> w = new UpdateWrapper<>();
        w.eq("id", id);
        if (writableCode) {
            setString(w, body, "product_code", "product_code");
        }
        setString(w, body, "name", "name");
        setString(w, body, "category", "category");
        setString(w, body, "brand", "brand");
        setString(w, body, "unit", "unit");
        setString(w, body, "spec_summary", "spec_summary");
        setString(w, body, "remark", "remark");
        if (CatalogJson.hasKey(body, "default_supplier_id")) {
            Long supplierId = CatalogJson.longVal(body, "default_supplier_id");
            assertSupplierSameTeam(supplierId, "default_supplier_id");
            w.set("default_supplier_id", supplierId);
        }
        w.set("updated_by", currentUserId());
        productMapper.update(null, w);
        log.info("商品已更新 id={}", id);
        return withView ? ProductView.of(productMapper.selectById(id)) : ProductView.of(existing);
    }

    // =====================================================================
    // SKU
    // =====================================================================

    /**
     * 新建（{@code id==null}）/ 修改（{@code id!=null}）SKU。
     *
     * @param pathProductId 路径 {@code /products/{id}/skus} 携带的商品 id；独立 {@code /skus} 时为 {@code null}
     */
    @Transactional(rollbackFor = Exception.class)
    public SkuView upsertSku(Long id, Long pathProductId, JsonNode body) {
        return id == null ? createSku(pathProductId, body) : updateSku(id, body, true);
    }

    private SkuView createSku(Long pathProductId, JsonNode body) {
        requireAllNonBlank(body, "sku_code", "name");
        Long productId = resolveProductIdForCreate(pathProductId, body);
        return insertSku(productId, body);
    }

    /**
     * SKU 实体插入（创建路径的<b>共用内核</b>）。
     *
     * <p>从 {@code createSku} 抽出，供两条创建路径共用：
     * <ol>
     *   <li>REST 管理端 {@code POST}（{@code createSku}）：保留 {@code name} 必填的前置校验；</li>
     *   <li>导入落库 {@code upsertSkuByCode}：{@code IMPORT_WORKFLOW §7.2} 把 {@code name}（展示名）
     *       列为<b>非必填</b>，且 {@code skus.name} 在 {@code DATABASE_DESIGN §5.7} 中<b>可空</b>，
     *       故导入路径不施加该必填约束（否则「无 name 列的合法 SKU 模板」会在落库期整批失败）。</li>
     * </ol>
     * 两条路径的<b>其它</b>行为（团队注入、商品关联、供应商同团队、字段映射）完全一致。
     */
    private SkuView insertSku(Long productId, JsonNode body) {
        return insertSku(productId, body, true);
    }

    /** SKU 插入内核；{@code withView=false} 省掉写后重查（语义见 {@link #createWarehouse(JsonNode, boolean)}）。 */
    private SkuView insertSku(Long productId, JsonNode body, boolean withView) {
        // 关联写入：商品不存在或跨团队 → 422 CAT-3004（任务书 §3.3 / §7-6）
        assertProductAssociation(productId);

        Long supplierId = CatalogJson.longVal(body, "supplier_id");
        assertSupplierSameTeam(supplierId, "supplier_id");

        SkuEntity entity = new SkuEntity();
        entity.setTeamId(currentTeamId());
        entity.setProductId(productId);
        entity.setSkuCode(CatalogJson.text(body, "sku_code"));
        entity.setName(CatalogJson.text(body, "name"));
        entity.setBarcode(CatalogJson.text(body, "barcode"));
        entity.setSpecJson(CatalogJson.jsonText(body, "spec_json"));
        entity.setStatus(STATUS_ACTIVE);
        entity.setSupplierId(supplierId);
        entity.setDefaultLeadTimeDays(CatalogJson.intVal(body, "default_lead_time_days"));
        entity.setMoq(CatalogJson.decimal(body, "moq"));
        entity.setOrderMultiple(CatalogJson.decimal(body, "order_multiple"));
        entity.setQtyStep(CatalogJson.decimal(body, "qty_step"));
        entity.setPurchasePrice(CatalogJson.decimal(body, "purchase_price"));
        entity.setSalePrice(CatalogJson.decimal(body, "sale_price"));
        entity.setWeightGram(CatalogJson.decimal(body, "weight_gram"));
        entity.setListedDate(CatalogJson.dateVal(body, "listed_date"));
        stampCreate(entity);
        skuMapper.insert(entity);
        log.info("SKU 已创建 teamId={} id={} productId={} skuCode={}", entity.getTeamId(), entity.getId(),
                productId, entity.getSkuCode());
        recordMasterData(BIZ_TYPE_SKU, entity.getId(), entity.getSkuCode(), "创建 SKU");
        return withView ? SkuView.of(skuMapper.selectById(entity.getId())) : SkuView.of(entity);
    }

    /**
     * 更新 SKU 内核。
     *
     * <p>★ {@code R86}：{@code sku_code} 是 by-code upsert 的<b>定位键、不可更新</b>
     * （{@code IMPORT_WORKFLOW §7.2}，与 {@code product_id} 同级）；REST 管理端仍可改
     * （{@code API_CONTRACT §10.3} 未禁止，{@code DATABASE_DESIGN §5.7}「更新规则」只标了
     * {@code product_id} 不可变、未标 {@code sku_code}）⇒ 由调用方显式传 {@code writableCode}，
     * 理由与取舍见 {@link #updateWarehouse(Long, JsonNode, boolean)}。
     */
    private SkuView updateSku(Long id, JsonNode body, boolean writableCode) {
        skuQueryService.requireInTeam(id);

        requireNonBlankIfPresent(body, "sku_code");
        requireNonBlankIfPresent(body, "name");
        UpdateWrapper<SkuEntity> w = new UpdateWrapper<>();
        w.eq("id", id);
        // product_id 不可变：请求体中的 product_id 一律忽略（换商品须新建 SKU，§6-B）
        if (writableCode) {
            setString(w, body, "sku_code", "sku_code");
        }
        setString(w, body, "name", "name");
        setString(w, body, "barcode", "barcode");
        setJson(w, body, "spec_json", "spec_json");
        setInteger(w, body, "default_lead_time_days", "default_lead_time_days");
        setDecimal(w, body, "moq", "moq");
        setDecimal(w, body, "order_multiple", "order_multiple");
        setDecimal(w, body, "qty_step", "qty_step");
        setDecimal(w, body, "purchase_price", "purchase_price");
        setDecimal(w, body, "sale_price", "sale_price");
        setDecimal(w, body, "weight_gram", "weight_gram");
        setDate(w, body, "listed_date", "listed_date");
        if (CatalogJson.hasKey(body, "supplier_id")) {
            Long supplierId = CatalogJson.longVal(body, "supplier_id");
            assertSupplierSameTeam(supplierId, "supplier_id");
            w.set("supplier_id", supplierId);
        }
        w.set("updated_by", currentUserId());
        skuMapper.update(null, w);
        log.info("SKU 已更新 id={}", id);
        return SkuView.of(skuMapper.selectById(id));
    }

    /** 解析创建时的商品 id：路径优先；请求体与路径不一致 → {@code CAT-3004}；缺失 → {@code COM-0001}。 */
    private Long resolveProductIdForCreate(Long pathProductId, JsonNode body) {
        Long bodyProductId = CatalogJson.longVal(body, "product_id");
        if (pathProductId != null) {
            if (bodyProductId != null && !bodyProductId.equals(pathProductId)) {
                throw BizException.withDetails(ErrorCodes.CAT_3004, "该 SKU 不属于所选商品",
                        List.of(new ApiErrorDetail("product_id", "请求体 product_id 与路径不一致", bodyProductId)));
            }
            return pathProductId;
        }
        if (bodyProductId == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail("product_id", "不能为空", null)));
        }
        return bodyProductId;
    }

    // =====================================================================
    // 按业务编码 upsert（M3-B4 · 裁定 G3 方案 1；供 importdata 分批落库调用）
    // =====================================================================

    /**
     * <b>★ 为何同一内核有两个传播级别的出口（{@code ADR-004} D5，{@code B4-PERF} 轮 1）</b>
     *
     * <table border="1">
     *   <caption>两个出口的分工</caption>
     *   <tr><th>出口</th><th>传播</th><th>谁用</th><th>为什么</th></tr>
     *   <tr>
     *     <td>{@code upsertXxxByCode(JsonNode)}</td>
     *     <td>{@code NESTED}</td>
     *     <td><b>Pass 2</b>（失败路径逐行重放）</td>
     *     <td>需要 {@code SAVEPOINT} 做<b>行级隔离</b>：某行失败只回滚该行、同批其余行照常提交</td>
     *   </tr>
     *   <tr>
     *     <td>{@code upsertXxxsByCode(List)}</td>
     *     <td>{@code REQUIRED}</td>
     *     <td><b>Pass 1</b>（正常路径）</td>
     *     <td>整批一个事务、<b>零</b> {@code SAVEPOINT}（省掉每行两次往返）；失败直接上抛，
     *         由调用方整批回滚后转 Pass 2</td>
     *   </tr>
     * </table>
     *
     * <h2>为什么 Pass 1 的循环内不能 catch 行级异常后继续</h2>
     * {@code REQUIRED} 以「参与方」身份加入调用方的批次事务。参与方抛出 {@code RuntimeException} 时
     * {@code AbstractPlatformTransactionManager.processRollback} 会走 {@code doSetRollbackOnly}，
     * 把<b>整个批次事务</b>置为 {@code rollbackOnly}；此时即便方法内部吞掉异常并继续，
     * 批次事务在提交时也必抛 {@code UnexpectedRollbackException}——整批数据丢失，且异常类型脱离
     * 调用方的行级/非行级分类。故 Pass 1 必须<b>让异常穿出去</b>。
     *
     * <h2>为什么 Pass 2 仍用 {@code NESTED} 而不是 {@code REQUIRED}</h2>
     * Pass 2 的目的正是「一行失败、其余行继续」：{@code NESTED} 在批次事务内建 {@code SAVEPOINT}，
     * 失败时 {@code rollbackToSavepoint}（{@code processRollback} 的 {@code status.hasSavepoint()} 分支），
     * <b>不会</b>把批次事务置 rollback-only；无外部事务时行为等同 {@code REQUIRED}（新建事务），
     * 单对象原子性与 {@code rollbackFor = Exception.class} 均不变。未使用 {@code REQUIRES_NEW}
     * （会自开事务、破坏批内一致性），也未自行提交。
     *
     * <p>（真实栈验证：{@code ImportConfirmIntegrationTest} 的「部分失败 → {@code PARTIAL_SUCCESS}」
     * 与「引用漂移 → 行级失败而批次继续」两个用例即依赖该语义。）
     */

    /**
     * 按<b>业务编码</b> upsert 仓库（{@code MODULE_BOUNDARIES §2.3}；M3-B4 裁定 G3 方案 1）。
     *
     * <h2>为什么解析必须发生在 catalog 内</h2>
     * 导入数据里只有<b>业务编码</b>（{@code code}/{@code *_code}），没有主键 id；
     * 而「按编码解析既有行」属本模块自身的业务规则，放在 {@code catalog} 内可避免该规则外泄到
     * {@code importdata}（裁定 G1/G3 的原始理由）。
     *
     * <h2>与既有 upsertWarehouse 的关系</h2>
     * 本方法<b>不改</b> {@code upsertWarehouse(Long id, JsonNode)} 的签名与语义（REST 管理端仍在用），
     * 只是在调用它之前补上「按编码解析 id」这一步：命中 → 复用更新路径；未命中 → 复用创建路径。
     *
     * <p>解析走<b>租户线</b>（{@code selectIdByCode} 未加 {@code @InterceptorIgnore}），
     * 故只在<b>本团队</b>范围内命中既有行（{@code §7.4} 跨团队同编码互不影响）。
     *
     * <p>{@code body.status} 若显式提供且与库内不同，则复用 {@link #changeStatus} 落实
     * （{@code IMPORT_WORKFLOW §7.4} 的 {@code status} 字段；缺省 {@code ACTIVE}）。
     */
    @Transactional(propagation = Propagation.NESTED, rollbackFor = Exception.class)
    public WarehouseView upsertWarehouseByCode(JsonNode body) {
        return doUpsertWarehouseByCode(body);
    }

    /**
     * 批量按编码 upsert 仓库（{@code ADR-004 D5} 的 <b>Pass 1</b> 入口；{@code B4-PERF}）。
     *
     * <h2>与 {@link #upsertWarehouseByCode} 的差别＝只有传播级别</h2>
     * <ul>
     *   <li>{@code REQUIRED}：<b>加入调用方的批次事务</b>，逐行<b>不建 {@code SAVEPOINT}</b>
     *       （省掉每行 {@code SAVEPOINT}/{@code RELEASE} 两次往返）；</li>
     *   <li>循环内<b>绝不</b> {@code catch} 行级异常后继续：本方法以「参与方」身份加入批次事务，
     *       一旦吞掉异常并正常返回，Spring 早把批次事务标记成 {@code rollback-only}，
     *       提交时抛 {@code UnexpectedRollbackException}，整批数据丢失且异常类型不可控。
     *       故<b>直接上抛</b>，由 {@code ImportConfirmService} 回滚整批后走 Pass 2 逐行重放。</li>
     * </ul>
     * 两条路径共用同一私有内核 {@link #doUpsertWarehouseByCode}，因此 upsert 语义、
     * {@code H2}「空值不覆盖」、审计条数完全一致，不存在「批量路径行为不同」的分叉。
     *
     * @param bodies 待落库行（顺序即文件顺序；空列表直接返回）
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void upsertWarehousesByCode(List<JsonNode> bodies) {
        if (bodies == null || bodies.isEmpty()) {
            return;
        }
        // 轮 2 定向优化：整批一次 IN 查询预取 code→id，替代逐行 selectIdByCode。
        // 数据依据：import-perf-profile 测得 10000 次 selectIdByCode = 19.1 s（占业务新建 32%）。
        // 语义不变：selectList 同样受租户线约束（自动追加 team_id），跨团队同编码天然查不到。
        Map<String, Long> preloadedIds = preloadWarehouseIdsByCode(bodies);
        for (JsonNode body : bodies) {
            doUpsertWarehouseByCode(body, preloadedIds);
        }
    }

    /**
     * 仓库 by-code upsert 的<b>无事务注解内核</b>（{@code ❌} 不得在此方法上标 {@code @Transactional}：
     * Spring 的代理只拦「从外部调用进来的 public 方法」，同类自调用不会经过代理，
     * 标了也不生效，反而制造「看起来有事务边界」的错觉）。
     */
    /** 单行出口（REST 与 Pass 2 重放）：逐行解析既有 id；与批量出口语义一致。 */
    private WarehouseView doUpsertWarehouseByCode(JsonNode body) {
        return doUpsertWarehouseByCode(body, null);
    }

    /**
     * 整批预取 {@code code → id}（轮 2 定向优化，依据 {@code import-perf-profile.txt}）。
     *
     * <p>一次 {@code IN} 查询代替逐行 {@code selectIdByCode}：后者实测 1.91 ms/次，
     * 1 万行就是 19.1 s。此处<b>不改</b>既有 Mapper 方法，只复用 {@code BaseMapper.selectList}
     * ——租户线照常追加 {@code team_id}，跨团队同编码仍查不到，与逐行解析<b>完全等价</b>。
     *
     * <p>缺失/空白的键直接跳过：交给下游 {@code requireText} 抛 {@code COM-0001}（行级失败），
     * 预取阶段不改变错误语义。
     *
     * <p>★ {@code R86}：返回表的键与查找<b>都</b>经 {@link #foldCode(String)} 折叠。
     * {@code in("code", …)} 受 {@code utf8mb4_0900_ai_ci} 约束<b>能</b>用文件里的 {@code wh-main}
     * 匹配到库里的 {@code WH-MAIN}，但返回的 Map 键是<b>库里写法</b>；若查找侧不折叠，
     * 则预取表必然落空 ⇒ 命中既有行被判成「不存在」⇒ 走 create 分支撞 {@code uk_*_team_code}，
     * 批量出口退化成整批回滚 + 逐行重放（与单行出口的 {@code selectIdByCode} 行为分叉）。
     * 折叠后两条出口的判定<b>严格等价</b>（与 {@code IMPORT_WORKFLOW §8.6} 末行一致）。
     */
    private Map<String, Long> preloadWarehouseIdsByCode(List<JsonNode> bodies) {
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode body : bodies) {
            String code = CatalogJson.text(body, "code");
            if (code != null && !code.isBlank()) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> ids = new HashMap<>(codes.size() * 2);
        for (WarehouseEntity row : warehouseMapper.selectList(new LambdaQueryWrapper<WarehouseEntity>()
                .select(WarehouseEntity::getId, WarehouseEntity::getCode)
                .in(WarehouseEntity::getCode, codes))) {
            ids.put(foldCode(row.getCode()), row.getId());
        }
        return ids;
    }

    /**
     * by-code upsert 的无事务内核。
     *
     * <p>★ {@code R86}：命中既有行时 {@code code} 是<b>定位键</b>（{@code IMPORT_WORKFLOW §7.4}），
     * 传给更新内核的是 {@code writableCode=false} —— <b>保留库内既有字面量</b>，不得改写成文件里的写法。
     * 判定仍按 {@code IMPORT_WORKFLOW §8.6} 末行（编码大小写不敏感等价）命中：{@code selectIdByCode} 走
     * {@code utf8mb4_0900_ai_ci}，{@code sku-a} 能命中库内的 {@code SKU-A}；命中后只是<b>不改</b>它。
     * create 分支（{@code existingId == null}）保持原样，{@code code} 照写。
     *
     * @param preloadedIds 整批预取的 {@code code → id}；{@code null} 表示「未预取，逐行解析」
     */
    private WarehouseView doUpsertWarehouseByCode(JsonNode body, Map<String, Long> preloadedIds) {
        String code = requireText(body, "code");
        Long existingId = preloadedIds == null
                ? warehouseMapper.selectIdByCode(code)
                : preloadedIds.get(foldCode(code));
        WarehouseView view;
        if (existingId == null) {
            view = createWarehouse(body, false);
        } else {
            // R86：writableCode=false ⇒ 不 set code 列，保留库内既有写法
            view = updateWarehouse(existingId, body, false);
            // R48（ADR-004 D4）：updateWarehouse 本身不写审计（REST PUT 由 @Audited 记），
            // 导入路径不经 Controller ⇒ 必须在此补一条「导入更新」，否则更新无痕。
            // ★ R86：bizNo 取 view.getCode()——更新后重查得到的正是【库内既有字面量】，
            //   而不是文件里的写法（否则审计会记录一个被改写前的、不存在的编码）。
            recordMasterData(BIZ_TYPE_WAREHOUSE, view.getId(), view.getCode(), "导入更新仓库");
        }
        if (needsStatusChange(body, view.getStatus())) {
            view = (WarehouseView) changeStatus(CatalogResourceType.WAREHOUSE, view.getId(), statusOf(body));
        }
        return view;
    }

    /**
     * 按<b>业务编码</b> upsert 供应商（{@code §7.3}；M3-B4 裁定 G3 方案 1）。语义同 {@link #upsertWarehouseByCode}。
     */
    @Transactional(propagation = Propagation.NESTED, rollbackFor = Exception.class)
    public SupplierView upsertSupplierByCode(JsonNode body) {
        return doUpsertSupplierByCode(body);
    }

    /** 批量按编码 upsert 供应商（{@code ADR-004 D5} Pass 1）。语义与限制同 {@link #upsertWarehousesByCode}。 */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void upsertSuppliersByCode(List<JsonNode> bodies) {
        if (bodies == null || bodies.isEmpty()) {
            return;
        }
        // 轮 2 定向优化：整批一次 IN 查询预取 code→id，替代逐行 selectIdByCode。
        // 数据依据：import-perf-profile 测得 10000 次 selectIdByCode = 19.1 s（占业务新建 32%）。
        // 语义不变：selectList 同样受租户线约束（自动追加 team_id），跨团队同编码天然查不到。
        Map<String, Long> preloadedIds = preloadSupplierIdsByCode(bodies);
        for (JsonNode body : bodies) {
            doUpsertSupplierByCode(body, preloadedIds);
        }
    }

    /** 供应商 by-code upsert 的无事务内核（禁止标 {@code @Transactional}，理由见同名仓库方法）。 */
    /** 单行出口（REST 与 Pass 2 重放）：逐行解析既有 id；与批量出口语义一致。 */
    private SupplierView doUpsertSupplierByCode(JsonNode body) {
        return doUpsertSupplierByCode(body, null);
    }

    /**
     * 整批预取 {@code code → id}（轮 2 定向优化，依据 {@code import-perf-profile.txt}）。
     *
     * <p>一次 {@code IN} 查询代替逐行 {@code selectIdByCode}：后者实测 1.91 ms/次，
     * 1 万行就是 19.1 s。此处<b>不改</b>既有 Mapper 方法，只复用 {@code BaseMapper.selectList}
     * ——租户线照常追加 {@code team_id}，跨团队同编码仍查不到，与逐行解析<b>完全等价</b>。
     *
     * <p>缺失/空白的键直接跳过：交给下游 {@code requireText} 抛 {@code COM-0001}（行级失败），
     * 预取阶段不改变错误语义。
     *
     * <p>★ {@code R86}：返回表的键与查找<b>都</b>经 {@link #foldCode(String)} 折叠，
     * 使批量出口与单行出口的命中判定<b>严格等价</b>（详见 {@link #preloadWarehouseIdsByCode}）。
     */
    private Map<String, Long> preloadSupplierIdsByCode(List<JsonNode> bodies) {
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode body : bodies) {
            String code = CatalogJson.text(body, "code");
            if (code != null && !code.isBlank()) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> ids = new HashMap<>(codes.size() * 2);
        for (SupplierEntity row : supplierMapper.selectList(new LambdaQueryWrapper<SupplierEntity>()
                .select(SupplierEntity::getId, SupplierEntity::getCode)
                .in(SupplierEntity::getCode, codes))) {
            ids.put(foldCode(row.getCode()), row.getId());
        }
        return ids;
    }

    /**
     * by-code upsert 的无事务内核。
     *
     * <p>★ {@code R86}：命中既有行时 {@code code} 是<b>定位键</b>（{@code IMPORT_WORKFLOW §7.3}），
     * 传给更新内核的是 {@code writableCode=false} —— <b>保留库内既有字面量</b>；判定仍按
     * {@code §8.6} 末行（大小写不敏感等价）命中。create 分支保持原样。
     *
     * @param preloadedIds 整批预取的 {@code code → id}；{@code null} 表示「未预取，逐行解析」
     */
    private SupplierView doUpsertSupplierByCode(JsonNode body, Map<String, Long> preloadedIds) {
        String code = requireText(body, "code");
        Long existingId = preloadedIds == null
                ? supplierMapper.selectIdByCode(code)
                : preloadedIds.get(foldCode(code));
        SupplierView view;
        if (existingId == null) {
            view = createSupplier(body, false);
        } else {
            // R86：writableCode=false ⇒ 不 set code 列，保留库内既有写法
            view = updateSupplier(existingId, body, false);
            // R48：导入更新补审计（见 doUpsertWarehouseByCode 同处注释）
            // ★ R86：bizNo 用 view.getCode()（更新后重查＝库内既有字面量）
            recordMasterData(BIZ_TYPE_SUPPLIER, view.getId(), view.getCode(), "导入更新供应商");
        }
        if (needsStatusChange(body, view.getStatus())) {
            view = (SupplierView) changeStatus(CatalogResourceType.SUPPLIER, view.getId(), statusOf(body));
        }
        return view;
    }

    /**
     * 按<b>业务编码</b> upsert 商品（{@code §7.1}；M3-B4 裁定 G3 方案 1）。
     *
     * <p>额外把导入特有的 {@code default_supplier_code}（默认供应商<b>编码</b>）解析为
     * {@code default_supplier_id}——{@code products} 表只有 id 列，编码解析属 catalog 自身规则。
     */
    @Transactional(propagation = Propagation.NESTED, rollbackFor = Exception.class)
    public ProductView upsertProductByCode(JsonNode body) {
        return doUpsertProductByCode(body);
    }

    /** 批量按编码 upsert 商品（{@code ADR-004 D5} Pass 1）。语义与限制同 {@link #upsertWarehousesByCode}。 */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void upsertProductsByCode(List<JsonNode> bodies) {
        if (bodies == null || bodies.isEmpty()) {
            return;
        }
        // 轮 2 定向优化：整批一次 IN 查询预取 code→id，替代逐行 selectIdByCode。
        // 数据依据：import-perf-profile 测得 10000 次 selectIdByCode = 19.1 s（占业务新建 32%）。
        // 语义不变：selectList 同样受租户线约束（自动追加 team_id），跨团队同编码天然查不到。
        Map<String, Long> preloadedIds = preloadProductIdsByCode(bodies);
        for (JsonNode body : bodies) {
            doUpsertProductByCode(body, preloadedIds);
        }
    }

    /** 商品 by-code upsert 的无事务内核（禁止标 {@code @Transactional}，理由见同名仓库方法）。 */
    /** 单行出口（REST 与 Pass 2 重放）：逐行解析既有 id；与批量出口语义一致。 */
    private ProductView doUpsertProductByCode(JsonNode body) {
        return doUpsertProductByCode(body, null);
    }

    /**
     * 整批预取 {@code product_code → id}（轮 2 定向优化，依据 {@code import-perf-profile.txt}）。
     *
     * <p>一次 {@code IN} 查询代替逐行 {@code selectIdByCode}：后者实测 1.91 ms/次，
     * 1 万行就是 19.1 s。此处<b>不改</b>既有 Mapper 方法，只复用 {@code BaseMapper.selectList}
     * ——租户线照常追加 {@code team_id}，跨团队同编码仍查不到，与逐行解析<b>完全等价</b>。
     *
     * <p>缺失/空白的键直接跳过：交给下游 {@code requireText} 抛 {@code COM-0001}（行级失败），
     * 预取阶段不改变错误语义。
     *
     * <p>★ {@code R86}：返回表的键与查找<b>都</b>经 {@link #foldCode(String)} 折叠，
     * 使批量出口与单行出口的命中判定<b>严格等价</b>（详见 {@link #preloadWarehouseIdsByCode}）。
     */
    private Map<String, Long> preloadProductIdsByCode(List<JsonNode> bodies) {
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode body : bodies) {
            String code = CatalogJson.text(body, "product_code");
            if (code != null && !code.isBlank()) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> ids = new HashMap<>(codes.size() * 2);
        for (ProductEntity row : productMapper.selectList(new LambdaQueryWrapper<ProductEntity>()
                .select(ProductEntity::getId, ProductEntity::getProductCode)
                .in(ProductEntity::getProductCode, codes))) {
            ids.put(foldCode(row.getProductCode()), row.getId());
        }
        return ids;
    }

    /**
     * by-code upsert 的无事务内核。
     *
     * <p>★ {@code R86}：命中既有行时 {@code product_code} 是<b>定位键、不可更新</b>
     * （{@code IMPORT_WORKFLOW §7.1}「{@code product_code} 为业务键不可改」），传给更新内核的是
     * {@code writableCode=false} —— <b>保留库内既有字面量</b>；判定仍按 {@code §8.6} 末行命中。
     * create 分支保持原样。
     *
     * @param preloadedIds 整批预取的 {@code product_code → id}；{@code null} 表示「未预取，逐行解析」
     */
    private ProductView doUpsertProductByCode(JsonNode body, Map<String, Long> preloadedIds) {
        String productCode = requireText(body, "product_code");
        JsonNode normalized = withResolvedCode(body, "default_supplier_code", "default_supplier_id",
                code -> supplierMapper.selectIdByCode(code), "引用的供应商不存在");
        Long existingId = preloadedIds == null
                ? productMapper.selectIdByCode(productCode)
                : preloadedIds.get(foldCode(productCode));
        ProductView view;
        if (existingId == null) {
            view = createProduct(normalized, false);
        } else {
            // R86：writableCode=false ⇒ 不 set product_code 列，保留库内既有写法
            view = updateProduct(existingId, normalized, false, false);
            // R48：导入更新补审计（见 doUpsertWarehouseByCode 同处注释）
            // ★ R86：bizNo 用 view.getProductCode()——此处 withView=false，返回的是 guard 已取到的
            //   【更新前】实体，其 product_code 即库内既有字面量（正是要求的口径）。
            recordMasterData(BIZ_TYPE_PRODUCT, view.getId(), view.getProductCode(), "导入更新商品");
        }
        if (needsStatusChange(normalized, view.getStatus())) {
            view = (ProductView) changeStatus(CatalogResourceType.PRODUCT, view.getId(),
                    statusOf(normalized));
        }
        return view;
    }

    /**
     * 按<b>业务编码</b> upsert SKU（{@code §7.2}；M3-B4 裁定 G3 方案 1 + R38 M2）。
     *
     * <h2>product_id 不可变（{@code §7.2} / {@code DATABASE_DESIGN §5.7}）——R38 M2 的判定层</h2>
     * 「同 {@code sku_code} 指向不同 {@code product_code}」在此<b>落库层</b>判定并抛
     * {@code CAT-3002}（校验阶段不判定：校验层拿不到库内既有 SKU 的 {@code product_id}，
     * 强行判定需在 {@code importdata} 侧再开一条按编码读 SKU 的通道，违反裁定 G1）。
     * 该行因此计入落库期失败行 → {@code PARTIAL_SUCCESS}，语义与裁定 C1b 一致。
     *
     * @param body 必含 {@code sku_code} + {@code product_code}
     */
    @Transactional(propagation = Propagation.NESTED, rollbackFor = Exception.class)
    public SkuView upsertSkuByCode(JsonNode body) {
        return doUpsertSkuByCode(body);
    }

    /** 批量按编码 upsert SKU（{@code ADR-004 D5} Pass 1）。语义与限制同 {@link #upsertWarehousesByCode}。 */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void upsertSkusByCode(List<JsonNode> bodies) {
        if (bodies == null || bodies.isEmpty()) {
            return;
        }
        // 轮 2 定向优化：整批一次 IN 查询预取 code→id，替代逐行 selectIdByCode。
        // 数据依据：import-perf-profile 测得 10000 次 selectIdByCode = 19.1 s（占业务新建 32%）。
        // 语义不变：selectList 同样受租户线约束（自动追加 team_id），跨团队同编码天然查不到。
        Map<String, Long> preloadedIds = preloadSkuIdsByCode(bodies);
        for (JsonNode body : bodies) {
            doUpsertSkuByCode(body, preloadedIds);
        }
    }

    /** SKU by-code upsert 的无事务内核（禁止标 {@code @Transactional}，理由见同名仓库方法）。 */
    /** 单行出口（REST 与 Pass 2 重放）：逐行解析既有 id；与批量出口语义一致。 */
    private SkuView doUpsertSkuByCode(JsonNode body) {
        return doUpsertSkuByCode(body, null);
    }

    /**
     * 整批预取 {@code sku_code → id}（轮 2 定向优化，依据 {@code import-perf-profile.txt}）。
     *
     * <p>一次 {@code IN} 查询代替逐行 {@code selectIdByCode}：后者实测 1.91 ms/次，
     * 1 万行就是 19.1 s。此处<b>不改</b>既有 Mapper 方法，只复用 {@code BaseMapper.selectList}
     * ——租户线照常追加 {@code team_id}，跨团队同编码仍查不到，与逐行解析<b>完全等价</b>。
     *
     * <p>缺失/空白的键直接跳过：交给下游 {@code requireText} 抛 {@code COM-0001}（行级失败），
     * 预取阶段不改变错误语义。
     *
     * <p>★ {@code R86}：返回表的键与查找<b>都</b>经 {@link #foldCode(String)} 折叠，
     * 使批量出口与单行出口的命中判定<b>严格等价</b>（详见 {@link #preloadWarehouseIdsByCode}）。
     */
    private Map<String, Long> preloadSkuIdsByCode(List<JsonNode> bodies) {
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode body : bodies) {
            String code = CatalogJson.text(body, "sku_code");
            if (code != null && !code.isBlank()) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> ids = new HashMap<>(codes.size() * 2);
        for (SkuEntity row : skuMapper.selectList(new LambdaQueryWrapper<SkuEntity>()
                .select(SkuEntity::getId, SkuEntity::getSkuCode)
                .in(SkuEntity::getSkuCode, codes))) {
            ids.put(foldCode(row.getSkuCode()), row.getId());
        }
        return ids;
    }

    /**
     * by-code upsert 的无事务内核。
     *
     * <p>★ {@code R86}：命中既有行时 {@code sku_code} 是<b>定位键、不可更新</b>
     * （{@code IMPORT_WORKFLOW §7.2}，与 {@code product_id} 同级），传给更新内核的是
     * {@code writableCode=false} —— <b>保留库内既有字面量</b>；判定仍按 {@code §8.6} 末行命中。
     * create 分支保持原样。
     *
     * @param preloadedIds 整批预取的 {@code sku_code → id}；{@code null} 表示「未预取，逐行解析」
     */
    private SkuView doUpsertSkuByCode(JsonNode body, Map<String, Long> preloadedIds) {
        String skuCode = requireText(body, "sku_code");
        String productCode = requireText(body, "product_code");

        // ① 先解析所属商品：不存在 → CAT-3001（§7.2「product_code 必须已存在」）
        Long productId = productMapper.selectIdByCode(productCode);
        if (productId == null) {
            throw BizException.withDetails(ErrorCodes.CAT_3001, "引用的商品不存在",
                    List.of(new ApiErrorDetail("product_code", "商品编码 " + productCode + " 不存在", productCode)));
        }
        JsonNode normalized = withResolvedCode(body, "supplier_code", "supplier_id",
                code -> supplierMapper.selectIdByCode(code), "引用的供应商不存在");

        // ② 再解析既有 SKU：未命中 → 创建；命中 → 校验 product_id 不可变后更新
        Long existingId = preloadedIds == null
                ? skuMapper.selectIdByCode(skuCode)
                : preloadedIds.get(foldCode(skuCode));
        SkuView view;
        if (existingId == null) {
            view = insertSku(productId, normalized, false);
        } else {
            SkuEntity existing = skuMapper.selectById(existingId);
            if (existing != null && !productId.equals(existing.getProductId())) {
                throw BizException.withDetails(ErrorCodes.CAT_3002, "该编码已存在，请更换",
                        List.of(new ApiErrorDetail("sku_code",
                                "SKU 编码 " + skuCode + " 已存在且隶属于其它商品（product_id 不可变）", skuCode)));
            }
            // R86：writableCode=false ⇒ 不 set sku_code 列，保留库内既有写法
            view = updateSku(existingId, normalized, false);
            // R48：导入更新补审计（见 doUpsertWarehouseByCode 同处注释）
            // ★ R86：bizNo 用 view.getSkuCode()（更新后重查＝库内既有字面量）
            recordMasterData(BIZ_TYPE_SKU, view.getId(), view.getSkuCode(), "导入更新 SKU");
        }
        if (needsStatusChange(normalized, view.getStatus())) {
            view = (SkuView) changeStatus(CatalogResourceType.SKU, view.getId(), statusOf(normalized));
        }
        return view;
    }

    /**
     * 把 body 里的「引用对象<b>编码</b>」解析为「外键 <b>id</b>」并替换（编码字段被移除）。
     *
     * <p>{@code IMPORT_WORKFLOW §7.1–§7.2} 的导入列是 {@code default_supplier_code}/{@code supplier_code}，
     * 而 {@code products}/{@code skus} 表只有 {@code *_id} 列——解析必须在本模块内完成（裁定 G1）。
     *
     * <p>字段<b>缺席或为空</b>时原样返回：不写入 id 列 ⇒ 保留库内原值
     * （裁定 H2「空值不得覆盖库内已有值」）。解析失败 → {@code CAT-3001}。
     */
    private JsonNode withResolvedCode(JsonNode body, String codeField, String idField,
                                      Function<String, Long> resolver, String notFoundMessage) {
        if (!CatalogJson.has(body, codeField)) {
            return body;
        }
        String code = CatalogJson.text(body, codeField);
        ObjectNode copy = ((ObjectNode) body).deepCopy();
        copy.remove(codeField);
        Long id = resolver.apply(code);
        if (id == null) {
            throw BizException.withDetails(ErrorCodes.CAT_3001, notFoundMessage,
                    List.of(new ApiErrorDetail(codeField, "编码 " + code + " 在本团队内不存在", code)));
        }
        copy.put(idField, id);
        return copy;
    }

    /**
     * 业务编码的<b>判定键</b>折叠（{@code trim + toUpperCase(Locale.ROOT)}，{@code R85}/{@code R86}）。
     *
     * <p>依据 {@code IMPORT_WORKFLOW §8.6} 末行：所有 {@code code} 字段一律<b>大小写不敏感等价</b>。
     * 物理依据是唯一键 {@code (team_id, <code>)} 建在 {@code utf8mb4_0900_ai_ci} 上 ⇒
     * 库内<b>物理上不可能</b>并存 {@code SKU-A} 与 {@code sku-a}，故把两者折叠成同一判定键
     * 不会引入歧义。
     *
     * <p>★ 只用于<b>判定</b>（命中与否、预取表键），<b>绝不</b>用于<b>写入</b>：
     * 落库一律写用户/文件给出的原样字面量（create 分支），命中既有行则原样保留库内写法
     * （{@code R86}）—— 折叠是「怎么找到行」，不是「把行改成什么」。
     *
     * <p>用 {@link Locale#ROOT} 而非 {@link Locale#TOUPPERCASE}：后者在土耳其语等 locale 下
     * 会把 {@code i} 折叠成 {@code İ}，破坏等价性（{@code String#toUpperCase()} 的无参版同此坑）。
     */
    private static String foldCode(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** 必填文本字段（缺失/空白 → {@code 400 COM-0001} + details）。 */
    private static String requireText(JsonNode body, String field) {
        String value = CatalogJson.text(body, field);
        if (CatalogJson.blank(value)) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail(field, "不能为空", value)));
        }
        return value;
    }

    /** body 是否显式提供了 {@code status} 且与当前状态<b>不同</b>（相同则不产生多余的状态变更审计）。 */
    private static boolean needsStatusChange(JsonNode body, String currentStatus) {
        return CatalogJson.has(body, "status") && !statusOf(body).equalsIgnoreCase(currentStatus);
    }

    /** {@code status} 归一化（去空白 + 大写）；合法性由 {@link #changeStatus} 的 {@code requireValidStatus} 把关。 */
    private static String statusOf(JsonNode body) {
        return CatalogJson.text(body, "status").trim().toUpperCase(Locale.ROOT);
    }

    // =====================================================================
    // 启停（M2-T10）
    // =====================================================================

    /**
     * 统一启停入口（{@code MODULE_BOUNDARIES §2.3}：{@code changeStatus(resourceType, id, status)}）。
     *
     * <p>4 个 {@code DELETE /{id}}（软删 → {@code INACTIVE}）与 2 个 {@code PATCH /{id}/status}
     * （显式改状态，含重新启用）全部经此收敛。<b>DELETE 一律软删</b>：只改 {@code status} 列，
     * 物理行保留（{@code API_CONTRACT §1.2} 硬规则 1）；对已 {@code INACTIVE} 对象重复 DELETE 仍返回成功
     * （幂等，任务书 §3-1），不做乐观锁、不报 409/422。
     *
     * @param resourceType 资源类型（区分四表）
     * @param id           资源主键
     * @param status       目标状态；仅接受 {@code ACTIVE} / {@code INACTIVE}，否则 400 {@code COM-0001}+details
     * @return 更新后的资源视图对象（具体类型与 {@code resourceType} 对应，由调用方按类型接收）
     */
    @Transactional(rollbackFor = Exception.class)
    public Object changeStatus(CatalogResourceType resourceType, Long id, String status) {
        String target = requireValidStatus(status);
        return switch (resourceType) {
            case WAREHOUSE -> changeWarehouseStatus(id, target);
            case SUPPLIER -> changeSupplierStatus(id, target);
            case PRODUCT -> changeProductStatus(id, target);
            case SKU -> changeSkuStatus(id, target);
        };
    }

    private WarehouseView changeWarehouseStatus(Long id, String status) {
        WarehouseEntity current = CatalogGuards.requireInTeam(warehouseMapper.selectById(id),
                () -> warehouseMapper.selectTeamIdById(id), ErrorCodes.CAT_3003, "仓库不存在");

        // TODO(M3/M6): CAT-3007 关联校验（在途采购/库存/补货建议）——依赖表属 M3/M6，本阶段不存在，故不实现
        UpdateWrapper<WarehouseEntity> w = new UpdateWrapper<>();
        w.eq("id", id).set("status", status).set("updated_by", currentUserId());
        // 停用默认仓：同事务把 is_default 置 0，避免下游 getDefaultWarehouse 命中已停用仓（任务书 §3-4）
        if (STATUS_INACTIVE.equals(status) && Boolean.TRUE.equals(current.getIsDefault())) {
            w.set("is_default", false);
        }
        warehouseMapper.update(null, w);
        log.info("仓库状态已变更 id={} status={}", id, status);
        recordMasterData(BIZ_TYPE_WAREHOUSE, id, current.getCode(),
                STATUS_INACTIVE.equals(status) ? "停用仓库" : "启用仓库");
        return WarehouseView.of(warehouseMapper.selectById(id));
    }

    private SupplierView changeSupplierStatus(Long id, String status) {
        SupplierEntity current = CatalogGuards.requireInTeam(supplierMapper.selectById(id),
                () -> supplierMapper.selectTeamIdById(id), ErrorCodes.CAT_3001, "供应商不存在");

        // TODO(M3/M6): CAT-3007 关联校验（在途采购/库存/补货建议）——依赖表属 M3/M6，本阶段不存在，故不实现
        UpdateWrapper<SupplierEntity> w = new UpdateWrapper<>();
        w.eq("id", id).set("status", status).set("updated_by", currentUserId());
        supplierMapper.update(null, w);
        log.info("供应商状态已变更 id={} status={}", id, status);
        recordMasterData(BIZ_TYPE_SUPPLIER, id, current.getCode(),
                STATUS_INACTIVE.equals(status) ? "停用供应商" : "启用供应商");
        return SupplierView.of(supplierMapper.selectById(id));
    }

    private ProductView changeProductStatus(Long id, String status) {
        ProductEntity current = CatalogGuards.requireInTeam(productMapper.selectById(id),
                () -> productMapper.selectTeamIdById(id), ErrorCodes.CAT_3001, "商品不存在");

        // CAT-3009：停用商品前其下不得存在 ACTIVE SKU（ERROR_CODES §2.4；本批必做，任务书 §3-3）
        if (STATUS_INACTIVE.equals(status)) {
            Long activeSkus = skuMapper.selectCount(new QueryWrapper<SkuEntity>()
                    .eq("product_id", id)
                    .eq("status", STATUS_ACTIVE));
            if (activeSkus != null && activeSkus > 0) {
                throw new BizException(ErrorCodes.CAT_3009, "该商品下仍有启用的 SKU，请先停用");
            }
        }
        // TODO(M3/M6): CAT-3007 关联校验（在途采购/库存/补货建议）——依赖表属 M3/M6，本阶段不存在，故不实现
        UpdateWrapper<ProductEntity> w = new UpdateWrapper<>();
        w.eq("id", id).set("status", status).set("updated_by", currentUserId());
        productMapper.update(null, w);
        log.info("商品状态已变更 id={} status={}", id, status);
        recordMasterData(BIZ_TYPE_PRODUCT, id, current.getProductCode(),
                STATUS_INACTIVE.equals(status) ? "停用商品" : "启用商品");
        return ProductView.of(productMapper.selectById(id));
    }

    private SkuView changeSkuStatus(Long id, String status) {
        SkuEntity current = CatalogGuards.requireInTeam(skuMapper.selectById(id),
                () -> skuMapper.selectTeamIdById(id), ErrorCodes.CAT_3001, "SKU 不存在");

        // TODO(M3/M6): CAT-3007 关联校验（在途采购/库存/补货建议）——依赖表属 M3/M6，本阶段不存在，故不实现
        UpdateWrapper<SkuEntity> w = new UpdateWrapper<>();
        w.eq("id", id).set("status", status).set("updated_by", currentUserId());
        skuMapper.update(null, w);
        log.info("SKU 状态已变更 id={} status={}", id, status);
        recordMasterData(BIZ_TYPE_SKU, id, current.getSkuCode(),
                STATUS_INACTIVE.equals(status) ? "停用 SKU" : "启用 SKU");
        return SkuView.of(skuMapper.selectById(id));
    }

    /** {@code status} 入参校验（任务书 §3-2）：缺失 / 非法枚举 → 400 {@code COM-0001} + {@code details[]}。 */
    private static String requireValidStatus(String status) {
        if (!STATUS_ACTIVE.equals(status) && !STATUS_INACTIVE.equals(status)) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail("status", "必须为 ACTIVE 或 INACTIVE", status)));
        }
        return status;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /**
     * 主数据审计（{@code MASTER_DATA_STATUS_CHANGE}，{@code DATABASE_DESIGN §6-J.1} #6）。
     *
     * <p>{@code biz_type} 区分四类资源；{@code biz_id} = 资源 id；{@code biz_no} = 编号快照
     * （{@code code}/{@code product_code}/{@code sku_code}——对象可能已软停用，故记快照）；
     * 操作者与团队取当前上下文（角色为「当时角色」快照）。
     *
     * <p>调用点共三类：
     * <ul>
     *   <li><b>创建</b>（{@code createXxx}）→ {@code "创建<资源名>"}；</li>
     *   <li><b>启停</b>（{@code changeXxxStatus}）→ {@code "停用/启用<资源名>"}；</li>
     *   <li><b>导入更新</b>（{@code doUpsertXxxByCode} 的更新分支）→ {@code "导入更新<资源名>"}
     *       ——{@code R48}（{@code ADR-004} D4）：导入不经 Controller，{@code @Audited} 切面覆盖不到，
     *       故必须在应用服务层补。REST 的 {@code PUT} 走 {@code updateXxx}（本方法<b>不</b>在那里调用），
     *       仍由 {@code @Audited} 记一条，<b>不会</b>重复。</li>
     * </ul>
     */
    private void recordMasterData(String bizType, Long id, String bizNo, String changeSummary) {
        TeamContext.Snapshot snapshot = TeamContext.get();
        auditWriteService.record(AuditEvent.builder()
                .teamId(snapshot == null ? 0L : snapshot.teamId())
                .action(AuditAction.MASTER_DATA_STATUS_CHANGE)
                .bizType(bizType)
                .bizId(id)
                .bizNo(bizNo)
                .operatorId(snapshot == null ? null : snapshot.userId())
                .operatorName(snapshot == null ? null : snapshot.username())
                .operatorRole(snapshot == null || snapshot.roles().isEmpty() ? null : snapshot.roles().get(0))
                .result(AuditResult.SUCCESS)
                .changeSummary(changeSummary)
                .build());
    }

    /**
     * SKU 的商品关联校验：商品不存在或跨团队 → {@code 422 CAT-3004}（任务书 §3.3 / §7-6）。
     *
     * <p>此处商品是「被写入的关联」而非「被访问的资源」，故<b>不</b>套用 §3.2 的 404/403 资源访问口径，
     * 统一按 {@code ERROR_CODES §2.4 CAT-3004}「SKU 不属于该商品或该团队」。受租户线约束的查询对
     * 「异团队」与「不存在」都返回 {@code null}，二者同码、不泄露存在性。
     */
    private void assertProductAssociation(Long productId) {
        if (productMapper.selectById(productId) == null) {
            throw new BizException(ErrorCodes.CAT_3004, "该 SKU 不属于所选商品");
        }
    }

    private void assertSupplierSameTeam(Long supplierId, String field) {
        if (supplierId == null) {
            return;
        }
        // 受租户线约束：异团队或不存在的供应商都查不到 → 统一 CAT-3005（任务书 §3.3）
        if (supplierMapper.selectById(supplierId) == null) {
            throw BizException.withDetails(ErrorCodes.CAT_3005, "关联的供应商不属于当前团队",
                    List.of(new ApiErrorDetail(field, "该供应商不存在或不属于当前团队", supplierId)));
        }
    }

    private static void requireAllNonBlank(JsonNode body, String... fields) {
        List<ApiErrorDetail> details = new ArrayList<>();
        for (String field : fields) {
            if (CatalogJson.blank(CatalogJson.text(body, field))) {
                details.add(new ApiErrorDetail(field, "不能为空", CatalogJson.text(body, field)));
            }
        }
        if (!details.isEmpty()) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败", details);
        }
    }

    private static void requireNonBlankIfPresent(JsonNode body, String field) {
        if (CatalogJson.hasKey(body, field) && CatalogJson.blank(CatalogJson.textOrNull(body, field))) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail(field, "不能为空", CatalogJson.textOrNull(body, field))));
        }
    }

    private static void setString(UpdateWrapper<?> w, JsonNode body, String field, String column) {
        if (CatalogJson.hasKey(body, field)) {
            w.set(column, CatalogJson.textOrNull(body, field));
        }
    }

    private static void setInteger(UpdateWrapper<?> w, JsonNode body, String field, String column) {
        if (CatalogJson.hasKey(body, field)) {
            w.set(column, CatalogJson.intVal(body, field));
        }
    }

    private static void setDecimal(UpdateWrapper<?> w, JsonNode body, String field, String column) {
        if (CatalogJson.hasKey(body, field)) {
            w.set(column, CatalogJson.decimal(body, field));
        }
    }

    private static void setJson(UpdateWrapper<?> w, JsonNode body, String field, String column) {
        if (CatalogJson.hasKey(body, field)) {
            w.set(column, CatalogJson.jsonText(body, field));
        }
    }

    private static void setDate(UpdateWrapper<?> w, JsonNode body, String field, String column) {
        if (CatalogJson.hasKey(body, field)) {
            w.set(column, CatalogJson.dateVal(body, field));
        }
    }

    private static void stampCreate(WarehouseEntity e) {
        Long uid = currentUserId();
        e.setCreatedBy(uid);
        e.setUpdatedBy(uid);
    }

    private static void stampCreate(SupplierEntity e) {
        Long uid = currentUserId();
        e.setCreatedBy(uid);
        e.setUpdatedBy(uid);
    }

    private static void stampCreate(ProductEntity e) {
        Long uid = currentUserId();
        e.setCreatedBy(uid);
        e.setUpdatedBy(uid);
    }

    private static void stampCreate(SkuEntity e) {
        Long uid = currentUserId();
        e.setCreatedBy(uid);
        e.setUpdatedBy(uid);
    }

    private static long currentTeamId() {
        return TeamContext.require().teamId();
    }

    private static Long currentUserId() {
        return TeamScopeValidator.requireContext().userId();
    }
}

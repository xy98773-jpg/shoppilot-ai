package com.shoppilot.catalog.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
        return id == null ? createWarehouse(body) : updateWarehouse(id, body);
    }

    private WarehouseView createWarehouse(JsonNode body) {
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
        return WarehouseView.of(warehouseMapper.selectById(entity.getId()));
    }

    private WarehouseView updateWarehouse(Long id, JsonNode body) {
        CatalogGuards.requireInTeam(warehouseMapper.selectById(id),
                () -> warehouseMapper.selectTeamIdById(id), ErrorCodes.CAT_3003, "仓库不存在");

        requireNonBlankIfPresent(body, "code");
        requireNonBlankIfPresent(body, "name");
        UpdateWrapper<WarehouseEntity> w = new UpdateWrapper<>();
        w.eq("id", id);
        setString(w, body, "code", "code");
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
        return id == null ? createSupplier(body) : updateSupplier(id, body);
    }

    private SupplierView createSupplier(JsonNode body) {
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
        return SupplierView.of(supplierMapper.selectById(entity.getId()));
    }

    private SupplierView updateSupplier(Long id, JsonNode body) {
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
        setString(w, body, "code", "code");
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
        return id == null ? createProduct(body) : updateProduct(id, body);
    }

    private ProductView createProduct(JsonNode body) {
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
        return ProductView.of(productMapper.selectById(entity.getId()));
    }

    private ProductView updateProduct(Long id, JsonNode body) {
        CatalogGuards.requireInTeam(productMapper.selectById(id),
                () -> productMapper.selectTeamIdById(id), ErrorCodes.CAT_3001, "商品不存在");

        requireNonBlankIfPresent(body, "product_code");
        requireNonBlankIfPresent(body, "name");
        UpdateWrapper<ProductEntity> w = new UpdateWrapper<>();
        w.eq("id", id);
        setString(w, body, "product_code", "product_code");
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
        return ProductView.of(productMapper.selectById(id));
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
        return id == null ? createSku(pathProductId, body) : updateSku(id, body);
    }

    private SkuView createSku(Long pathProductId, JsonNode body) {
        requireAllNonBlank(body, "sku_code", "name");
        Long productId = resolveProductIdForCreate(pathProductId, body);
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
        return SkuView.of(skuMapper.selectById(entity.getId()));
    }

    private SkuView updateSku(Long id, JsonNode body) {
        skuQueryService.requireInTeam(id);

        requireNonBlankIfPresent(body, "sku_code");
        requireNonBlankIfPresent(body, "name");
        UpdateWrapper<SkuEntity> w = new UpdateWrapper<>();
        w.eq("id", id);
        // product_id 不可变：请求体中的 product_id 一律忽略（换商品须新建 SKU，§6-B）
        setString(w, body, "sku_code", "sku_code");
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
     * <p>只由<b>创建</b>与<b>启停</b>路径调用；更新（PATCH）路径由 Controller 上的
     * {@code @Audited} 切面记录（见类级 Javadoc「审计埋点」）。
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

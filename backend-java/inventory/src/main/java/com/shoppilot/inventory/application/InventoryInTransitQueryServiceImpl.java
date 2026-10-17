package com.shoppilot.inventory.application;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * {@link InventoryInTransitQueryService} 的<b>占位实现</b>（M3-T16 骨架）。
 *
 * <p>见接口 javadoc 三条理由——本批（M3）返回空列表；M6（purchasing）落库后在此填实真实查询。
 * 标注 {@code @Service} 仅为让 Spring 容器装配成功，供后续 analysis（M4）依赖注入。
 */
@Service
public class InventoryInTransitQueryServiceImpl implements InventoryInTransitQueryService {

    @Override
    public List<InTransitItem> queryInTransit(long teamId, long warehouseId) {
        // M3-T16 占位：M6 实现。返回空列表，不查库、不抛异常。
        return List.of();
    }
}

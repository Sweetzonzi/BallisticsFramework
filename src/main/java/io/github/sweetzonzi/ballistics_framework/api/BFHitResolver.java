package io.github.sweetzonzi.ballistics_framework.api;

import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 命中前目标解析接口。
 * <p>
 * 由代理实体实现。在伤害管线之外（{@link BFDamageApi#hurt} 调用之前）执行，
 * 将原始的实体命中重定向到实际的物理伤害目标。
 * <p>
 * 与 {@link BFHurtTarget} 的职责分离：
 * <ul>
 *   <li>BFHitResolver 回答"打中了谁"——管线外</li>
 *   <li>BFHurtTarget 回答"打中了之后穿深多少、伤害多少"——管线内</li>
 * </ul>
 *
 * @see BFDamageApi#resolveHitTarget(net.minecraft.world.entity.Entity, Vec3, Vec3)
 */
public interface BFHitResolver {

    /**
     * 解析命中的实际伤害目标（主方法）。
     *
     * @param hitPoint 原版报告的命中点（世界坐标）
     * @param delta    搜索矢量，其模为搜索距离上限（m），方向为命中方向。
     *                 典型值：投射物的 deltaMovement 或其倍数。
     * @return 解析结果；{@code null} 表示实际未命中，投射物应继续飞行
     */
    @Nullable
    BFHitResolveResult resolveHit(Vec3 hitPoint, Vec3 delta);

    /**
     * 便利重载：从原版 HitResult 提取命中点后委托给二参数方法。
     * <p>
     * 默认实现取 {@code hitResult.getLocation()} 作为命中点。
     * 实现者可按需覆写以利用 HitResult 类型信息。
     *
     * @param hitResult 原版命中结果（取其 getLocation() 作为命中点）
     * @param delta     搜索矢量
     * @return 解析结果；null 表示未命中
     */
    @Nullable
    default BFHitResolveResult resolveHit(HitResult hitResult, Vec3 delta) {
        return resolveHit(hitResult.getLocation(), delta);
    }
}

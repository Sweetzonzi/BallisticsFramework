package io.github.sweetzonzi.ballistics_framework.api;

import net.minecraft.world.phys.Vec3;

/**
 * 命中解析结果——真正的目标 + 修正后的命中几何 + 扩展数据。
 *
 * @param actualTarget       真正的协议伤害目标（{@link BFHurtTarget} 实例）
 * @param correctedHitPoint  修正后的命中点世界坐标
 * @param correctedHitNormal 修正后的命中面法线
 * @param extensions         解析器提供的扩展数据，可直接用于构造 {@link BFDamageContext}。
 *                           调用方应先 {@link BFDamageExtensions#copy() copy} 后再追加自己的 key。
 *                           无扩展数据时为空容器。
 */
public record BFHitResolveResult(
    BFHurtTarget actualTarget,
    Vec3 correctedHitPoint,
    Vec3 correctedHitNormal,
    BFDamageExtensions extensions
) {
    /** 无扩展数据的便利构造器 */
    public BFHitResolveResult(BFHurtTarget actualTarget, Vec3 correctedHitPoint, Vec3 correctedHitNormal) {
        this(actualTarget, correctedHitPoint, correctedHitNormal, new BFDamageExtensions());
    }
}

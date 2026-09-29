package io.github.sweetzonzi.ballistics_framework.api;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 命中前目标解析接口。
 * <p>
 * 由代理对象实现——实体、物理体属主或其他包装体均可。在伤害管线之外
 * （{@link BFDamageApi#hurt} 调用之前）执行，将原始的命中重定向到实际的物理伤害目标。
 * <p>
 * 解析结果有两个使用场景：
 * <ul>
 *   <li>框架内的攻击者（如武器模组的投射物）主动调用
 *       {@link BFDamageApi#resolveHitTarget(Object, Vec3, Vec3)} 取得实际目标后再经管线施加伤害；</li>
 *   <li>协议外（原版）伤害命中代理对象时，由 {@code BFHurtInterceptor} 解析出实际目标并转发伤害
 *       ——此场景下代理只需实现本接口，不必同时实现 {@link BFHurtTarget}。</li>
 * </ul>
 * <p>
 * 与 {@link BFHurtTarget} 的职责分离：
 * <ul>
 *   <li>BFHitResolver 回答"打中了谁"——管线外</li>
 *   <li>BFHurtTarget 回答"打中了之后穿深多少、伤害多少"——管线内</li>
 * </ul>
 *
 * @see BFDamageApi#resolveHitTarget(Object, Vec3, Vec3)
 */
public interface BFHitResolver {

    /**
     * 解析命中的实际伤害目标（主方法）。
     * <p>
     * <b>契约：本方法必须是幂等且无副作用的纯查询。</b>
     * <p>
     * 投射物命中通常只解析一次（{@code Projectile#onHit} 阶段的结果经缓存传给
     * {@code Entity#hurt} 阶段）；但当伤害来源的 direct entity 不是那个投射物时
     * （伤害延迟结算、反射弹、非投射物来源在同一 tick 内先打中代理），{@code hurt}
     * 阶段取不到那份缓存，会按伤害来源重新解析一次——同一次命中因此仍可能被调用两次。
     * 实现不得依赖调用次数，也不得在解析过程中修改自身状态；
     * <b>同一组 {@code (hitPoint, delta)} 必须始终返回同一结果</b>，否则两阶段的相反结论
     * 会让投射物在"继续飞行"与"销毁"之间反复。
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

    /**
     * 由攻击者的速度矢量导出解析搜索矢量。
     * <p>
     * 规则：速率钳制在 [0.5, 4.0] 后取两倍位移，即搜索距离上限落在 [1.0, 8.0] m，
     * 方向与速度同向。用于把"伤害来源报告的位置"扩展成一段可用于精确复检的线段。
     * <p>
     * 速率低于 0.001 或为非有限值时返回 {@link Vec3#ZERO}；调用方应对零矢量做早退处理，
     * 避免以零长度线段发起解析。
     *
     * @param velocity 攻击者速度矢量
     * @return 搜索矢量；速率为零或无效时返回 {@link Vec3#ZERO}
     */
    static Vec3 searchDelta(Vec3 velocity) {
        double speed = velocity.length();
        if (!(speed >= 0.001)) return Vec3.ZERO;          // 同时排除 NaN
        if (!Double.isFinite(speed)) return Vec3.ZERO;
        // Java 17 兼容：Math.max/Math.min 替代 Math.clamp
        double clamped = Math.max(0.5, Math.min(4.0, speed));
        return velocity.scale(clamped * 2.0 / speed);
    }

    /**
     * 由协议外伤害来源导出解析搜索几何。
     * <p>
     * "搜索矢量"约定为 {@code hitPoint.add(delta)} 即搜索终点，因此本方法返回的
     * 第二分量是搜索方向与搜索距离的合体：方向为搜索方向，模为搜索距离（m）。
     * <p>
     * 按伤害类别分派，顺序即优先级：
     * <ol>
     *   <li><b>爆炸</b>（{@code EXPLOSION} / {@code PLAYER_EXPLOSION}）：起点取来源位置，
     *       方向指向 {@code self}，长度取两点距离。爆炸的 direct entity 可能是活体
     *       （如苦力怕）、TNT 或火球，按 direct entity 类型分派会把它分别误判为
     *       "活体近战"或"投射物飞行方向"，故本类必须先于后两类判定。
     *       本行只匹配这两个原版伤害类型；模组自定义的爆炸类型不会命中本行，
     *       但会落到第 4 行（同样是以来源位置指向 {@code self} 的径向几何），行为一致。</li>
     *   <li><b>投射物</b>：起点取投射物坐标，方向取投射物自身速度。仅当调用方
     *       未持有该投射物的命中结果缓存时才会走到这里。</li>
     *   <li><b>活体近战</b>：起点取攻击者眼位，方向取攻击者视线，长度固定 5.0 m。</li>
     *   <li><b>其他有源位置伤害</b>：起点取来源位置，方向指向 {@code self}，长度取两点距离。</li>
     *   <li><b>无源位置伤害</b>（虚空、饥饿、{@code /kill} 等）：返回 null，调用方应直接放行原版流程。</li>
     * </ol>
     * <p>
     * 本方法依赖 {@code DamageSource#getSourcePosition()}（返回显式记录位置，否则取
     * {@code getDirectEntity().position()}，无 direct entity 时为 null）。
     *
     * @param self   被命中的代理对象
     * @param source 原版伤害来源
     * @return 长度为 2 的数组 {@code [hitPoint, delta]}；无法构造几何时返回 null
     */
    @Nullable
    static Vec3[] searchRay(Entity self, DamageSource source) {
        // 爆炸优先：其 direct entity 类型不固定（苦力怕 / TNT / 火球），
        // 落到下面两个分支会分别得到"视线方向"或"飞行方向"这类错误几何
        if (source.is(DamageTypes.EXPLOSION) || source.is(DamageTypes.PLAYER_EXPLOSION)) {
            return radialRay(self, source.getSourcePosition());
        }
        if (source.getDirectEntity() instanceof Projectile projectile) {
            Vec3 from = projectile.position();
            Vec3 delta = searchDelta(projectile.getDeltaMovement());
            return delta.equals(Vec3.ZERO) ? null : new Vec3[]{from, delta};
        }
        if (source.getDirectEntity() instanceof LivingEntity attacker) {
            Vec3 from = attacker.getEyePosition();
            return new Vec3[]{from, attacker.getViewVector(1.0f).scale(5.0)};
        }
        return radialRay(self, source.getSourcePosition());
    }

    /**
     * 由来源位置指向 {@code self} 的径向搜索几何。
     *
     * @param self 被命中的代理对象
     * @param from 来源位置；为 null 表示该伤害没有可用的几何来源
     * @return 长度为 2 的数组 {@code [hitPoint, delta]}；来源位置缺失或与 {@code self} 重合时返回 null
     */
    @Nullable
    private static Vec3[] radialRay(Entity self, @Nullable Vec3 from) {
        if (from == null) return null;
        Vec3 toSelf = self.position().subtract(from);
        double distance = toSelf.length();
        if (!(distance >= 0.001)) return null;
        return new Vec3[]{from, toSelf};
    }
}

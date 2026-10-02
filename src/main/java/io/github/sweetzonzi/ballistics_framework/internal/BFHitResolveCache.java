package io.github.sweetzonzi.ballistics_framework.internal;

import io.github.sweetzonzi.ballistics_framework.api.BFHitResolveResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.jetbrains.annotations.Nullable;

/**
 * 投射物命中结果缓存（内部实现）。
 * <p>
 * 本接口存在于 internal 包中，但<b>必须是 public</b>：唯一的实现方
 * {@code ProjectileHitResolverMixin} 位于 mixin 包，它只能以 public 方法覆写接口方法，
 * 而接口方法不能比接口本身更可见。若把接口降为包私有，Mixin 的跨包接口应用
 * 会因可访问性不匹配而失败。public 只表达"能被 mixin 包看到"，
 * 不表达"属于公开 API"——外部模组不应引用本接口，理由与 internal 包内其他类一致。
 * <p>
 * 由 {@code ProjectileHitResolverMixin} 在 {@link Projectile} 自身的字段上实现，
 * 供 {@code BFHurtInterceptor} 在 {@code hurt} 阶段取用。存在的理由是：
 * {@code Projectile#onHit} 阶段持有精确几何、已经完成一次解析，而 {@code Entity#hurt}
 * 阶段未必能还原那份几何，重新解析可能得到相反结论、甚至把伤害送回代理自身。
 * <p>
 * <b>复用范围限于单次命中事件。</b>写入发生在 {@code resolveHit} 判定为真命中之后，
 * 因此能取到缓存就说明这次命中已被判定通过，其中记录的解析结果对本次命中有效，
 * 可直接使用。缓存不跨命中事件：穿透投射物在同一 tick 内会连续产生多次命中，
 * 每次命中都必须有自己的判定结果。
 * <p>
 * 缓存是一次性的：读取即清空，且仅在命中实体身份匹配时返回。
 * <p>
 * 被缓存的 {@link BFHitResolveResult} 会连同其中的 {@code extensions} 容器一起
 * 存活到 {@code hurt} 阶段。取用方按 {@code BFDamageApi#contextForResolvedTarget} 的规则
 * 读取 {@link BFHitResolveResult#actualTarget()}、修正几何与该容器（合并方向为"解析器覆盖同名键"），
 * 但<b>不得向该容器写入</b>——它可能与解析器持有的实例是同一个。
 */
public interface BFHitResolveCache {

    /** 一次命中的判定结果：被命中的实体 + 该次命中的解析结果 */
    record CachedResolve(Entity hitEntity, BFHitResolveResult result) {}

    /**
     * 写入本次命中的解析结果。应在 {@code resolveHit} 判定为真命中之后、
     * 放行原版流程之前调用。
     *
     * @param hitEntity 原版报告的命中实体，即随后 {@code hurt} 的接收者
     * @param result    本次命中的解析结果
     */
    void bf$cacheResolve(Entity hitEntity, BFHitResolveResult result);

    /**
     * 取出并清空缓存。仅当缓存的命中实体与 {@code hitEntity} 身份相等时返回该记录，
     * 其余情况返回 null。无论命中与否都清空，缓存不可跨命中事件复用。
     *
     * @param hitEntity 当前正在承受伤害的实体
     * @return 本次命中的解析结果；无记录、或记录的命中实体不是 {@code hitEntity} 时返回 null
     */
    @Nullable
    CachedResolve bf$takeResolve(Entity hitEntity);
}

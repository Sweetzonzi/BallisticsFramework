package io.github.sweetzonzi.ballistics_framework.example.entity;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageApi;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageExtensions;
import io.github.sweetzonzi.ballistics_framework.api.BFHitResolveResult;
import io.github.sweetzonzi.ballistics_framework.api.BFHitResolver;
import io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 示例代理实体（宿主）。
 * <p>
 * 实现 {@link BFHitResolver} 而<b>不</b>实现 {@link BFHurtTarget}：它只回答"这一下实际
 * 打到了哪个对象"，把伤害交给它持有的零件（{@link BFHurtTarget}，不要求是实体），自身
 * 不承担协议穿甲判定。这是"宿主 + 零件"拓扑的形态——宿主是一个实体，零件是挂在宿主身上
 * 的一份数据（第 3 方模组的 {@code SubPart} 即如此），因此 {@code getBFEntity()} 的默认
 * 实现对零件返回 null，零件也从不接收原版伤害。
 * <p>
 * 代理实体的伤害去向：
 * <ul>
 *   <li>协议入口 {@link BFDamageApi#hurt} 不解析路由，调用方需自行调用
 *       {@link BFDamageApi#resolveHitTarget}；</li>
 *   <li>原版伤害命中本实体时，由拦截器按 {@link #resolveHit} 的结果转发。</li>
 * </ul>
 * 本实体不承担协议穿甲判定（它不实现 {@link BFHurtTarget}），只保留一把测试用的调用记录。
 * 无自然生成，仅用于开发测试。
 */
public class ExampleProxyEntity extends PathfinderMob implements BFHitResolver {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 本宿主的零件；null 表示没有零件承受伤害，{@link #resolveHit} 按未命中处理。 */
    @Nullable
    private BFHurtTarget part;

    private int resolveHitCalls;
    private int hurtCalls;
    private float lastHurtAmount;
    private boolean hasContextAtHurt;

    /**
     * {@link #resolveHit} 返回的修正命中点相对入参命中点的偏移；{@link Vec3#ZERO} 表示原样透传。
     * 用于验证"解析出的修正几何会被应用到转发上下文"。
     */
    private Vec3 hitCorrection = Vec3.ZERO;

    /**
     * {@link #resolveHit} 返回的修正命中面法线；{@link Vec3#ZERO} 表示未修正
     * （转发方应保留原上下文的法线）。
     */
    private Vec3 normalCorrection = Vec3.ZERO;

    public ExampleProxyEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        LOGGER.debug("[BF-Example] 代理实体已创建: pos={}", position());
    }

    /**
     * 注册属性：20 HP，基础移动速度为 0（不动宿主）。
     */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0);
    }

    // ======================== 零件装配（示例拓扑） ========================

    /**
     * 装配本宿主持有的零件。
     *
     * @param part 零件；null 表示未装配，{@link #resolveHit} 将返回 null
     */
    public void setPart(@Nullable BFHurtTarget part) {
        this.part = part;
    }

    /** @return 当前持有的零件；未装配时为 null */
    @Nullable
    public BFHurtTarget getPart() {
        return this.part;
    }

    /**
     * 设置 {@link #resolveHit} 返回的修正几何。
     * <p>
     * 用于验证"解析出的修正几何会被应用到转发上下文"：转发方应把
     * {@code 入参命中点 + hitOffset} 与 {@code hitNormal} 写进交给实际目标的上下文。
     *
     * @param hitOffset 相对入参命中点的偏移；{@link Vec3#ZERO} 表示原样透传
     * @param hitNormal 修正后的命中面法线；{@link Vec3#ZERO} 表示未修正
     */
    public void setHitCorrection(Vec3 hitOffset, Vec3 hitNormal) {
        this.hitCorrection = hitOffset;
        this.normalCorrection = hitNormal;
    }

    // ======================== BFHitResolver 实现 ========================

    /**
     * 把命中解析到持有的零件上，并按 {@link #setHitCorrection} 报告的修正几何返回。
     * <p>
     * 幂等且无副作用（接口契约），但会计数以便验证"同一次命中只解析一次"。
     * 零件若实现 {@link ResolveExtensionsCarrier}，其扩展容器会随结果一并返回——
     * 这是给测试夹具用的窄接口，生产实现自行构造 {@link BFDamageExtensions} 即可。
     *
     * @return 零件；未装配时返回 null（视为未命中）
     */
    @Override
    @Nullable
    public BFHitResolveResult resolveHit(Vec3 hitPoint, Vec3 delta) {
        this.resolveHitCalls++;
        BFHurtTarget actual = this.part;
        if (actual == null) return null;
        BFDamageExtensions exts = (actual instanceof ResolveExtensionsCarrier carrier)
                ? carrier.bf$resolveExtensions() : new BFDamageExtensions();
        return new BFHitResolveResult(actual, hitPoint.add(this.hitCorrection),
                this.normalCorrection, exts);
    }

    /**
     * 零件侧的窄接口：声明本零件希望随解析结果返回的扩展容器。
     * <p>
     * 真实模组的解析器会自行构造 {@link BFDamageExtensions}（例如写入子部件标识）；
     * 本接口只是示例夹具把这件事外置的通道，不属于协议的一部分。
     */
    public interface ResolveExtensionsCarrier {

        /** @return 本零件希望随解析结果返回的扩展容器 */
        BFDamageExtensions bf$resolveExtensions();
    }

    // ======================== hurt 记录 ========================

    /**
     * 记录本次 {@code hurt} 的入参，然后交回原版流程。
     * <p>
     * {@link #hasContextAtHurt} 记录的是"进入本方法时上下文栈顶是否为本实体"——投递入口
     * 会把承载者压为栈顶，因此投递期内该值为 true。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        this.hurtCalls++;
        this.lastHurtAmount = amount;
        this.hasContextAtHurt = BFDamageApi.hasContextFor(this);
        return super.hurt(source, amount);
    }

    // ======================== 调用记录（供 GameTest 断言） ========================

    /** @return {@link #resolveHit} 被调用的次数 */
    public int getResolveHitCalls() {
        return this.resolveHitCalls;
    }

    /** @return {@link #hurt} 被调用的次数 */
    public int getHurtCalls() {
        return this.hurtCalls;
    }

    /** @return 最近一次 {@link #hurt} 收到的伤害量 */
    public float getLastHurtAmount() {
        return this.lastHurtAmount;
    }

    /** @return 最近一次 {@link #hurt} 进入时，本实体是否已是上下文栈顶 */
    public boolean hadContextAtHurt() {
        return this.hasContextAtHurt;
    }

    // ======================== BFHurtTarget 缺失是有意的 ========================

    // 本类刻意不实现 BFHurtTarget：
    //   - 它不回答"我能吸收多少"，装甲层由零件承担；
    //   - 它一旦声明自己是协议伤害目标，协议管线里就会多出一条与零件装甲重叠的判定；
    //   - 拦截器据此走"情况 3（BFHitResolver）"而不是"情况 2（BFHurtTarget）"。
    // 承载者形态下的投递（BFDamageApi.deliverTo）因此只跑它穿戴的护甲层，本体层不跑。
}

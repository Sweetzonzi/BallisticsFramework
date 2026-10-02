package io.github.sweetzonzi.ballistics_framework.example.entity;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.api.ArmorLevel;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageApi;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageContext;
import io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget;
import io.github.sweetzonzi.ballistics_framework.api.PenetrationResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 示例承载者实体。
 * <p>
 * 它同时是 {@link BFHurtTarget} 与实体，因此两种身份可对照：
 * <ul>
 *   <li>作为协议伤害的<b>目标</b>（{@link BFDamageApi#hurt}）：本体层跑
 *       {@link #resolvePenetration} → {@link #calculateFinalDamage}；</li>
 *   <li>作为<b>承载者</b>（{@link BFDamageApi#deliverTo}）：本体层<b>不</b>跑，只跑它穿戴的
 *       护甲层，然后落地。</li>
 * </ul>
 * 两种身份都汇集到 {@link #hurt}，因此"投递不重跑本体层"可以直接由调用计数观察。
 * <p>
 * 关于 {@link #primeHurtCooldown}：原版 {@code LivingEntity} 把 {@code lastHurt} 声明为
 * {@code protected}，测试无法从外部直接摆出"无敌帧窗口内的第二次命中"，故由本类暴露一个
 * 探针方法。无自然生成，仅用于开发测试。
 */
public class ExampleCarrierEntity extends PathfinderMob implements BFHurtTarget {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 本体层调用计数 ----
    private int resolvePenetrationCalls;
    private int calculateFinalDamageCalls;

    // ---- hurt 入参记录 ----
    private int hurtCalls;
    private float lastHurtAmount;
    private boolean hasContextAtHurt;

    // ---- 投递入口自检探针 ----
    @Nullable
    private BFDamageContext redeliverProbe;
    private boolean redeliverAttempted;
    private boolean redeliverRejected;

    public ExampleCarrierEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        LOGGER.debug("[BF-Example] 承载者实体已创建: pos={}", position());
    }

    /**
     * 注册属性：20 HP，基础移动速度为 0（不动承载者）。
     */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0);
    }

    // ======================== BFHurtTarget 实现 ========================

    /** 裸体承载者：本体层无装甲（UNARMORED_1）。 */
    @Override
    public ArmorLevel getArmorLevel(BFDamageContext ctx) {
        return ArmorLevel.UNARMORED_1;
    }

    @Override
    public PenetrationResult resolvePenetration(BFDamageContext ctx) {
        this.resolvePenetrationCalls++;
        return BFHurtTarget.super.resolvePenetration(ctx);
    }

    @Override
    public float calculateFinalDamage(BFDamageContext ctx, PenetrationResult result) {
        this.calculateFinalDamageCalls++;
        return BFHurtTarget.super.calculateFinalDamage(ctx, result);
    }

    /**
     * 落地并记录入参，然后交回原版流程。
     * <p>
     * {@link #hasContextAtHurt} 记录的是"进入本方法时本实体是否已是上下文栈顶"——投递入口
     * 会把承载者压为栈顶，因此投递期内该值为 true。
     * <p>
     * 若已被 {@link #armRedeliverProbe} 装上探针，本方法内会以该上下文再投递一次自身。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        this.hurtCalls++;
        this.lastHurtAmount = amount;
        this.hasContextAtHurt = BFDamageApi.hasContextFor(this);

        BFDamageContext probe = this.redeliverProbe;
        if (probe != null) {
            this.redeliverProbe = null;
            this.redeliverAttempted = true;
            // 本实体此刻已是上下文栈顶，因此这次投递应被入口自检拒绝
            this.redeliverRejected = !BFDamageApi.deliverTo(this, probe);
        }

        return super.hurt(source, amount);
    }

    /**
     * 原版伤害不走协议管线，退回原版流程。
     * <p>
     * 即：本实体只接受经由 {@link BFDamageApi#hurt} 或 {@link BFDamageApi#deliverTo}
     * 进入的协议伤害，原版伤害不会触发本体的穿甲判定。
     */
    @Nullable
    @Override
    public BFDamageContext createContextFromVanilla(DamageSource source, float amount) {
        return null;
    }

    // ======================== 测试探针 ========================

    /**
     * 装入投递自检探针：本实体下一次进入 {@link #hurt} 时会以该上下文再投递一次自身。
     * <p>
     * 用于验证"承载者已在栈顶时投递被拒绝"。
     *
     * @param ctx 探针使用的投递上下文；null 表示撤下探针
     */
    public void armRedeliverProbe(@Nullable BFDamageContext ctx) {
        this.redeliverProbe = ctx;
        this.redeliverAttempted = false;
        this.redeliverRejected = false;
    }

    /**
     * 把本实体摆进原版无敌帧窗口：{@code invulnerableTime > 10} 且
     * {@code lastHurt >= amount}，使随后落在本实体上的伤害被原版拒绝。
     *
     * @param invulnerableTime 无敌帧剩余 tick
     * @param lastHurt         窗口内已承受过的伤害量
     */
    public void primeHurtCooldown(int invulnerableTime, float lastHurt) {
        this.invulnerableTime = invulnerableTime;
        this.lastHurt = lastHurt;
    }

    // ======================== 调用记录（供 GameTest 断言） ========================

    /** @return 本体层 {@code resolvePenetration} 被调用的次数 */
    public int getResolvePenetrationCalls() {
        return this.resolvePenetrationCalls;
    }

    /** @return 本体层 {@code calculateFinalDamage} 被调用的次数 */
    public int getCalculateFinalDamageCalls() {
        return this.calculateFinalDamageCalls;
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

    /** @return 探针是否已被触发 */
    public boolean wasRedeliverAttempted() {
        return this.redeliverAttempted;
    }

    /** @return 探针触发的那次投递是否被入口自检拒绝 */
    public boolean wasRedeliverRejected() {
        return this.redeliverRejected;
    }
}

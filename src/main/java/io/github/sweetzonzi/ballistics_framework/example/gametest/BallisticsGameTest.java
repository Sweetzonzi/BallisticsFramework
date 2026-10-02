package io.github.sweetzonzi.ballistics_framework.example.gametest;

import io.github.sweetzonzi.ballistics_framework.api.ArmorLevel;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageApi;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageContext;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageHandler;
import io.github.sweetzonzi.ballistics_framework.api.BFHitResolveResult;
import io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget;
import io.github.sweetzonzi.ballistics_framework.api.PenetrationResult;
import io.github.sweetzonzi.ballistics_framework.example.ExampleContent;
import io.github.sweetzonzi.ballistics_framework.example.entity.ExampleCarrierEntity;
import io.github.sweetzonzi.ballistics_framework.example.entity.ExampleProxyEntity;
import io.github.sweetzonzi.ballistics_framework.example.entity.ExampleTargetEntity;
import io.github.sweetzonzi.ballistics_framework.example.item.ExampleDeliveryArmorItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * BallisticsFramework 穿甲管线与承载者投递的自动化 GameTest。
 * <p>
 * 覆盖 19 个场景：
 * <ul>
 *   <li><b>8 个穿甲管线场景</b>：裸打靶子、同级击穿、低级阻挡、越级击穿、投射物越级命中、
 *       投射物打裸体靶子、普通实体回退、原版伤害回退——均为手工构造上下文后直接调用
 *       {@link BFDamageApi#hurt}。</li>
 *   <li><b>11 个承载者投递场景</b>（{@link BFDamageApi#deliverTo}）：第一趟路由、投递落地、
 *       投递不重新路由、贴身护甲击穿、贴身护甲挡下、本体层不跑、跳过贴身护甲、
 *       入口自检拒绝重复投递、零伤害早退、免疫、无敌帧窗口。这些场景是仓库内首次把
 *       "实体 {@code hurt} → mixin → 拦截器"这条链路纳入自动化验证——投递落地经过它。</li>
 * </ul>
 * agent 或开发者只需运行 {@code gradlew runGameTestServer} 即可自动验证全部场景，
 * 无需手动进入世界、穿戴护甲、发射投射物等。
 * <p>
 * 结构模板：{@code empty_arena}（5×3×5 石砖地板空场地）。
 * <p>
 * 断言精度说明：浮点数比较使用 {@link Math#abs 差值 ≤ 0.01} 容忍浮点误差。
 */
@GameTestHolder("ballistics_framework")
@PrefixGameTestTemplate(false)
public class BallisticsGameTest {

    private static final float EPSILON = 0.01f;
    private static final ResourceLocation ARENA_ID =
            ResourceLocation.fromNamespaceAndPath("ballistics_framework", "empty_arena");

    /**
     * 在所有GameTest批次开始前，程序化创建测试场地结构模板。
     * <p>
     * 通过 StructureTemplate.fillFromWorld 在 ServerLevel 中搭建实体内存中的方块，
     * 然后捕获为标准结构模板并保存到 StructureTemplateManager，
     * 完全避免手动编辑 .nbt 文件的复杂性和格式错误风险。
     */
    @BeforeBatch(batch = "defaultBatch")
    public static void beforeBatch(ServerLevel level) {
        BlockPos origin = new BlockPos(0, 0, 0);
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                level.setBlock(origin.offset(x, 0, z),
                        Blocks.STONE_BRICKS.defaultBlockState(), 3);
            }
        }
        StructureTemplate template = level.getStructureManager().getOrCreate(ARENA_ID);
        template.fillFromWorld(level, origin, new Vec3i(5, 3, 5), false, Blocks.STRUCTURE_VOID);
        level.getStructureManager().save(ARENA_ID);
    }

    // ======================== 场景1：近战武器裸打靶子 ========================

    /**
     * 近战武器（60mm穿深）对裸体靶子（0mm护甲）——越级击穿，伤害系数 1.0。
     * <p>
     * 对应管线分支：BFHurtTarget 直接管线（分支1）。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testMeleeAgainstUnarmoredTarget(GameTestHelper helper) {
        ExampleTargetEntity target = spawnTarget(helper, new BlockPos(2, 1, 2));

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BFDamageContext ctx = BFDamageContext.builder()
                .source(helper.getLevel().damageSources().mobAttack(player))
                .baseDamage(15f)
                .hitVelocity(Vec3.ZERO)
                .hitPoint(Vec3.ZERO)
                .hitNormal(new Vec3(0, 1, 0))
                .penetration(60f)
                .build();

        float dealt = BFDamageApi.hurt(target, ctx);

        assertFloatEquals(15.0f, dealt, "裸体靶子应全伤穿透");
        helper.succeed();
    }

    // ======================== 场景2：同级击穿（穿透等级 == 护甲等级） ========================

    /**
     * 近战武器（30mm穿深，HEAVY级别）对穿戴示例护甲的靶子（40mm，HEAVY级别）。
     * <p>
     * 同级击穿：穿透等级 = 护甲等级（均为 HEAVY），伤害系数 0.65：15 × 0.65 = 9.75。
     * <p>
     * 对应管线分支：复合目标——BFHurtTarget + BFArmorMaterial 护甲（分支0）。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testMeleeSameLevelPenetration(GameTestHelper helper) {
        ExampleTargetEntity target = spawnTarget(helper, new BlockPos(2, 1, 2));
        equipExampleArmor(target);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BFDamageContext ctx = BFDamageContext.builder()
                .source(helper.getLevel().damageSources().mobAttack(player))
                .baseDamage(15f)
                .hitVelocity(Vec3.ZERO)
                .hitPoint(Vec3.ZERO)
                .hitNormal(new Vec3(0, 1, 0))
                .penetration(30f)
                .build();

        float dealt = BFDamageApi.hurt(target, ctx);

        assertFloatEquals(9.75f, dealt, "同级击穿应×0.65");
        helper.succeed();
    }

    // ======================== 场景3：低级打高级（穿深不足，未击穿） ========================

    /**
     * 近战武器（15mm穿深，MEDIUM级别）对穿戴示例护甲的靶子（40mm，HEAVY级别）。
     * <p>
     * 低级打高级：穿透等级（MEDIUM）低于护甲等级（HEAVY），canDefeat 判定失败 → BLOCKED，
     * calculateFinalDamage 返回 0。
     * <p>
     * 对应管线分支：复合目标 + 护甲阻挡。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testMeleeLowerLevelBlocked(GameTestHelper helper) {
        ExampleTargetEntity target = spawnTarget(helper, new BlockPos(2, 1, 2));
        equipExampleArmor(target);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BFDamageContext ctx = BFDamageContext.builder()
                .source(helper.getLevel().damageSources().mobAttack(player))
                .baseDamage(15f)
                .hitVelocity(Vec3.ZERO)
                .hitPoint(Vec3.ZERO)
                .hitNormal(new Vec3(0, 1, 0))
                .penetration(15f)
                .build();

        float dealt = BFDamageApi.hurt(target, ctx);

        assertFloatEquals(0f, dealt, "低级打高级应被完全阻挡");
        helper.succeed();
    }

    // ======================== 场景4：越级击穿（穿深远高于护甲） ========================

    /**
     * 近战武器（60mm穿深，SUPER_HEAVY_1级别）对穿戴示例护甲的靶子（40mm，HEAVY级别）。
     * <p>
     * 越级击穿：穿透等级（SUPER_HEAVY_1）高于护甲等级（HEAVY），伤害系数 1.0：15 × 1.0 = 15.0。
     * <p>
     * 对应管线分支：复合目标——BFHurtTarget + BFArmorMaterial 护甲（分支0）。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testMeleeOvermatchPenetration(GameTestHelper helper) {
        ExampleTargetEntity target = spawnTarget(helper, new BlockPos(2, 1, 2));
        equipExampleArmor(target);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BFDamageContext ctx = BFDamageContext.builder()
                .source(helper.getLevel().damageSources().mobAttack(player))
                .baseDamage(15f)
                .hitVelocity(Vec3.ZERO)
                .hitPoint(Vec3.ZERO)
                .hitNormal(new Vec3(0, 1, 0))
                .penetration(60f)
                .build();

        float dealt = BFDamageApi.hurt(target, ctx);

        assertFloatEquals(15.0f, dealt, "越级击穿应全伤");
        helper.succeed();
    }

    // ======================== 场景5：投射物越级击中穿护甲的靶子 ========================

    /**
     * 投射物（200mm穿深，SUPER_HEAVY_3级别）对穿戴示例护甲的靶子（40mm，HEAVY级别）。
     * <p>
     * 严重越级击穿（200mm >> 40mm），伤害系数 1.0，伤害 = 25。
     * 同时验证回调节点：PENETRATED + OVERMATCH（穿深 > 40×1.5=60）。
     * <p>
     * 对应管线分支：复合目标 + 投射物参数 + 回调验证。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testProjectileAgainstArmoredTarget(GameTestHelper helper) {
        ExampleTargetEntity target = spawnTarget(helper, new BlockPos(2, 1, 2));
        equipExampleArmor(target);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BFDamageContext ctx = BFDamageContext.builder()
                .source(helper.getLevel().damageSources().playerAttack(player))
                .baseDamage(25f)
                .hitVelocity(new Vec3(0, 0, 2))
                .hitPoint(Vec3.ZERO)
                .hitNormal(new Vec3(0, 0, 1))
                .penetration(200f)
                .handler(new CallbackRecorder())
                .build();

        CallbackRecorder recorder = (CallbackRecorder) ctx.getHandler();
        float dealt = BFDamageApi.hurt(target, ctx);

        assertFloatEquals(25.0f, dealt, "严重越级击穿应全伤");
        assertTrue(recorder.penetrated, "应触发 PENETRATED 回调");
        assertTrue(recorder.overmatch, "穿深远超护甲应触发 OVERMATCH 回调");
        assertTrue(!recorder.blocked, "不应触发 BLOCKED 回调");
        assertTrue(!recorder.ricochet, "不应触发 RICOCHET 回调");
        helper.succeed();
    }

    // ======================== 场景6：投射物打裸体靶子 ========================

    /**
     * 投射物（200mm）对裸体靶子（0mm）——越级击穿且碾压，伤害 25。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testProjectileAgainstUnarmoredTarget(GameTestHelper helper) {
        ExampleTargetEntity target = spawnTarget(helper, new BlockPos(2, 1, 2));

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BFDamageContext ctx = BFDamageContext.builder()
                .source(helper.getLevel().damageSources().playerAttack(player))
                .baseDamage(25f)
                .hitVelocity(new Vec3(0, 0, 2))
                .hitPoint(Vec3.ZERO)
                .hitNormal(new Vec3(0, 0, 1))
                .penetration(200f)
                .handler(new CallbackRecorder())
                .build();

        CallbackRecorder recorder = (CallbackRecorder) ctx.getHandler();
        float dealt = BFDamageApi.hurt(target, ctx);

        assertFloatEquals(25.0f, dealt, "越级击穿裸体靶子应全伤");
        assertTrue(recorder.penetrated, "应触发 PENETRATED 回调");
        assertTrue(recorder.overmatch, "应触发 OVERMATCH 回调");
        helper.succeed();
    }

    // ======================== 场景7：投射物命中普通实体（原版回退） ========================

    /**
     * 对非协议实体（普通生物）发起协议伤害——应走原版回退路径（分支3）。
     * <p>
     * 原始验证矩阵中"投射物打方块"的等价测试：验证 BFDamageApi 对不实现
     * BFHurtTarget 的实体不会崩溃，而是正确回退到原版 Entity.hurt()。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testHurtAgainstVanillaEntity(GameTestHelper helper) {
        Mob zombie = helper.spawnWithNoFreeWill(
                net.minecraft.world.entity.EntityType.ZOMBIE,
                new BlockPos(2, 1, 2));

        float hpBefore = zombie.getHealth();

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BFDamageContext ctx = BFDamageContext.builder()
                .source(helper.getLevel().damageSources().playerAttack(player))
                .baseDamage(5f)
                .hitVelocity(Vec3.ZERO)
                .hitPoint(Vec3.ZERO)
                .hitNormal(new Vec3(0, 1, 0))
                .penetration(200f)
                .build();

        float dealt = BFDamageApi.hurt(zombie, ctx);

        assertTrue(dealt > 0f, "原版回退应返回非零伤害");
        assertTrue(zombie.getHealth() < hpBefore, "僵尸应受到实际伤害");
        helper.succeed();
    }

    // ======================== 场景8：原版伤害走原版流程 ========================

    /**
     * 对靶子实体发起原版伤害（绕开 BFDamageApi）——应走原版，不触发协议管线。
     * <p>
     * ExampleTargetEntity.createContextFromVanilla() 返回 null，因此原版伤害
     * 不会被 Mixin 拦截转为协议伤害。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testVanillaDamageFallback(GameTestHelper helper) {
        ExampleTargetEntity target = spawnTarget(helper, new BlockPos(2, 1, 2));

        float hpBefore = target.getHealth();

        target.hurt(helper.getLevel().damageSources().generic(), 5.0f);

        float hpAfter = target.getHealth();
        assertTrue(hpAfter < hpBefore, "原版伤害应正常扣血");
        helper.succeed();
    }

    // ======================== 场景9：承载者投递——第一趟路由到零件 ========================

    /**
     * 第一趟：宿主实现 {@code BFHitResolver}、零件实现 {@code BFHurtTarget}。
     * <p>
     * 协议入口不做路由（{@code BFDamageApi.hurt} 的方法体内不调用 {@code resolveHit}），
     * 因此调用方自行解析后再把伤害交给零件。
     * <p>
     * 断言：零件承受全部伤害、{@code resolveHit} 被调用一次、宿主生命值不变
     * （第一趟不含投递）、宿主 {@code hurt} 未被触碰。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testDeliverFirstPassRoutesToPart(GameTestHelper helper) {
        ExampleProxyEntity host = spawnProxy(helper, new BlockPos(2, 1, 2));
        TestPart part = new TestPart();
        host.setPart(part);
        Player attacker = helper.makeMockPlayer(GameType.SURVIVAL);

        BFHitResolveResult resolved = BFDamageApi.resolveHitTarget(
                (Object) host, chestHit(host), new Vec3(0, 0, 2));
        assertTrue(resolved != null, "宿主应解析出零件");
        assertTrue(resolved.actualTarget() == part, "解析结果应是宿主持有的那个零件");
        assertTrue(host.getPart() == part, "宿主应仍持有该零件");

        float dealt = BFDamageApi.hurt(resolved.actualTarget(),
                deliveryContext(helper, attacker, host, 15f, 60f, null));

        assertFloatEquals(15f, dealt, "裸体零件应全伤穿透");
        assertFloatEquals(15f, part.getDamageTaken(), "零件应承受全部伤害");
        assertTrue(part.getHurtCalls() == 1, "零件 hurt 应被调用一次");
        assertFloatEquals(20f, host.getHealth(), "第一趟不投递，宿主生命值应不变");
        assertTrue(host.getResolveHitCalls() == 1, "路由应只解析一次");
        assertTrue(host.getHurtCalls() == 0, "第一趟不应触碰宿主 hurt");
        helper.succeed();
    }

    // ======================== 场景10：投递落地 ========================

    /**
     * 第二趟：装配体汇总后把结算结果投递给承载者。
     * <p>
     * 宿主不实现 {@code BFHurtTarget}，所以投递只跑它穿戴的护甲层（此处无护甲），
     * 随后交原版 {@code hurt} 落地。
     * <p>
     * 断言：投递返回 true、承载者生命值按投递量下降、投递期内承载者已是上下文栈顶
     * （首次把"实体 hurt → mixin → 拦截器"这条链路纳入自动化验证）。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testDeliverLandsOnCarrier(GameTestHelper helper) {
        ExampleProxyEntity host = spawnProxy(helper, new BlockPos(2, 1, 2));
        TestPart part = new TestPart();
        host.setPart(part);
        Player attacker = helper.makeMockPlayer(GameType.SURVIVAL);

        BFDamageContext firstPass = deliveryContext(helper, attacker, host, 15f, 60f, null);
        BFHitResolveResult resolved = BFDamageApi.resolveHitTarget(
                (Object) host, chestHit(host), new Vec3(0, 0, 2));
        float settled = BFDamageApi.hurt(resolved.actualTarget(), firstPass);
        assertFloatEquals(15f, settled, "第一趟应结算在零件上");

        float hpBefore = host.getHealth();
        boolean landed = BFDamageApi.deliverTo(host, firstPass.childContext(settled, 30f));

        assertTrue(landed, "投递应落地");
        assertTrue(host.getHealth() < hpBefore, "宿主生命值应按投递量下降");
        assertFloatEquals(settled, host.getLastHurtAmount(), "宿主 hurt 应收到投递的伤害量");
        assertTrue(host.hadContextAtHurt(), "投递期内宿主应已是上下文栈顶");
        assertTrue(host.getHurtCalls() == 1, "落地应只调用一次宿主 hurt");
        helper.succeed();
    }

    // ======================== 场景11：投递不得被重新路由 ========================

    /**
     * 承载者同时是 {@code BFHitResolver}（宿主兼解析者）时的投递。
     * <p>
     * 投递不做路由，且压栈使拦截器命中"情况 1"直接放行原版，因此整条链路在一次
     * {@code deliverTo} 内结束——否则 {@code resolveHit} 会再次回到触发它的零件，
     * 即"投递被路由回起点"的递归形态。
     * <p>
     * 断言：投递返回 true、{@code resolveHit} 的调用次数在投递期内不增加、不发生栈溢出。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testDeliverDoesNotReroute(GameTestHelper helper) {
        ExampleProxyEntity host = spawnProxy(helper, new BlockPos(2, 1, 2));
        host.setPart(new TestPart());
        Player attacker = helper.makeMockPlayer(GameType.SURVIVAL);

        BFDamageContext firstPass = deliveryContext(helper, attacker, host, 15f, 60f, null);
        BFHitResolveResult resolved = BFDamageApi.resolveHitTarget(
                (Object) host, chestHit(host), new Vec3(0, 0, 2));
        BFDamageApi.hurt(resolved.actualTarget(), firstPass);
        int resolveCallsAfterFirstPass = host.getResolveHitCalls();

        boolean landed = BFDamageApi.deliverTo(host, firstPass.childContext(15f, 30f));

        assertTrue(landed, "投递应落地");
        assertTrue(host.getResolveHitCalls() == resolveCallsAfterFirstPass,
                "投递不得重新解析路由：resolveHit 的调用次数不应增加");
        helper.succeed();
    }

    // ======================== 场景12：贴身护甲层独立击穿 ========================

    /**
     * 承载者穿戴 {@code BFArmorMaterial} 护甲，投递穿深大于该护甲 RHA。
     * <p>
     * 示例投递护甲 {@code ExampleDeliveryArmorItem} 的 RHA 为 15mm（MEDIUM 等级带），
     * 穿深 18mm 同属 MEDIUM → 同级击穿，{@code calculateFinalDamage} 输出 15 × 0.65 = 9.75。
     * <p>
     * 断言：投递返回 true、{@code carrier.hurt} 收到折算后的量、该层 {@code before*} /
     * {@code on*} 各触发一次、{@code afterHurt} 触发一次、三件套各调用一次、本体层不跑。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testDeliverCarrierArmorLayerPenetrates(GameTestHelper helper) {
        ExampleDeliveryArmorItem.resetCounters();
        ExampleCarrierEntity carrier = spawnCarrier(helper, new BlockPos(2, 1, 2));
        equipDeliveryArmor(carrier);
        Player attacker = helper.makeMockPlayer(GameType.SURVIVAL);
        DeliveryRecorder recorder = new DeliveryRecorder();

        float hpBefore = carrier.getHealth();
        boolean landed = BFDamageApi.deliverTo(carrier,
                deliveryContext(helper, attacker, carrier, 15f, 18f, recorder));

        assertTrue(landed, "击穿贴身护甲后投递应落地");
        assertFloatEquals(9.75f, carrier.getLastHurtAmount(),
                "carrier.hurt 应收到 calculateFinalDamage 折算后的量");
        assertTrue(carrier.getHealth() < hpBefore, "承载者生命值应下降");
        assertTrue(recorder.beforePenetrated == 1, "贴身护甲层 before* 应触发一次");
        assertTrue(recorder.onPenetrated == 1, "贴身护甲层 on* 应触发一次");
        assertTrue(recorder.total() == 2, "贴身护甲层回调总数应为 2（before* + on*）");
        assertTrue(ExampleDeliveryArmorItem.getModifyPenetrationCalls() == 1,
                "护甲层三件套：modifyPenetration 应调用一次");
        assertTrue(ExampleDeliveryArmorItem.getResolvePenetrationCalls() == 1,
                "护甲层三件套：resolvePenetration 应调用一次");
        assertTrue(ExampleDeliveryArmorItem.getCalculateFinalDamageCalls() == 1,
                "护甲层三件套：calculateFinalDamage 应调用一次");
        assertTrue(ExampleDeliveryArmorItem.getAfterHurtCalls() == 1, "armorAfterHurt 应触发一次");
        assertTrue(carrier.getResolvePenetrationCalls() == 0, "投递不跑承载者本体层");
        helper.succeed();
    }

    // ======================== 场景13：贴身护甲层挡下 ========================

    /**
     * 承载者穿戴协议护甲，投递穿深小于该护甲 RHA——贴身护甲独立判定为未击穿。
     * <p>
     * 断言：投递返回 false、承载者生命值不变、{@code carrier.hurt} 未被调用、该层
     * {@code onBlocked} 触发一次、{@code afterHurt} 仍触发一次（挡下也是一次命中，耐久应消耗）。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testDeliverCarrierArmorBlocks(GameTestHelper helper) {
        ExampleDeliveryArmorItem.resetCounters();
        ExampleCarrierEntity carrier = spawnCarrier(helper, new BlockPos(2, 1, 2));
        equipDeliveryArmor(carrier);
        Player attacker = helper.makeMockPlayer(GameType.SURVIVAL);
        DeliveryRecorder recorder = new DeliveryRecorder();

        // 穿深 5mm < 护甲 RHA 15mm → 未击穿，calculateFinalDamage 输出 0
        float hpBefore = carrier.getHealth();
        boolean landed = BFDamageApi.deliverTo(carrier,
                deliveryContext(helper, attacker, carrier, 15f, 5f, recorder));

        assertTrue(!landed, "未击穿贴身护甲时投递不落地");
        assertFloatEquals(hpBefore, carrier.getHealth(), "被护甲挡下时承载者生命值不应变化");
        assertTrue(carrier.getHurtCalls() == 0, "被护甲挡下时不应调用 carrier.hurt");
        assertTrue(recorder.beforeBlocked == 1, "贴身护甲层 before* 应触发一次");
        assertTrue(recorder.onBlocked == 1, "贴身护甲层 on* 应触发一次");
        assertTrue(recorder.total() == 2, "贴身护甲层回调总数应为 2（before* + on*）");
        assertTrue(ExampleDeliveryArmorItem.getAfterHurtCalls() == 1,
                "挡下同样是命中，armorAfterHurt 应触发一次（耐久消耗）");
        assertTrue(carrier.getResolvePenetrationCalls() == 0, "投递不跑承载者本体层");
        helper.succeed();
    }

    // ======================== 场景14：投递不跑承载者本体层 ========================

    /**
     * 承载者同时是 {@code BFHurtTarget} 时的投递。
     * <p>
     * 对照两条路径：同一个承载者形态作为协议<b>目标</b>时本体层跑完整管线（计数 1/1）；
     * 作为<b>承载者</b>被投递时本体层一次都不跑，只跑它穿戴的护甲层（计数 0/0，护甲层各 1）。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testDeliverSkipsCarrierBodyLayer(GameTestHelper helper) {
        Player attacker = helper.makeMockPlayer(GameType.SURVIVAL);

        // 对照：作为协议目标 → 本体层跑
        ExampleCarrierEntity control = spawnCarrier(helper, new BlockPos(2, 1, 4));
        BFDamageApi.hurt(control, deliveryContext(helper, attacker, control, 15f, 60f, null));
        assertTrue(control.getResolvePenetrationCalls() == 1,
                "作为协议目标时本体层 resolvePenetration 应调用一次");
        assertTrue(control.getCalculateFinalDamageCalls() == 1,
                "作为协议目标时本体层 calculateFinalDamage 应调用一次");

        // 投递：本体层 0 次、护甲层三件套各 1 次
        ExampleDeliveryArmorItem.resetCounters();
        ExampleCarrierEntity carrier = spawnCarrier(helper, new BlockPos(2, 1, 2));
        equipDeliveryArmor(carrier);
        boolean landed = BFDamageApi.deliverTo(carrier,
                deliveryContext(helper, attacker, carrier, 15f, 18f, new DeliveryRecorder()));

        assertTrue(landed, "投递应落地");
        assertTrue(carrier.getResolvePenetrationCalls() == 0,
                "投递不跑承载者本体层 resolvePenetration");
        assertTrue(carrier.getCalculateFinalDamageCalls() == 0,
                "投递不跑承载者本体层 calculateFinalDamage");
        assertTrue(ExampleDeliveryArmorItem.getModifyPenetrationCalls() == 1,
                "护甲层三件套：modifyPenetration 应调用一次");
        assertTrue(ExampleDeliveryArmorItem.getResolvePenetrationCalls() == 1,
                "护甲层三件套：resolvePenetration 应调用一次");
        assertTrue(ExampleDeliveryArmorItem.getCalculateFinalDamageCalls() == 1,
                "护甲层三件套：calculateFinalDamage 应调用一次");
        helper.succeed();
    }

    // ======================== 场景15：ignoreBFArmor = true ========================

    /**
     * {@code ignoreBFArmor = true}：在"投递已绕开判定"的基础上再跳过贴身护甲层。
     * <p>
     * 穿深 5mm 本会被这件护甲挡下；跳过护甲层后伤害应原样（{@code ctx.baseDamage()}）落地。
     * <p>
     * 断言：投递返回 true、承载者生命值下降、护甲物品的三件套与 {@code afterHurt} 均未被调用、
     * handler 未收到任何回调——整条投递路径不产生协议信号。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testDeliverIgnoresBFArmor(GameTestHelper helper) {
        ExampleDeliveryArmorItem.resetCounters();
        ExampleCarrierEntity carrier = spawnCarrier(helper, new BlockPos(2, 1, 2));
        equipDeliveryArmor(carrier);
        Player attacker = helper.makeMockPlayer(GameType.SURVIVAL);
        DeliveryRecorder recorder = new DeliveryRecorder();

        float hpBefore = carrier.getHealth();
        boolean landed = BFDamageApi.deliverTo(carrier,
                deliveryContext(helper, attacker, carrier, 15f, 5f, recorder), true);

        assertTrue(landed, "跳过贴身护甲层后应直接落地");
        assertFloatEquals(15f, carrier.getLastHurtAmount(), "应原样投递 ctx.baseDamage()");
        assertTrue(carrier.getHealth() < hpBefore, "承载者生命值应下降");
        assertTrue(carrier.getHurtCalls() == 1, "落地应只调用一次 carrier.hurt");
        assertTrue(ExampleDeliveryArmorItem.getModifyPenetrationCalls() == 0,
                "跳过护甲层：不得调用 modifyPenetration");
        assertTrue(ExampleDeliveryArmorItem.getResolvePenetrationCalls() == 0,
                "跳过护甲层：不得调用 resolvePenetration");
        assertTrue(ExampleDeliveryArmorItem.getCalculateFinalDamageCalls() == 0,
                "跳过护甲层：不得调用 calculateFinalDamage");
        assertTrue(ExampleDeliveryArmorItem.getAfterHurtCalls() == 0,
                "跳过护甲层：不得触发 afterHurt");
        assertTrue(recorder.total() == 0, "跳过护甲层：handler 不应收到任何回调");
        helper.succeed();
    }

    // ======================== 场景16：入口自检拒绝重复投递 ========================

    /**
     * 承载者在自己的 {@code hurt} 内再投递一次自己。
     * <p>
     * 此时承载者已是上下文栈顶，投递入口自检命中 → 返回 false 并记录警告，不产生递归。
     * （警告文本本身由 {@code BFDamageApi} 写入日志，测试只能断言被拒绝这件事。）
     * <p>
     * 断言：外层投递落地、探针触发、被拒绝、生命值只下降一次、不发生栈溢出、不重复进入 hurt。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testDeliverRejectsRedelivery(GameTestHelper helper) {
        ExampleCarrierEntity carrier = spawnCarrier(helper, new BlockPos(2, 1, 2));
        Player attacker = helper.makeMockPlayer(GameType.SURVIVAL);
        BFDamageContext ctx = deliveryContext(helper, attacker, carrier, 10f, 60f, null);

        carrier.armRedeliverProbe(ctx);

        float hpBefore = carrier.getHealth();
        boolean landed = BFDamageApi.deliverTo(carrier, ctx);

        assertTrue(landed, "外层投递应落地");
        assertTrue(carrier.wasRedeliverAttempted(), "承载者 hurt 内的探针应被触发");
        assertTrue(carrier.wasRedeliverRejected(), "承载者已在栈顶时投递应被拒绝");
        assertTrue(carrier.getHurtCalls() == 1, "被拒绝的投递不得再次进入 carrier.hurt");
        assertFloatEquals(hpBefore - 10f, carrier.getHealth(), "生命值只应下降一次");
        helper.succeed();
    }

    // ======================== 场景17：零伤害量早退 ========================

    /**
     * {@code ctx.baseDamage() == 0}：投递没有内容可交付。
     * <p>
     * 断言：返回 false、不调用 {@code carrier.hurt}、生命值不变、不触发任何回调、
     * 不跑护甲层——这是"回调是否触发不取决于伤害量"规则的例外。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testDeliverZeroDamage(GameTestHelper helper) {
        ExampleDeliveryArmorItem.resetCounters();
        ExampleCarrierEntity carrier = spawnCarrier(helper, new BlockPos(2, 1, 2));
        equipDeliveryArmor(carrier);
        Player attacker = helper.makeMockPlayer(GameType.SURVIVAL);
        DeliveryRecorder recorder = new DeliveryRecorder();

        float hpBefore = carrier.getHealth();
        boolean landed = BFDamageApi.deliverTo(carrier,
                deliveryContext(helper, attacker, carrier, 0f, 18f, recorder));

        assertTrue(!landed, "零伤害量应返回 false");
        assertFloatEquals(hpBefore, carrier.getHealth(), "零伤害量不应改变生命值");
        assertTrue(carrier.getHurtCalls() == 0, "零伤害量不得调用 carrier.hurt");
        assertTrue(recorder.total() == 0, "零伤害量不应触发任何回调");
        assertTrue(ExampleDeliveryArmorItem.getResolvePenetrationCalls() == 0,
                "零伤害量不应跑护甲层");
        helper.succeed();
    }

    // ======================== 场景18：原版免疫 ========================

    /**
     * 承载者免疫（{@code setInvulnerable(true)}）：投递返回 false 是原版拒绝，不是护甲挡下。
     * <p>
     * 断言：返回 false、生命值不变；同时断言护甲层判定为<b>击穿</b>且回调已发——用生命值
     * 与护甲层结果一起把这次 false 与"护甲挡下"区分开。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testDeliverCarrierInvulnerable(GameTestHelper helper) {
        ExampleDeliveryArmorItem.resetCounters();
        ExampleCarrierEntity carrier = spawnCarrier(helper, new BlockPos(2, 1, 2));
        equipDeliveryArmor(carrier);
        carrier.setInvulnerable(true);
        Player attacker = helper.makeMockPlayer(GameType.SURVIVAL);
        DeliveryRecorder recorder = new DeliveryRecorder();

        float hpBefore = carrier.getHealth();
        boolean landed = BFDamageApi.deliverTo(carrier,
                deliveryContext(helper, attacker, carrier, 15f, 18f, recorder));

        assertTrue(!landed, "免疫状态下原版拒绝，投递应返回 false");
        assertFloatEquals(hpBefore, carrier.getHealth(), "免疫状态下生命值不应变化");
        assertTrue(carrier.getHurtCalls() == 1, "原版拒绝发生在 hurt 内部，因此 hurt 已被调用");
        assertTrue(ExampleDeliveryArmorItem.getResolvePenetrationCalls() == 1,
                "护甲层已经跑过——这次 false 不是被护甲挡下");
        assertTrue(recorder.onPenetrated == 1, "护甲层判定应为击穿");
        helper.succeed();
    }

    // ======================== 场景19：原版无敌帧窗口 ========================

    /**
     * 承载者处于原版 20 tick 无敌帧窗口内且本次伤害不大于窗口内已承受的 {@code lastHurt}：
     * 投递返回 false 同样是原版拒绝。
     * <p>
     * 断言：返回 false、生命值不变；护甲层已跑且判定为击穿（区别于护甲挡下）。
     */
    @GameTest(timeoutTicks = 200, template = "empty_arena")
    public static void testDeliverCarrierHurtCooldown(GameTestHelper helper) {
        ExampleDeliveryArmorItem.resetCounters();
        ExampleCarrierEntity carrier = spawnCarrier(helper, new BlockPos(2, 1, 2));
        equipDeliveryArmor(carrier);
        // invulnerableTime > 10 且 lastHurt(100) ≥ 本次伤害(9.75)：原版直接返回 false
        carrier.primeHurtCooldown(20, 100f);
        Player attacker = helper.makeMockPlayer(GameType.SURVIVAL);
        DeliveryRecorder recorder = new DeliveryRecorder();

        float hpBefore = carrier.getHealth();
        boolean landed = BFDamageApi.deliverTo(carrier,
                deliveryContext(helper, attacker, carrier, 15f, 18f, recorder));

        assertTrue(!landed, "无敌帧窗口内不大于 lastHurt 的命中被原版拒绝");
        assertFloatEquals(hpBefore, carrier.getHealth(), "无敌帧窗口内生命值不应变化");
        assertTrue(carrier.getHurtCalls() == 1, "原版拒绝发生在 hurt 内部，因此 hurt 已被调用");
        assertTrue(ExampleDeliveryArmorItem.getCalculateFinalDamageCalls() == 1,
                "护甲层已经跑过——这次 false 不是被护甲挡下");
        assertTrue(recorder.onPenetrated == 1, "护甲层判定应为击穿");
        helper.succeed();
    }

    // ======================== 辅助方法 ========================

    /**
     * 在指定位置生成一个示例靶子实体。
     */
    private static ExampleTargetEntity spawnTarget(GameTestHelper helper, BlockPos pos) {
        return helper.spawn(ExampleContent.EXAMPLE_TARGET_ENTITY.get(), pos);
    }

    /**
     * 为目标生物穿戴完整的示例护甲四件套。
     */
    private static void equipExampleArmor(Mob target) {
        target.setItemSlot(EquipmentSlot.HEAD,
                new ItemStack(ExampleContent.EXAMPLE_HELMET.get()));
        target.setItemSlot(EquipmentSlot.CHEST,
                new ItemStack(ExampleContent.EXAMPLE_CHESTPLATE.get()));
        target.setItemSlot(EquipmentSlot.LEGS,
                new ItemStack(ExampleContent.EXAMPLE_LEGGINGS.get()));
        target.setItemSlot(EquipmentSlot.FEET,
                new ItemStack(ExampleContent.EXAMPLE_BOOTS.get()));
    }

    /**
     * 生成一个示例代理实体（宿主：实现 {@code BFHitResolver}，不实现 {@code BFHurtTarget}）。
     */
    private static ExampleProxyEntity spawnProxy(GameTestHelper helper, BlockPos pos) {
        return helper.spawn(ExampleContent.EXAMPLE_PROXY_ENTITY.get(), pos);
    }

    /**
     * 生成一个示例承载者实体（实现 {@code BFHurtTarget}，可作投递承载者）。
     */
    private static ExampleCarrierEntity spawnCarrier(GameTestHelper helper, BlockPos pos) {
        return helper.spawn(ExampleContent.EXAMPLE_CARRIER_ENTITY.get(), pos);
    }

    /**
     * 为承载者穿戴投递期示例护甲（{@code ExampleDeliveryArmorItem}，RHA 15mm）。
     */
    private static void equipDeliveryArmor(Mob carrier) {
        carrier.setItemSlot(EquipmentSlot.CHEST,
                new ItemStack(ExampleContent.EXAMPLE_DELIVERY_CHESTPLATE.get()));
    }

    /**
     * 承载者身体坐标系下的胸部命中点。
     * <p>
     * {@code BFArmorMaterial#mapHitToSlot} 的默认实现按
     * {@code (hitPoint - wearer.position()) / wearer.getBbHeight()} 划分槽位，
     * 0.7 的高度占比落在 CHEST 带（0.55~0.85），使贴身护甲稳定映射到胸甲槽位。
     */
    private static Vec3 chestHit(Entity carrier) {
        return carrier.position().add(0.0, carrier.getBbHeight() * 0.7, 0.0);
    }

    /**
     * 构造一个投递上下文：命中点取承载者身体坐标系下的胸部位置。
     * <p>
     * 伤害来源用 {@code mobAttack}——{@code damageSources().generic()} 属于
     * {@code bypasses_armor} 标签，会绕过原版护甲，使"协议层与本体层两处减免"被观测成一处。
     *
     * @param baseDamage  投递的伤害量（穿透后的修正值）
     * @param penetration 到达承载者的残余穿深（mm RHA）
     * @param handler     伤害发起方回调，可为 null
     */
    private static BFDamageContext deliveryContext(GameTestHelper helper, Player attacker,
                                                   Entity carrier, float baseDamage,
                                                   float penetration,
                                                   @Nullable BFDamageHandler handler) {
        return BFDamageContext.builder()
                .source(helper.getLevel().damageSources().mobAttack(attacker))
                .baseDamage(baseDamage)
                .hitVelocity(new Vec3(0, 0, 2))
                .hitPoint(chestHit(carrier))
                .hitNormal(new Vec3(0, 0, 1))
                .penetration(penetration)
                .handler(handler)
                .build();
    }

    private static void assertFloatEquals(float expected, float actual, String message) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new GameTestAssertException(
                    message + "：预期 " + expected + "，实际 " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    // ======================== 内部类：回调记录器 ========================

    /**
     * 用于在 GameTest 中验证回调触发情况的轻量级 handler。
     * <p>
     * 各 boolean 字段在对应回调被调用时置为 true，测试方法在
     * BFDamageApi.hurt() 返回后通过断言检查。
     */
    private static class CallbackRecorder implements BFDamageHandler {
        boolean penetrated;
        boolean blocked;
        boolean ricochet;
        boolean overmatch;
        boolean spall;
        boolean entityHit;

        @Override
        public void onPenetrated(BFHurtTarget target, BFDamageContext ctx) {
            penetrated = true;
        }

        @Override
        public void onBlocked(BFHurtTarget target, BFDamageContext ctx) {
            blocked = true;
        }

        @Override
        public void onRicochet(BFHurtTarget target, BFDamageContext ctx) {
            ricochet = true;
        }

        @Override
        public void onOvermatch(BFHurtTarget target, BFDamageContext ctx) {
            overmatch = true;
        }

        @Override
        public void onSpall(BFHurtTarget target, BFDamageContext ctx) {
            spall = true;
        }

        @Override
        public void onNormalEntityHit(Entity entity, BFDamageContext ctx,
                                      float baseDamage, boolean success) {
            entityHit = true;
        }
    }

    // ======================== 内部类：投递期回调计数器 ========================

    /**
     * 用于在 GameTest 中逐项统计投递期回调次数的 handler。
     * <p>
     * 与 {@link CallbackRecorder} 的差别有两点：
     * <ul>
     *   <li>统计次数而不是布尔值——投递期需要断言"各触发一次"；</li>
     *   <li>{@link #isOvermatch} / {@link #isSpall} 恒为 false。默认实现会通过
     *       {@code target} 查询 {@code getRHA} / {@code modifyPenetration}，那会在护甲物品上
     *       产生三件套以外的调用，使"三件套各一次"的计数无法断言。这两个判定只影响
     *       超匹配(碾压)与破片的附加回调，不影响主结果回调。</li>
     * </ul>
     */
    private static class DeliveryRecorder implements BFDamageHandler {
        int beforePenetrated;
        int beforeBlocked;
        int beforeRicochet;
        int beforeOvermatch;
        int beforeSpall;
        int onPenetrated;
        int onBlocked;
        int onRicochet;
        int onOvermatch;
        int onSpall;
        int normalEntityHit;

        @Override
        public void beforePenetrated(BFHurtTarget target, BFDamageContext ctx) {
            beforePenetrated++;
        }

        @Override
        public void beforeBlocked(BFHurtTarget target, BFDamageContext ctx) {
            beforeBlocked++;
        }

        @Override
        public void beforeRicochet(BFHurtTarget target, BFDamageContext ctx) {
            beforeRicochet++;
        }

        @Override
        public void beforeOvermatch(BFHurtTarget target, BFDamageContext ctx) {
            beforeOvermatch++;
        }

        @Override
        public void beforeSpall(BFHurtTarget target, BFDamageContext ctx) {
            beforeSpall++;
        }

        @Override
        public void onPenetrated(BFHurtTarget target, BFDamageContext ctx) {
            onPenetrated++;
        }

        @Override
        public void onBlocked(BFHurtTarget target, BFDamageContext ctx) {
            onBlocked++;
        }

        @Override
        public void onRicochet(BFHurtTarget target, BFDamageContext ctx) {
            onRicochet++;
        }

        @Override
        public void onOvermatch(BFHurtTarget target, BFDamageContext ctx) {
            onOvermatch++;
        }

        @Override
        public void onSpall(BFHurtTarget target, BFDamageContext ctx) {
            onSpall++;
        }

        @Override
        public void beforeNormalEntityHit(Entity entity, BFDamageContext ctx, float baseDamage) {
            normalEntityHit++;
        }

        @Override
        public void onNormalEntityHit(Entity entity, BFDamageContext ctx,
                                      float baseDamage, boolean success) {
            normalEntityHit++;
        }

        @Override
        public boolean isOvermatch(BFHurtTarget target, BFDamageContext ctx,
                                   PenetrationResult result) {
            return false;
        }

        @Override
        public boolean isSpall(BFHurtTarget target, BFDamageContext ctx,
                               PenetrationResult result) {
            return false;
        }

        /** @return 全部回调的触发总次数 */
        int total() {
            return beforePenetrated + beforeBlocked + beforeRicochet + beforeOvermatch
                    + beforeSpall + onPenetrated + onBlocked + onRicochet + onOvermatch
                    + onSpall + normalEntityHit;
        }
    }

    // ======================== 内部类：示例零件 ========================

    /**
     * 示例零件：一个不依附于实体的 {@code BFHurtTarget}。
     * <p>
     * "宿主 + 零件"拓扑里的零件就是这样的对象——它是宿主身上的一份数据（第 3 方模组的
     * {@code SubPart} 即如此），不是实体，因此 {@code getBFEntity()} 的默认实现返回 null，
     * 协议层在它身上得到的是"独立对象直接声明协议感知"的分支。
     * <p>
     * 记录承受的伤害量与 {@code hurt} 调用次数，供测试断言"零件确实承担了第一趟"。
     */
    private static class TestPart implements BFHurtTarget {

        /** 零件自身的防护等级；默认裸体。 */
        private final ArmorLevel armorLevel;

        private float damageTaken;
        private int hurtCalls;

        TestPart() {
            this(ArmorLevel.UNARMORED_1);
        }

        TestPart(ArmorLevel armorLevel) {
            this.armorLevel = armorLevel;
        }

        @Override
        public ArmorLevel getArmorLevel(BFDamageContext ctx) {
            return armorLevel;
        }

        @Override
        public boolean hurt(DamageSource source, float amount) {
            hurtCalls++;
            damageTaken += amount;
            // 零件没有原版实体可落地，伤害在自身内部结算完毕即视为成功
            return true;
        }

        /**
         * 按"原版伤害量的一半作为穿深"折算（与 {@code BFArmorMaterial} 的默认实现同一估算），
         * 使这个零件也能承接经拦截器转发的原版伤害（情况 3 要求实际目标能给出上下文）。
         */
        @Nullable
        @Override
        public BFDamageContext createContextFromVanilla(DamageSource source, float amount) {
            return BFDamageContext.builder()
                    .source(source)
                    .baseDamage(amount)
                    .penetration(amount / 2f)
                    .build();
        }

        /** @return 累计承受的伤害量 */
        float getDamageTaken() {
            return damageTaken;
        }

        /** @return {@link #hurt} 被调用的次数 */
        int getHurtCalls() {
            return hurtCalls;
        }
    }
}

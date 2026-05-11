package io.github.sweetzonzi.ballistics_framework.example.gametest;

import io.github.sweetzonzi.ballistics_framework.api.BFDamageApi;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageContext;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageHandler;
import io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget;
import io.github.sweetzonzi.ballistics_framework.example.ExampleContent;
import io.github.sweetzonzi.ballistics_framework.example.entity.ExampleTargetEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * BallisticsFramework 穿甲管线的自动化 GameTest。
 * <p>
 * 覆盖 8 个管线验证场景：裸打靶子、同级击穿、低级阻挡、越级击穿、
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
    }
}

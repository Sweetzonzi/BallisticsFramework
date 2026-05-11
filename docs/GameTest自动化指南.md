# BallisticsFramework GameTest 自动化测试指南

> 版本：2026-05-12
> 适用项目：BallisticsFramework（弹道框架 — 内弹道、外弹道、终点弹道伤害协议层）
> 构建系统：NeoForge / Gradle

---

## 目录

1. [GameTest 框架原理](#1-gametest-框架原理)
2. [如何编写一个 GameTest](#2-如何编写一个-gametest)
3. [如何运行](#3-如何运行)
4. [测试覆盖内容](#4-测试覆盖内容)
5. [如何解读测试输出](#5-如何解读测试输出)
6. [Agent 使用方法](#6-agent-使用方法)
7. [为其他项目编写 GameTest 的指导建议](#7-为其他项目编写-gametest-的指导建议)

---

## 1. GameTest 框架原理

### 1.1 概述

Minecraft GameTest 框架是 Mojang 为数据包和行为包开发者提供的自动化测试基础设施。NeoForge 将其适配到模组开发环境中，使模组开发者无需启动完整的 Minecraft 客户端即可运行自动化测试。

### 1.2 核心三要素

GameTest 由三个组件协作完成：

| 组件 | 作用 | BallisticsFramework 中的对应项 |
|------|------|-------------------------------|
| `@GameTestHolder` 类注解 | 声明测试类所属的命名空间（mod_id），框架据此定位结构文件 | `@GameTestHolder("ballistics_framework")` |
| `@GameTest` 方法注解 | 标记一个静态方法为测试用例，指定结构模板、超时等参数 | 6 个带 `@GameTest` 注解的方法 |
| `.nbt` 结构文件 | 定义测试场地（方块布局），在测试启动前自动加载 | `empty_arena.nbt`（5×3×5 石砖地板空场地） |

### 1.3 GameTestServer

GameTest 在专用的 **GameTestServer** 上运行，该服务器具有以下特征：

- **轻量级无头服务器**：不需要渲染 GUI，不需要玩家手动操作，没有图形界面
- **自动化的世界生命周期**：每个测试方法执行前，框架自动创建/重置一个独立的测试世界，加载指定的 `.nbt` 结构文件作为测试场地
- **批量执行**：所有带 `@GameTest` 注解的方法会被自动发现并依次执行
- **结果以 JUnit 风格输出**：通过/失败统计、失败时的异常堆栈都以标准测试框架风格呈现，CI 系统可以直接解析

### 1.4 GameTestHelper

`GameTestHelper` 是每个测试方法的入口参数，提供操作测试世界的 API：

- **实体操作**：`spawn()` 生成实体、`makeMockPlayer()` 创建模拟玩家、`spawnWithNoFreeWill()` 生成无 AI 的生物、`killAllEntities()` 清理实体
- **方块操作**：`setBlock()` 放置方块、`destroyBlock()` 破坏方块、`assertBlockPresent()` 断言方块存在
- **红石操作**：`pressButton()` 按下按钮、`pullLever()` 拉下拉杆、`pulseRedstone()` 发出红石脉冲
- **世界访问**：`getLevel()` 获取 ServerLevel 实例
- **断言控制**：`succeed()` 标记测试通过；若测试方法正常结束但未调用 `succeed()`，测试视为失败

### 1.5 测试生命周期

```
启动 GameTestServer → 扫描所有 @GameTestHolder 类
    → 对每个 @GameTest 方法：
        1. 创建/重置测试世界
        2. 加载 template 指定的 .nbt 结构文件
        3. 调用测试方法，传入 GameTestHelper
        4. 等待 succeed() 或 timeoutTicks 超时
        5. 记录通过/失败结果
    → 汇总输出 JUnit 风格报告 → 退出
```

---

## 2. 如何编写一个 GameTest

### 2.1 类结构

```java
@GameTestHolder("ballistics_framework")  // 值为 mod_id
public class BallisticsGameTest {

    @GameTest(
        timeoutTicks = 200,                            // 超时 tick 数
        template = "ballistics_framework:empty_arena"  // 结构文件 ResourceLocation
    )
    public static void testMethodName(GameTestHelper helper) {
        // 测试逻辑...
        helper.succeed();  // 必须调用，标记测试通过
    }
}
```

### 2.2 方法签名规范

- **必须是 `public static void`**
- **必须有且仅有一个参数 `GameTestHelper`**
- **方法名无限制**，但建议以 `test` 开头便于识别

### 2.3 `@GameTest` 注解参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `template` | `String` | `""` | 结构文件的 ResourceLocation，格式为 `"mod_id:structure_name"`。结构文件必须放在 `src/main/resources/data/<mod_id>/structures/<name>.nbt` |
| `timeoutTicks` | `int` | `200` | 测试的最大执行时间（单位：游戏刻，20 tick = 1 秒）。超时未调用 `succeed()` 则测试失败 |
| `required` | `boolean` | `true` | 是否必须通过。设为 `false` 允许该测试失败但不影响整体结果 |
| `batch` | `String` | `"defaultBatch"` | 批次名称，用于分组执行 |
| `attempts` | `int` | `1` | 重试次数 |
| `setupTicks` | `int` | `0` | 结构加载后、测试开始前的等待 tick 数 |

### 2.4 结构文件

结构文件是 `.nbt` 格式的 Minecraft 结构文件。BallisticsFramework 使用一个简单的空场地：

- **文件路径**：`src/main/resources/data/ballistics_framework/structures/empty_arena.nbt`
- **内容**：5×3×5 石砖地板空场地
- **生成方式**：可以通过游戏内结构方块导出，也可以用纯代码策略生成（详见第 7 节）

### 2.5 断言方式

GameTest 框架使用**抛异常**的方式表示断言失败：

```java
// 方式1：使用 GameTestAssertException 直接抛出
if (Math.abs(expected - actual) > 0.01f) {
    throw new GameTestAssertException("预期 " + expected + "，实际 " + actual);
}

// 方式2：封装为辅助方法（推荐）
private static void assertFloatEquals(float expected, float actual, String message) {
    if (Math.abs(expected - actual) > 0.01f) {
        throw new GameTestAssertException(message + "：预期 " + expected + "，实际 " + actual);
    }
}

private static void assertTrue(boolean condition, String message) {
    if (!condition) {
        throw new GameTestAssertException(message);
    }
}
```

**注意**：GameTest 框架**不支持**标准的 JUnit `Assertions.assertEquals()`——不要引入 JUnit 依赖。只使用 `GameTestAssertException`。

### 2.6 标记测试通过

必须在测试方法末尾调用 `helper.succeed()`。如果方法正常结束但未调用 `succeed()`，框架会将本次测试标记为失败。

```java
@GameTest(timeoutTicks = 200, template = "ballistics_framework:empty_arena")
public static void testExample(GameTestHelper helper) {
    // 执行测试逻辑...
    helper.succeed();  // ← 必须调用
}
```

### 2.7 GameTestHelper 常用 API 速查表

| 方法签名 | 用途 | BallisticsFramework 中的使用示例 |
|---------|------|-------------------------------|
| `spawn(EntityType<T>, BlockPos)` | 在指定位置生成实体 | `helper.spawn(ExampleContent.EXAMPLE_TARGET_ENTITY.get(), pos)` |
| `spawnWithNoFreeWill(EntityType<T>, BlockPos)` | 生成无 AI 的生物（不会移动） | `helper.spawnWithNoFreeWill(EntityType.ZOMBIE, pos)` |
| `makeMockPlayer(GameType)` | 创建模拟玩家 | `helper.makeMockPlayer(GameType.SURVIVAL)` |
| `getLevel()` | 获取 ServerLevel 实例 | `helper.getLevel().damageSources().mobAttack(player)` |
| `setBlock(BlockPos, Block)` | 放置方块 | 场景搭建时使用 |
| `destroyBlock(BlockPos)` | 破坏方块 | 清理或验证时使用 |
| `killAllEntities()` | 杀死世界中所有实体 | 测试前后清理 |
| `pressButton(BlockPos)` | 按下按钮 | 红石逻辑测试 |
| `pullLever(BlockPos)` | 拉下拉杆 | 红石逻辑测试 |
| `pulseRedstone(BlockPos)` | 发出红石脉冲 | 简短红石信号 |
| `assertBlockPresent(Block, BlockPos)` | 断言指定位置有指定方块 | 方块状态验证 |
| `assertEntityPresent(EntityType<?>)` | 断言世界中存在指定类型的实体 | 实体生成验证 |
| `succeed()` | 标记测试通过 | 每个测试方法末尾调用 |

### 2.8 辅助工具类推荐做法

将浮点数断言、布尔断言等封装为 `private static` 辅助方法，保持测试代码简洁：

```java
private static final float EPSILON = 0.01f;

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
```

### 2.9 实体生成与装备穿戴

生成测试目标实体后可以直接操作其装备槽位——GameTestServer 是完整的服务端环境：

```java
// 生成靶子实体
ExampleTargetEntity target = helper.spawn(
        ExampleContent.EXAMPLE_TARGET_ENTITY.get(),
        new BlockPos(2, 1, 2));

// 穿戴护甲
target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ExampleContent.EXAMPLE_HELMET.get()));
target.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ExampleContent.EXAMPLE_CHESTPLATE.get()));
target.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ExampleContent.EXAMPLE_LEGGINGS.get()));
target.setItemSlot(EquipmentSlot.FEET, new ItemStack(ExampleContent.EXAMPLE_BOOTS.get()));
```

### 2.10 回调验证：CallbackRecorder 模式

当需要验证 `BFDamageHandler` 回调是否被正确触发时，使用内部类实现的"回调记录器"模式：

```java
private static class CallbackRecorder implements BFDamageHandler {
    boolean penetrated;
    boolean blocked;
    boolean ricochet;
    boolean overmatch;
    boolean spall;

    @Override public void onPenetrated(BFHurtTarget target, BFDamageContext ctx) { penetrated = true; }
    @Override public void onBlocked(BFHurtTarget target, BFDamageContext ctx) { blocked = true; }
    @Override public void onRicochet(BFHurtTarget target, BFDamageContext ctx) { ricochet = true; }
    @Override public void onOvermatch(BFHurtTarget target, BFDamageContext ctx) { overmatch = true; }
    @Override public void onSpall(BFHurtTarget target, BFDamageContext ctx) { spall = true; }
}
```

测试代码中，将 `CallbackRecorder` 注入 `BFDamageContext`，执行管线后检查各 boolean 字段即可验证回调触发情况。这种模式比检查日志字符串更可靠——它是强类型的程序化验证，不依赖字符串匹配。

---

## 3. 如何运行

### 3.1 命令行运行

在项目根目录（`build.gradle` 所在目录）执行：

```bash
gradlew runGameTestServer
```

### 3.2 IDE 中运行

**IntelliJ IDEA**：

1. 打开右侧 Gradle 面板（View → Tool Windows → Gradle）
2. 展开：`Tasks` → `neoforge` → `runGameTestServer`
3. 双击 `runGameTestServer`

**说明**：`runGameTestServer` 是 NeoForge 提供的 Gradle 任务，会启动一个专用的 GameTestServer 并自动执行所有带 `@GameTest` 注解的测试方法。

### 3.3 结果查看

测试完成后，控制台会输出 JUnit 风格的通过/失败统计：

- **全部通过时**：显示类似 `All N tests passed` 的消息
- **有失败时**：显示失败的测试方法名、异常信息、堆栈跟踪

---

## 4. 测试覆盖内容

### 4.1 测试场景总览

BallisticsFramework 实现了 8 个 GameTest 场景，覆盖 `example-包实现计划.md` 中定义的管线验证矩阵，
并补充了同级击穿、低级阻挡、越级击穿三种穿甲判定关系的独立测试。

| 测试方法 | 场景 | 攻击参数 | 目标 | 预期伤害 | 对应管线分支 |
|---------|------|---------|------|---------|------------|
| `testMeleeAgainstUnarmoredTarget` | 近战武器裸打靶子 | 60mm / 15HP | 裸体靶子 (0mm) | 15.0 | **分支1**：BFHurtTarget 直接管线 |
| `testMeleeSameLevelPenetration` | 同级击穿 | 30mm / 15HP | 穿护甲靶子 (40mm) | 9.75 | **分支0**：同级 ×0.65 |
| `testMeleeLowerLevelBlocked` | 低级打高级 | 15mm / 15HP | 穿护甲靶子 (40mm) | 0 | **分支0**：完全阻挡 |
| `testMeleeOvermatchPenetration` | 越级击穿 | 60mm / 15HP | 穿护甲靶子 (40mm) | 15.0 | **分支0**：越级 ×1.0 |
| `testProjectileAgainstArmoredTarget` | 投射物打护甲靶子 | 200mm / 25HP | 穿护甲靶子 (40mm) | 25.0 | **分支0** + 回调验证（PENETRATED + OVERMATCH） |
| `testProjectileAgainstUnarmoredTarget` | 投射物打裸体靶子 | 200mm / 25HP | 裸体靶子 (0mm) | 25.0 | **分支1** + 回调验证 |
| `testHurtAgainstVanillaEntity` | 投射物打普通实体 | 200mm / 5HP | 僵尸（非协议） | > 0 | **分支3**：原版回退 |
| `testVanillaDamageFallback` | 原版伤害走原版 | 原版 generic / 5HP | 靶子实体 | 扣血 | 不触发管线 |

### 4.2 管线分支说明

BallisticsFramework 的 `BFDamageApi.hurt()` 是管线唯一入口，内部按三路分支调度：

```
BFDamageApi.hurt(Object target, BFDamageContext ctx)
    │
    ├─ 分支1：target 是 BFHurtTarget → 直接走完整穿甲判定管线
    │
    ├─ 分支0：target 是 BFHurtTarget + LivingEntity + BFArmorMaterial 护甲
    │   → 先走护甲管线 → 再走本体管线（复合目标双层防护）
    │
    ├─ 分支2：target 是 LivingEntity + 穿戴 BFArmorMaterial 护甲
    │   → 通过 BFArmorAdapter 接管并走完整管线
    │
    └─ 分支3：target 是普通 Entity（非协议感知）
        → 调用 entity.hurt(source, amount) 走原版伤害
```

### 4.3 各场景详细说明

#### 场景1：`testMeleeAgainstUnarmoredTarget` — 近战武器裸打靶子

- **攻击方**：模拟近战武器，穿深 60mm（`ArmorLevel.HEAVY`），基础伤害 15 HP
- **防御方**：裸体 `ExampleTargetEntity`（实现 `BFHurtTarget`，`getArmorLevel` 返回 `UNARMORED_1`，即 0mm 护甲）
- **管线路径**：分支1 — 命中 `BFHurtTarget`，直接进入穿甲判定管线
- **预期伤害**：15.0（穿深远超护甲，全伤穿透系数 1.0）
- **断言语义**：验证 `BFDamageApi.hurt()` 对 BFHurtTarget 的裸打路径

#### 场景2：`testMeleeSameLevelPenetration` — 同级击穿

- **攻击方**：模拟近战武器，穿深 30mm（`ArmorLevel.HEAVY`），基础伤害 15 HP
- **防御方**：`ExampleTargetEntity` + 穿戴示例护甲四件套（每件 40mm，`ArmorLevel.HEAVY`）
- **等级判定**：穿透等级 = 护甲等级（均为 HEAVY）→ 同级击穿
- **管线路径**：分支0 — 复合目标，`canDefeat` 返回 true（ordinal 相等）
- **预期伤害**：9.75（同级击穿伤害系数 0.65：15 × 0.65 = 9.75）
- **断言语义**：验证 `calculateFinalDamage` 中对 `getPenetrationLevel() == getArmorLevel()` 的同级判定

#### 场景3：`testMeleeLowerLevelBlocked` — 低级打高级

- **攻击方**：模拟近战武器，穿深 15mm（`ArmorLevel.MEDIUM`），基础伤害 15 HP
- **防御方**：`ExampleTargetEntity` + 穿戴示例护甲四件套（40mm，`ArmorLevel.HEAVY`）
- **等级判定**：穿透等级（MEDIUM）低于护甲等级（HEAVY）→ 未击穿
- **管线路径**：分支0 — 复合目标，`canDefeat` 返回 false
- **预期伤害**：0（BLOCKED，`calculateFinalDamage` 返回 0）
- **断言语义**：验证低级穿深无法击穿高级护甲的完全阻挡行为

#### 场景4：`testMeleeOvermatchPenetration` — 越级击穿

- **攻击方**：模拟近战武器，穿深 60mm（`ArmorLevel.SUPER_HEAVY_1`），基础伤害 15 HP
- **防御方**：`ExampleTargetEntity` + 穿戴示例护甲四件套（40mm，`ArmorLevel.HEAVY`）
- **等级判定**：穿透等级（SUPER_HEAVY_1）高于护甲等级（HEAVY）→ 越级击穿
- **管线路径**：分支0 — 复合目标，`canDefeat` 返回 true 且 ordinal 不等
- **预期伤害**：15.0（越级击穿伤害系数 1.0：15 × 1.0 = 15.0）
- **断言语义**：验证越级击穿时全伤穿透，与场景2的同级×0.65形成对比

#### 场景5：`testProjectileAgainstArmoredTarget` — 投射物打护甲靶子

- **攻击方**：模拟投射物，穿深 200mm（`ArmorLevel.SUPER_HEAVY_3`），基础伤害 25 HP
- **防御方**：靶子 + 示例护甲四件套（40mm）
- **管线路径**：分支0 — 复合目标 + 投射物参数
- **预期伤害**：25.0（越级击穿 200mm >> 40mm，全伤系数 1.0）
- **回调验证**：`CallbackRecorder` 断言 PENETRATED 和 OVERMATCH 回调均被触发，BLOCKED 和 RICOCHET 未触发
- **OVERMATCH 判定规则**：穿深 200 > 40 × 1.5 = 60，满足超匹配条件

#### 场景6：`testProjectileAgainstUnarmoredTarget` — 投射物打裸体靶子

- **攻击方**：同上，穿深 200mm，伤害 25 HP
- **防御方**：裸体靶子（0mm）
- **管线路径**：分支1 — BFHurtTarget 直接管线
- **预期伤害**：25.0（越级击穿，全伤）
- **回调验证**：断言 PENETRATED + OVERMATCH 回调触发

#### 场景7：`testHurtAgainstVanillaEntity` — 投射物打普通实体

- **攻击方**：模拟投射物，穿深 200mm，伤害 5 HP
- **防御方**：原版僵尸（非 `BFHurtTarget` 实现者）
- **管线路径**：分支3 — 原版回退
- **预期结果**：僵尸生命值减少（`getHealth()` 在伤害后小于伤害前）
- **断言语义**：验证 BFDamageApi 对非协议实体不会崩溃，正确回退到原版 `Entity.hurt()`

#### 场景8：`testVanillaDamageFallback` — 原版伤害走原版流程

- **攻击方**：原版 `DamageSource.generic()`，伤害 5 HP
- **防御方**：`ExampleTargetEntity`（`createContextFromVanilla()` 返回 null）
- **管线路径**：**不触发协议管线** — Mixin 拦截后发现 `createContextFromVanilla()` 返回 null，放行原版流程
- **预期结果**：靶子生命值减少
- **断言语义**：验证协议外伤害兼容机制正确工作——非协议来源伤害在 `createContextFromVanilla()` 返回 null 时正确放行

---

## 5. 如何解读测试输出

### 5.1 成功输出

全部测试通过时，控制台输出典型如下：

```
[GameTest] Starting batch: defaultBatch
[GameTest] Running test: ballistics_framework:testMeleeAgainstUnarmoredTarget
[GameTest] ✓ ballistics_framework:testMeleeAgainstUnarmoredTarget
[GameTest] Running test: ballistics_framework:testMeleeSameLevelPenetration
[GameTest] ✓ ballistics_framework:testMeleeSameLevelPenetration
...
[GameTest] All 8 tests passed
```

退出码为 0，表示全部通过。

### 5.2 失败输出

测试失败时，输出包含以下信息：

```
[GameTest] ✗ ballistics_framework:testMeleeSameLevelPenetration
[GameTest]   Error: GameTestAssertException: 同级击穿应×0.65：预期 9.75，实际 0
    at ...BallisticsGameTest.assertFloatEquals(BallisticsGameTest.java:...)
    at ...BallisticsGameTest.testMeleeSameLevelPenetration(BallisticsGameTest.java:...)
    ...
[GameTest] Summary: 7 passed, 1 failed
```

退出码为非零值，CI 系统可据此判断失败。

### 5.3 用 grep 过滤日志

BallisticsFramework 的示例代码使用 `[BF-Example]` 前缀输出日志。在 GameTest 运行过程中，可以使用 grep 过滤出相关日志：

```bash
# 在 Windows PowerShell 中
gradlew runGameTestServer 2>&1 | Select-String "\[BF-Example\]"

# 在 Linux/macOS 中
gradlew runGameTestServer 2>&1 | grep "\[BF-Example\]"
```

GameTest 场景中，BFDamageApi.hurt() 的调用会触发完整管线，各节点的 Example 日志也会出现在输出中，可用于调试管线执行详情。

### 5.4 回调触发验证

通过 `CallbackRecorder` 内部类的 boolean 字段来验证回调触发：

- 在测试方法中创建 `CallbackRecorder`，注入 `BFDamageContext`
- 执行 `BFDamageApi.hurt()` 后，检查 `recorder.penetrated`、`recorder.overmatch` 等字段
- 若回调未触发（字段仍为 false），`assertTrue()` 抛出 `GameTestAssertException`，输出清晰的错误信息

这种模式比检查日志字符串更可靠——它是强类型的程序化验证，不依赖字符串匹配。

---

## 6. Agent 使用方法

本节专门指导 AI agent 如何利用 BallisticsFramework 的 GameTest 进行自动化验证。

### 6.1 唯一需要的命令

Agent 验证 BallisticsFramework 正确性，只需执行一条命令：

```bash
gradlew runGameTestServer
```

### 6.2 Agent 无需执行的操作

GameTest 的设计使 agent 无需以下任何操作：

- **无需启动 GUI**：GameTestServer 是无头服务器
- **无需鼠标键盘操作**：不需要控制玩家移动、点击、穿戴装备
- **无需进入世界**：不需要手动进入 Minecraft 世界
- **无需截图比对**：验证结果通过退出码和 JUnit 风格文本输出体现
- **无需等待游戏加载**：GameTestServer 启动比完整客户端快得多

### 6.3 Agent 判断成功/失败的逻辑

Agent 应依据以下规则判断测试是否通过：

1. **退出码**：0 = 全部通过，非 0 = 至少一个测试失败
2. **控制台关键词**：
   - 含 `All N tests passed` → 全部通过
   - 含 `failed` 或 `GameTestAssertException` → 有失败
3. **详细结果**：可解析控制台 JUnit 风格输出获取每个测试方法的状态

```
伪代码逻辑：
if (exitCode == 0 && output.contains("All")) {
    // 全部通过
} else {
    // 解析失败列表
    // 提取异常消息和堆栈信息用于诊断
}
```

### 6.4 Agent 工作流示例

当 agent 修改 BallisticsFramework 代码后，验证流程为：

```
1. 修改代码（如修改 BFDamageApi.hurt() 的管线路由逻辑）
2. 执行 gradlew runGameTestServer
3. 检查退出码和输出：
   - 通过 → 确认管线逻辑未被破坏
   - 失败 → 解析失败项，定位受影响的管线分支，修正代码后重新执行
```

### 6.5 注意事项

- **Gradle 守护进程**：连续多次执行 `runGameTestServer` 时，Gradle 守护进程会复用，无需担心启动开销
- **编译依赖**：修改代码后 Gradle 会自动重新编译受影响的文件，无需手动编译
- **测试隔离**：每个 GameTest 方法在独立的测试世界中运行，互不影响

---

## 7. 为其他项目编写 GameTest 的指导建议

### 7.1 针对 Machine-Max（载具系统）的建议

Machine-Max 是载具拼装与子系统联动的模组，以下是适合与不适合 GameTest 测试的内容：

**适合 GameTest 测试的内容**：

| 测试场景 | 说明 |
|---------|------|
| 载具拼装逻辑 | 在测试世界中放置方块/零件，调用拼装检查逻辑，断言是否形成合法载具 |
| 子系统联动 | 放置引擎 + 变速箱 + 能量网络，模拟红石信号激活，断言能量是否正确传输到车轮 |
| 碰撞检测 | 生成载具 + 障碍物，推进载具，断言碰撞事件是否触发、载具是否受损 |
| 载具属性计算 | 给定一组零件，调用属性聚合逻辑，断言载具速度/装甲/载重等计算值是否正确 |

**不适合 GameTest 测试的内容**：

| 测试场景 | 原因 |
|---------|------|
| GUI 渲染 | GameTestServer 无渲染管线，无法验证 HUD、仪表盘、GUI 界面 |
| 粒子效果 | 粒子属于客户端效果，服务端无法生成 |
| 摄像机控制 | 视角切换/第三人称跟随等纯客户端逻辑 |
| 载具外观渲染 | 需要 OpenGL 上下文，GameTestServer 不提供 |

### 7.2 针对 Spark-Core（物理引擎）的建议

Spark-Core 是 IK 解算器与行为树的物理引擎模组，以下是适合与不适合 GameTest 测试的内容：

**适合 GameTest 测试的内容**：

| 测试场景 | 说明 |
|---------|------|
| IK 解算器精度 | 给定关节链的已知位姿，调用 IK 解算器，断言末端位置误差在允许范围内（如 ≤ 0.01 格） |
| 行为树节点逻辑 | 创建行为树实例，注入模拟的黑板数据，逐 tick 推进，断言各节点返回值（SUCCESS/FAILURE/RUNNING） |
| Bullet 物理碰撞 | 生成刚体对，施加力/冲量，推进物理世界，断言碰撞检测结果（碰撞点、法线、冲量大小） |
| 物理约束求解 | 设置关节约束（铰链、弹簧等），施加外部力，断言约束是否被正确维持 |

**不适合 GameTest 测试的内容**：

| 测试场景 | 原因 |
|---------|------|
| 物理世界可视化 | 无法验证 debug 渲染（Bullet 调试线框、IK 解算过程线等） |
| 实时性要求高的场景 | GameTest 执行与物理 tick 紧密耦合，不适合测试需要精确物理时间步进精度的场景（建议用纯 Java 单元测试配合 JBullet 的 `stepSimulation` 完成） |
| 多线程物理模拟 | GameTest 在主服务器线程上运行 |

### 7.3 结构文件的生成策略

**传统方式**：在 Minecraft 游戏中用结构方块保存 `.nbt` 文件

**推荐方式：纯代码生成策略**

结构文件本质上是符合 NBT 格式的二进制文件。对于复杂或需要批量生成的测试场地，推荐编写 Python 脚本生成 `.nbt` 文件：

```python
# 示例：用 Python 生成简单的空场地结构文件
import nbtlib
from nbtlib.tag import Int, List, Compound

# 创建结构 NBT
structure = Compound({
    "size": List[Int]([5, 3, 5]),  # 5×3×5
    "entities": List[Compound](),   # 无实体
    "blocks": List[Compound](),     # 方块列表
    "palette": List[Compound](),    # 调色板
    "DataVersion": Int(3953),       # MC 1.21 数据版本
})

nbtlib.File(structure).save("empty_arena.nbt")
```

使用 `nbtlib` 库（`pip install nbtlib`）可轻松生成符合 Minecraft 格式的 `.nbt` 文件。

**优势**：

- 批量生成多组变体测试场地（不同尺寸、不同方块类型、不同预设实体）
- 可版本管理（Python 脚本提交到仓库，`.nbt` 作为构建产物）
- 无需手动进入 Minecraft 操作结构方块

### 7.4 通用最佳实践

以下建议适用于所有 Minecraft NeoForge 模组的 GameTest 开发：

1. **一个测试类一个 `@GameTestHolder`**：按功能模块分组，不要把所有测试塞进一个类
2. **结构文件命名规范**：`<功能>_<变体>.nbt`，如 `empty_arena.nbt`、`redstone_lab.nbt`、`combat_pit.nbt`
3. **浮点数断言容忍误差**：GameTest 环境与客户端/专用服务器存在浮点运算微小差异，使用 `EPSILON = 0.01f` 做容差比较
4. **timeoutTicks 不要设太短**：默认 200 ticks（10 秒），对于需要等待红石传播或实体 AI 运行的测试，适当增加到 400-600 ticks
5. **用 `succeed()` 而非自然结束**：明确调用 `helper.succeed()` 标记通过，避免因方法提前返回但未调用 succeed 造成的假阴性
6. **`fail()` 用于不可恢复的错误**：在 try-catch 中捕获异常后可用 `helper.fail("reason")` 主动标记失败
7. **测试方法必须独立**：不依赖其他测试的执行顺序，不共享可变状态

---

> **本文档基于 BallisticsFramework 的 GameTest 实现编写。**
> 源码参考：`src/main/java/io/github/sweetzonzi/ballistics_framework/example/gametest/BallisticsGameTest.java`
> 术语参考：`docs/glossary.md`
> 管线验证矩阵参考：`docs/example-包实现计划.md`

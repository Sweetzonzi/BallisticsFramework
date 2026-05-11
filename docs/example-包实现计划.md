# Example 包实现计划

> **目标**: 为纯 Lib 的 BallisticsFramework 引入可开关的示例内容包，提供可运行的开发示例与功能自测能力。
>
> **原则**: 示例代码常驻 `src/main` 随 JAR 发布。仅在开发环境且 config 开启时才注册示例内容；生产环境一律不注册。config 默认 true。

---

## 一、技术架构

### 1.1 代码组织（全部在 src/main 内）

```
src/main/java/io/github/sweetzonzi/ballistics_framework/
├── BallisticsFramework.java                       ← 修改：新增事件订阅 + config 注册
├── api/...                                        ← 不动
├── internal/...                                   ← 不动
├── mixin/...                                      ← 不动
└── example/                                       ← 新增包
    ├── ExampleConfig.java                         ← config 定义（ModConfigSpec + 环境检测）
    ├── ExampleContent.java                        ← 注册入口
    ├── ExampleCreativeTab.java                    ← 创造模式标签页
    ├── item/
    │   ├── ExampleMeleeWeapon.java                ← 测试近战武器
    │   ├── ExampleProjectileItem.java             ← 发射投射物的物品
    │   └── ExampleArmorItem.java                  ← 测试护甲物品
    ├── entity/
    │   ├── ExampleProjectileEntity.java           ← 测试投射物实体
    │   └── ExampleTargetEntity.java               ← 测试生物（BFHurtTarget）
    └── event/
        └── ExampleClientEvents.java               ← MOD 总线（client）：实体渲染器注册
```

### 1.2 Config 系统（NeoForge ModConfigSpec）

`ExampleConfig` 使用 NeoForge 标准配置系统，生成 `ballistics_framework-common.toml`：

```java
public final class ExampleConfig {
    public static final ModConfigSpec SPEC;
    public static final ConfigValue<Boolean> ENABLE_EXAMPLE_CONTENT;
    /** 是否为生产环境（非 dev）。生产环境强制关闭示例注册 */
    private static final boolean PRODUCTION = FMLLoader.isProduction();

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ENABLE_EXAMPLE_CONTENT = builder
                .comment("是否注册示例物品、实体和生物（仅在开发环境生效）。用于功能测试与开发参考。")
                .define("enableExampleContent", true);
        SPEC = builder.build();
    }

    /** 双重检查：仅在开发环境且 config 开启时才可注册示例内容 */
    public static boolean shouldEnable() {
        return !PRODUCTION && ENABLE_EXAMPLE_CONTENT.get();
    }
}
```

在 `BallisticsFramework` 构造函数中注册：

```java
public BallisticsFramework(IEventBus modEventBus) {
    // 注册 config（无论开关状态，config 始终存在）
    ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ExampleConfig.SPEC);

    // 示例内容注册（仅在开发环境 + config 开启时生效）
    ExampleContent.init(modEventBus);

    BFDamageExtensions.init();
}
```

`ExampleClientEvents` 通过 `@Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)` 注解独立订阅。

**双重保险**：

| 条件 | 生产环境 | 开发环境 |
|------|---------|---------|
| `enableExampleContent = true`（默认） | ❌ 强制跳过 | ✅ 注册 |
| `enableExampleContent = false` | ❌ 强制跳过 | ❌ 跳过 |
| config 文件不存在（首次启动） | ❌ 强制跳过 | ✅ 注册（默认值 true） |

> `FMLLoader.isProduction()` 来自 `net.neoforged.fml.loading.FMLLoader`（在 `neoforge` 依赖中直接可用）。

### 1.3 注册开关判断

所有注册入口统一调用 `ExampleConfig.shouldEnable()`：

```java
if (!ExampleConfig.shouldEnable()) return;
```

这确保：
- **开发环境**（IDE runClient/runServer）：默认自动启用示例内容，无需手动改 config
- **生产环境**（打包 JAR 丢进 mods 文件夹）：无论 config 设为 true/false 都**强制不注册**
- 开发者若想在 dev 中临时关闭，改 config 为 false 即可

---

## 二、示例内容详设

### 2.1 测试近战武器 `ExampleMeleeWeapon`

| 项目 | 说明 |
|------|------|
| **类名** | `ExampleMeleeWeapon` |
| **继承** | `net.minecraft.world.item.SwordItem` |
| **材质** | `Tiers.IRON`（复用原版铁剑属性） |
| **纹理** | 无自定义，复用原版铁剑纹理（`SwordItem` 自动使用材质对应的原版纹理） |
| **功能** | 左键攻击实体时拦截（覆写 hurtEnemy 或直接用原版行为 + 额外构造 BFDamageContext） |
| **穿深** | 60mm（对应 `ArmorLevel.HEAVY`） |
| **伤害值** | `baseDamage = 15f` |
| **回调** | 无（handler 传 null） |

**关键逻辑**：覆写 `hurtEnemy`，在调用 `super.hurtEnemy()` 前用 `BFDamageApi.hurt()` 替换原版伤害流程。

### 2.2 投射物物品 `ExampleProjectileItem`

| 项目 | 说明 |
|------|------|
| **类名** | `ExampleProjectileItem` |
| **继承** | `net.minecraft.world.item.Item` |
| **纹理** | 复用原版雪球纹理（`new Item.Properties()` + getDefaultInstance 用 `Items.SNOWBALL` 的模型） |
| **功能** | 右键发射 `ExampleProjectileEntity` |

注意：要让物品在手里显示雪球模型，需要在 `ExampleClientEvents` 中注册 `ItemProperties` 或在 model json 中指定。最简单做法是用 `Item` 默认行为 + 给物品在创造标签页拿即可，外观不重要。

### 2.3 投射物实体 `ExampleProjectileEntity`（★有回调）

| 项目 | 说明 |
|------|------|
| **类名** | `ExampleProjectileEntity` |
| **继承** | `net.minecraft.world.entity.projectile.ThrowableProjectile` |
| **实现接口** | `BFDamageHandler`（自身作为回调 handler） |
| **渲染** | `ThrownItemRenderer`（复用雪球渲染器） |
| **穿深** | 200mm（`ArmorLevel.SUPER_HEAVY_3`，一定能击穿示例护甲 40mm） |
| **伤害值** | `baseDamage = 25f` |
| **速度** | 飞行初始速度 2.0 m/block |

**回调实现**：

| 回调方法 | 行为 |
|----------|------|
| `onPenetrated` | `LOGGER.info` 输出击穿信息 |
| `onBlocked` | `LOGGER.info` 输出未击穿信息 |
| `onRicochet` | `LOGGER.info` 输出跳弹信息 |
| `onOvermatch` | `LOGGER.info` 输出碾压信息 |
| `onSpall` | `LOGGER.info` 输出破片信息 |

> 粒子效果涉及 client-only 代码，投射物实体在服务端运行，回调在服务端触发。
> 使用 `LOGGER.info` 输出到日志是最稳妥的验证方式；
> 后续若有需求可通过发包到客户端添加粒子。

**命中逻辑**（`onHitEntity` 中）：

```java
BFDamageContext ctx = BFDamageContext.builder()
    .source(damageSources().thrown(this, getOwner()))
    .baseDamage(25f)
    .hitVelocity(getDeltaMovement())
    .hitPoint(result.getPos())
    .hitNormal(result.getPos().subtract(target.position()).normalize())
    .penetration(200f)
    .build();

// 使用 dealDamage 快捷方法（自动注入 this 为 handler）
this.dealDamage(target, ctx);
discard();
```

### 2.4 测试生物 `ExampleTargetEntity`

| 项目 | 说明 |
|------|------|
| **类名** | `ExampleTargetEntity` |
| **继承** | `net.minecraft.world.entity.PathfinderMob` |
| **实现接口** | `BFHurtTarget` |
| **外观** | 复用原版僵尸模型 + 材质（`"textures/entity/zombie/zombie.png"`） |
| **基础属性** | HP 60，无自然生成，无 AI |
| **AI** | 空 `Brain` 或 `GoalSelector` 不注册任何 Goal |
| **渲染器** | `HumanoidMobRenderer<ExampleTargetEntity, ZombieModel<ExampleTargetEntity>>`，复用 `ZombieModel` |

**BFHurtTarget 实现**：

| 方法 | 实现 |
|------|------|
| `getArmorLevel(ctx)` | 返回 `ArmorLevel.UNARMORED_1` |
| `createContextFromVanilla(source, amount)` | 返回 null（退回原版） |

> **设计意图**: 裸体靶子 + 可穿戴示例护甲，测试复合目标管线（分支0）。

### 2.5 测试护甲套装 `ExampleArmorItem`

| 项目 | 说明 |
|------|------|
| **类名** | `ExampleArmorItem` |
| **继承** | `net.minecraft.world.item.ArmorItem` |
| **实现接口** | `BFArmorMaterial` |
| **材质** | `ArmorMaterials.IRON`（复用原版铁甲属性） |
| **纹理** | `ArmorItem` 自动使用 `ArmorMaterial` 对应的原版铁甲纹理 |
| **护甲等级** | `ArmorLevel.HEAVY`（40mm RHA） |

**BFArmorMaterial 实现**：

| 方法 | 实现 |
|------|------|
| `getArmorLevel(slot, ctx)` | 返回 `ArmorLevel.HEAVY` |
| `createContextFromVanilla(source, amount)` | 默认（自动接管所有原版伤害） |

---

## 三、注册清单

### 3.1 物品（DeferredRegister.Items）

| 注册名 | 类 | 创造标签页 |
|--------|-----|-----------|
| `example_melee_weapon` | `ExampleMeleeWeapon` | `example_weapons` |
| `example_projectile` | `ExampleProjectileItem` | `example_weapons` |
| `example_helmet` | `ExampleArmorItem` | `example_equipment` |
| `example_chestplate` | `ExampleArmorItem` | `example_equipment` |
| `example_leggings` | `ExampleArmorItem` | `example_equipment` |
| `example_boots` | `ExampleArmorItem` | `example_equipment` |

### 3.2 实体（DeferredRegister<EntityType<?>>）

| 注册名 | 类 | 尺寸 | 追踪范围 |
|--------|-----|------|---------|
| `example_projectile` | `ExampleProjectileEntity` | 0.25×0.25 | 64 / 1 |
| `example_target` | `ExampleTargetEntity` | 0.6×1.8 | 80 / 3 |

### 3.3 实体渲染器（仅在 client 注册）

| 实体 | 渲染器 | 模型 | 材质 |
|------|--------|------|------|
| `ExampleProjectileEntity` | `ThrownItemRenderer` | 无（复用） | 无 |
| `ExampleTargetEntity` | `HumanoidMobRenderer` | `ZombieModel` | `textures/entity/zombie/zombie.png` |

### 3.4 创造标签页

| 标签页 ID | 显示名 |
|-----------|--------|
| `example_weapons` | `BF Example - Weapons` |
| `example_equipment` | `BF Example - Equipment` |

---

## 四、事件订阅架构

### 4.1 BallisticsFramework 构造函数

```java
public BallisticsFramework(IEventBus modEventBus) {
    // config 注册（无论开关状态，config 始终存在）
    ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ExampleConfig.SPEC);

    // 示例内容注册（仅在开发环境 + config 开启时生效）
    ExampleContent.init(modEventBus);

    BFDamageExtensions.init();
}
```

### 4.2 ExampleContent — 静态注册入口

通过 `init(IEventBus)` 方法统一处理 DeferredRegister。内部调用 `ExampleConfig.shouldEnable()` 判断：

```java
public final class ExampleContent {
    // DeferredRegister 定义（始终初始化，因为声明不产生副作用）
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("ballistics_framework");
    private static final DeferredRegister<EntityType<?>> ENTITIES = ...;

    // 物品/实体注册对象定义…

    public static void init(IEventBus bus) {
        if (!ExampleConfig.shouldEnable()) return;
        ITEMS.register(bus);
        ENTITIES.register(bus);
    }
}
```

### 4.3 ExampleClientEvents — 自动订阅

```java
@Mod.EventBusSubscriber(modid = BallisticsFramework.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ExampleClientEvents {
    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        if (!ExampleConfig.shouldEnable()) return;
        // 注册渲染器
    }
}
```

---

## 五、修改文件清单

### 5.1 新建文件（9 个）

| 文件路径（相对于 src/main/java/.../ballistics_framework/example/） | 说明 |
|---|---|
| `ExampleConfig.java` | NeoForge ModConfigSpec + FMLLoader.isProduction 环境检测 |
| `ExampleContent.java` | DeferredRegister 定义 + init(IEventBus) 静态入口 |
| `ExampleCreativeTab.java` | 创造标签页 |
| `item/ExampleMeleeWeapon.java` | 近战武器 |
| `item/ExampleProjectileItem.java` | 投射物发射物品 |
| `item/ExampleArmorItem.java` | 护甲物品 |
| `entity/ExampleProjectileEntity.java` | 投射物实体 + BFDamageHandler |
| `entity/ExampleTargetEntity.java` | 测试生物 + BFHurtTarget |
| `event/ExampleClientEvents.java` | Client 渲染器等注册 |

### 5.2 修改文件（1 个）

| 文件 | 修改内容 |
|------|---------|
| `BallisticsFramework.java` | 构造函数注入 `IEventBus`；注册 config；调用 `ExampleContent.init()` |

---

## 六、管线验证矩阵

| 场景 | 攻击方 | 防御方 | 预期结果 |
|------|--------|--------|---------|
| 1. 近战武器裸打靶子 | `ExampleMeleeWeapon`(60mm) | `ExampleTargetEntity`(0mm) | 击穿，伤害 15 |
| 2. 近战武器打穿护甲的靶子 | `ExampleMeleeWeapon`(60mm) | `ExampleTargetEntity` + 穿戴示例护甲(40mm) | 同级击穿，15×0.65=9.75 |
| 3. 投射物打穿护甲的靶子 | `ExampleProjectileEntity`(200mm) | `ExampleTargetEntity` + 穿戴示例护甲(40mm) | 越级击穿，伤害 25；日志输出 `onPenetrated` + `onOvermatch` |
| 4. 投射物打裸体靶子 | `ExampleProjectileEntity`(200mm) | `ExampleTargetEntity`(0mm) | 击穿，伤害 25；日志输出 `onPenetrated` + `onOvermatch` |
| 5. 投射物打地/墙面 | `ExampleProjectileEntity`(200mm) | 方块 | 实体被移除，无伤害 |
| 6. 裸体靶子吃原版伤害 | 原版僵尸挠 | `ExampleTargetEntity`(0mm) | 走原版（createContextFromVanilla 返回 null） |

---

## 七、日志规范

为确保 agent 调试和人工排查时有充分信息，所有关键位置需打 `LOGGER.info/error` 日志。

### 7.1 日志宏定义

使用同一个 logger 实例（放在 `ExampleContent`）：

```java
public static final Logger LOGGER = LogUtils.getLogger();
```

### 7.2 必打日志点

| 位置 | 日志内容 | 级别 |
|------|---------|------|
| `ExampleContent.init()` 入口 | `"[BF-Example] 示例内容注册 = {} (开发环境 = {}, 生产环境 = {}, config = {})"` — 输出 shouldEnable 结果及各条件值 | INFO |
| 每个 DeferredRegister register | `"[BF-Example] 已注册 {} 个物品 / {} 个实体"` — 分别输出 count | INFO |
| `ExampleCreativeTab` 创建 | `"[BF-Example] 创造标签页已创建: {}, {}"` — 两个标签页 ID | INFO |
| `ExampleClientEvents` 渲染器注册 | `"[BF-Example] 已注册实体渲染器: ExampleProjectileEntity, ExampleTargetEntity"` | INFO |
| `ExampleMeleeWeapon.hurtEnemy` | `"[BF-Example] 近战武器命中: target={}, baseDamage={}, penetration={}"` — 目标名称、伤害、穿深 | INFO |
| `ExampleMeleeWeapon` API 调用返回 | `"[BF-Example] 近战武器伤害结果: dealt={}"` — 实际造成的伤害量 | INFO |
| `ExampleProjectileItem` 发射时 | `"[BF-Example] 投射物已发射: shooter={}, velocity={}"` — 发射者名称、速度 | INFO |
| `ExampleProjectileEntity.onHitEntity` | `"[BF-Example] 投射物命中实体: target={}, baseDamage={}, penetration={}"` — 目标名称、伤害、穿深 | INFO |
| `ExampleProjectileEntity` BFDamageHandler 每个回调 | `"[BF-Example] 回调 {}: target={}"` — 回调名（PENETRATED/BLOCKED/RICOCHET/OVERMATCH/SPALL）+ 目标名称 | INFO |
| `ExampleProjectileEntity` 伤害返回 | `"[BF-Example] 投射物伤害结果: dealt={}"` | INFO |
| `ExampleTargetEntity` 构造函数 | `"[BF-Example] 靶子实体已创建: pos={}"` — 位置 | DEBUG |
| `ExampleTargetEntity.getArmorLevel` | `"[BF-Example] 靶子 getArmorLevel 被调用: ctx.penetration={}"` — 上下文穿深 | DEBUG |
| `ExampleArmorItem.getArmorLevel` | `"[BF-Example] 示例护甲 getArmorLevel: slot={}, level={}"` — 槽位、返回等级 | DEBUG |
| `BallisticsFramework` 构造函数 | `"[BF-Example] Config 已注册。shouldEnable={} (生产环境={}, config={})"` | INFO |

> `DEBUG` 级别日志需在 `logLevel` 中设为 `DEBUG` 才能看到（开发 run config 默认已是 DEBUG）。

### 7.3 日志前缀约定

所有示例相关日志统一使用 `[BF-Example]` 前缀，便于在控制台中 `grep` 过滤。

---

## 八、不做的内容

| 项目 | 原因 |
|------|------|
| 自定义纹理/模型 | 全部复用原版 |
| 方块/方块实体 | 示例不需要 |
| 数据生成（DataGen） | 无自定义 loot/recipe/tag |
| 合成配方 | 物品从创造标签页拿 |
| GameTest | 暂不引入 |
| 本地化 lang 文件 | 硬编码英文名（示例性质） |
| 粒子效果 | 回调使用 LOGGER.info 输出，避免 client/server 分离复杂度 |

---

## 九、实施步骤

### 步骤1：创建 `ExampleConfig`
- 创建 `src/main/java/.../ballistics_framework/example/ExampleConfig.java`
- NeoForge ModConfigSpec + `FMLLoader.isProduction()` + `shouldEnable()`
- 编译检查：`gradlew build`

### 步骤2：修改 `BallisticsFramework`
- 构造函数注入 `IEventBus`
- 注册 config：`ModLoadingContext.get().registerConfig(...)`
- 调用 `ExampleContent.init(modEventBus)`
- 添加 config 状态日志
- 编译检查：`gradlew build`

### 步骤3：创建实体类
- `ExampleProjectileEntity`（继承 `ThrowableProjectile`，实现 `BFDamageHandler`，含 5 个回调日志）
- `ExampleTargetEntity`（继承 `PathfinderMob`，实现 `BFHurtTarget`，含 getArmorLevel 日志）
- 编译检查：`gradlew build`

### 步骤4：创建物品类
- `ExampleMeleeWeapon`（继承 `SwordItem`，覆写 `hurtEnemy`，含命中日志）
- `ExampleProjectileItem`（继承 `Item`，覆写 `use` 发射实体，含发射日志）
- `ExampleArmorItem`（继承 `ArmorItem`，实现 `BFArmorMaterial`，含 getArmorLevel 日志）
- 编译检查：`gradlew build`

### 步骤5：创建 `ExampleContent`
- DeferredRegister 定义（ITEMS + ENTITIES）
- `init(IEventBus)` 静态入口，含 shouldEnable 判断日志 + 注册数量日志
- 编译检查：`gradlew build`

### 步骤6：创建 `ExampleCreativeTab`
- 两个标签页：`example_weapons`、`example_equipment`
- 含标签页创建日志
- 编译检查：`gradlew build`

### 步骤7：创建 `ExampleClientEvents`
- `@Mod.EventBusSubscriber` 注解，`Dist.CLIENT`
- 订阅 `EntityRenderersEvent.RegisterRenderers`
- 含渲染器注册日志
- 编译检查：`gradlew build`

### 步骤8：构建验证
```bash
# 完整构建（确保 example 代码可编译通过）
gradlew build
# 验证 JAR 内容（确认 example 包在 JAR 内）
jar -tf build/libs/*.jar | grep -i example
```

### 步骤9：runData 验证
```bash
gradlew runData
```
- 预期：运行成功，无崩溃。因无自定义 DataGen，此步骤主要验证 example 注册逻辑不影响数据生成流程。

### 步骤10：runClient 验证
```bash
gradlew runClient
```
- 进入世界后打开创造模式物品栏
- 检查 "BF Example - Weapons" 和 "BF Example - Equipment" 标签页是否出现
- 检查物品图标、护甲、投射物是否正常渲染
- 控制台检查 `[BF-Example]` 日志输出：
  - config 状态日志（shouldEnable 各条件值）
  - 物品/实体注册数量日志
  - 渲染器注册日志

### 步骤11：执行管线验证矩阵

在 runClient 中逐项测试：

| # | 操作 | 检查点 |
|---|------|--------|
| 1 | 拿 `ExampleMeleeWeapon`（60mm）→ 打 `ExampleTargetEntity`（0mm）裸体靶子 | 日志：`近战武器命中: target=..., baseDamage=15, penetration=60` → `伤害结果: dealt=15.0` |
| 2 | 给靶子穿戴示例护甲四件套（40mm）→ 同一武器再打 | 日志：`伤害结果: dealt=9.75`（同级击穿 ×0.65） |
| 3 | 拿 `ExampleProjectileItem` → 右键发射投射物 → 命中穿护甲的靶子 | 日志：`投射物命中实体` → `回调 PENETRATED` → `回调 OVERMATCH` → `伤害结果: dealt=25` |
| 4 | 投射物打裸体靶子 | 日志：`回调 PENETRATED` → `回调 OVERMATCH` → `伤害结果: dealt=25` |
| 5 | 投射物打墙壁/地面 | 投射物消失，无伤害日志 |
| 6 | 用原版方式（空手/原版武器）打 `ExampleTargetEntity` | 无 `[BF-Example]` 伤害日志（createContextFromVanilla 返回 null，走原版） |

### 步骤12：runServer 验证
```bash
gradlew runServer
```
- 服务器启动成功后检查日志：`[BF-Example] shouldEnable=false (生产环境=true, ...)`
- **预期：生产环境强制 false，不应出现任何物品/实体注册日志**
- 用 `gradlew runClient` 连入此服务器
- 检查创造标签页不出现 BF Example 标签页

### 步骤13：生产环境模拟验证
- 执行 `gradlew build` 获取 JAR
- 将 JAR 直接丢进原版 NeoForge 客户端的 `mods` 文件夹
- 启动游戏，检查创造物品栏：**不应出现 BF Example 标签页**
- 检查 `config/ballistics_framework-common.toml`：文件应正常生成，`enableExampleContent = true`（默认值），但由于生产环境，实际不注册
- 检查日志：`[BF-Example] shouldEnable=false (生产环境=true, config=true)`

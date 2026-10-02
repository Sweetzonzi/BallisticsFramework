# A.1 API 参考

本页以类为单位，按字母顺序列出协议层全部公开 API 的完整签名和说明。除末尾标注 `internal` 的条目外，所有类均位于 `io.github.sweetzonzi.ballistics_framework.api` 包。

---

## ArmorLevel

**`enum ArmorLevel`** — 离散穿甲/护甲等级枚举，共 13 级（含元等级 UNPENETRABLE）。按防护强度升序排列。

### 枚举常量

| 常量 | RHA 区间 | ord |
|------|---------|-----|
| `UNARMORED_1` | (0, 1] | 0 |
| `UNARMORED_2` | (1, 3] | 1 |
| `LIGHT_1` | (3, 5] | 2 |
| `LIGHT_2` | (5, 10] | 3 |
| `MEDIUM` | (10, 20] | 4 |
| `HEAVY` | (20, 40] | 5 |
| `SUPER_HEAVY_1` | (40, 80] | 6 |
| `SUPER_HEAVY_2` | (80, 150] | 7 |
| `SUPER_HEAVY_3` | (150, 300] | 8 |
| `SUPER_HEAVY_4` | (300, 600] | 9 |
| `SUPER_HEAVY_5` | (600, 1200] | 10 |
| `SUPER_HEAVY_6` | (1200, 2000] | 11 |
| `UNPENETRABLE` | (2000, ∞) | 12 |

### 静态方法

```java
// 根据 RHA 值查找对应等级（向上映射，取第一个 upperRha >= rha）
static ArmorLevel fromRha(float rha)
```

### 实例方法

```java
// 等级边界
float upperRha()                      // 本等级 RHA 上限（mm），包含该值
float lowerRha()                      // 本等级 RHA 下限（mm），不包含该值
float medianRha()                     // 本等级 RHA 中位值 = (lower + upper)/2

// 判定
boolean canDefeat(ArmorLevel armorLevel)  // 当前等级能否击穿目标等级（>= 比较，等于算击穿）

// 本地化显示
Component getArmorDisplayName()       // 护甲显示名（如"重型防护"）
Component getPenetrationDisplayName() // 穿甲显示名（如"重型穿深"）
```

---

## PenetrationResult

**`enum PenetrationResult`** — 穿甲判定的一次性结果。三者互斥。

| 常量 | 含义 |
|------|------|
| `PENETRATED` | 击穿——穿深足以穿透装甲 |
| `BLOCKED` | 未击穿（含钝伤等，伤害量由护甲侧自由决定） |
| `RICOCHET` | 跳弹——穿深不足且入射角过大，弹丸偏转飞走 |

---

## BFDamageApi

**`public final class BFDamageApi`** — 协议层唯一对外入口。构造函数为 `private`，所有方法为 `static`。

### 静态方法

```java
// ========== 核心入口 ==========

/**
 * 发起一次协议伤害。按优先级判断目标类型并进入五个分支之一：
 *   分支0：目标同时是 BFHurtTarget + LivingEntity + 穿戴 BFArmorMaterial
 *          → 双层串联管线（护甲先拦截，本体后判定）
 *   分支1：target instanceof BFHurtTarget
 *          → 标准穿甲管线
 *   分支1.5：target instanceof BFHitResolver（且不满足分支1）
 *          → 用 ctx.hitPoint() 与 BFHitResolver.searchDelta(ctx.hitVelocity())
 *            解析出实际目标，再经 contextForResolvedTarget 重建上下文后转发；
 *            解析为未命中时返回 0f
 *   分支2：target instanceof LivingEntity && 穿戴 BFArmorMaterial 护甲
 *          → 通过 BFArmorAdapter 走管线（适配器模式）
 *   分支3：target instanceof Entity
 *          → 原版 entity.hurt(source, baseDamage) 回退
 *   分支4：以上皆不满足 → 记录 error 日志并返回 0f（无分支可承接）
 * 分支编号顺序即判定顺序，既有编号不重排。契约归属：
 * docs/BFDamageApi-hurt解析器转发计划.md。
 * @param target 伤害目标
 * @param ctx    完整命中上下文
 * @return       实际造成的伤害量
 */
static float hurt(Object target, BFDamageContext ctx)

// ========== 承载者投递 ==========

/**
 * 把一次已结算的伤害交给它的承载实体（carrier）。承载者穿戴的
 * BFArmorMaterial 护甲参与本次判定。等价于 deliverTo(carrier, ctx, false)。
 * @param carrier 承载实体；调用期内被压为上下文栈顶
 * @param ctx     已修正的投递上下文：baseDamage 为穿透后的伤害量，
 *                penetration 为到达承载者的残余穿深
 * @return        是否落地，见 deliverTo(Entity, BFDamageContext, boolean)
 */
static boolean deliverTo(Entity carrier, BFDamageContext ctx)

/**
 * 把一次已结算的伤害交给它的承载实体，并可跳过承载者的贴身护甲层。
 * 不做穿甲判定、不修正数值、不执行承载者作为 BFHurtTarget 的本体层、
 * 不经过 BFHitResolver 路由。只做三件事：压栈 → 可选地过一遍承载者
 * 穿戴的 BFArmorMaterial 护甲层 → 交给原版 carrier.hurt()。护甲层跑过
 * 且折算结果大于 0 时，交给原版的是该折算结果，否则是 ctx.baseDamage()。
 * 与发起入口 hurt 的分工：hurt 在目标是纯解析器时会先解析再转发，
 * deliverTo 始终落在承载者自身（契约见 docs/BFDamageApi-hurt解析器转发计划.md §四）。
 * 调用时上下文栈顶不得已存在承载者自身；栈顶已是承载者时返回 false 并
 * 记录警告（判据即 hasContextFor，只比较栈顶；栈更深处的目标由调用方
 * 保证，见投递计划的 §5.8）。契约归属：docs/BFDamageApi-deliverTo投递计划.md。
 * @param carrier       承载实体
 * @param ctx           已修正的投递上下文
 * @param ignoreBFArmor true 表示在"投递已绕开判定"的基础上，再跳过承载者穿戴的
 *                      BFArmorMaterial 护甲层：不跑三件套、不触发任何穿甲回调、
 *                      不消耗护甲耐久，伤害直接交给原版 hurt
 * @return 是否落地。false 表示贴身护甲判定为未击穿/跳弹且 calculateFinalDamage
 *         返回 0，或原版拒绝（无敌帧内且未超过上次伤害、已死亡、免疫、玩家受到
 *         的伤害量恰为 0）。true 不保证扣了血：原版护甲、附魔、吸收都可能把伤害
 *         削到 0。与护甲层回调配合即可区分"护甲挡下"与"原版拒绝"
 */
static boolean deliverTo(Entity carrier, BFDamageContext ctx, boolean ignoreBFArmor)

// ========== 上下文查询 ==========

/**
 * 判断当前线程中是否已有针对指定目标的协议上下文。
 * 用于 Mixin 重入守卫。
 * @param target 要检查的目标
 * @return true 表示该目标正处于协议伤害管线中
 */
static boolean hasContextFor(Object target)

/**
 * 获取当前线程中指定目标的协议上下文。
 * 在 BFHurtTarget 的管线方法内调用，以读取命中点、入射方向等信息。
 * @param target 要获取上下文的目标（通常传入 this）
 * @return 当前协议上下文；不在管线内或栈顶目标不匹配则返回 null
 */
@Nullable
static BFDamageContext getContextFor(Object target)

// ========== 命中前目标解析 ==========

/**
 * 判断命中对象是否具有协议感知能力。
 * 对象实现 BFHitResolver 或 BFHurtTarget 时返回 true。
 * @param hit 命中对象（实体、物理体属主或其他包装体）
 * @return true 表示可走协议解析
 */
static boolean isProtocolAware(Object hit)

/**
 * isProtocolAware(Object) 的实体版重载，仅作转发。
 * @deprecated 参数类型已放宽到 Object；本重载保留用于二进制兼容。
 */
@Deprecated(since = "1.0.0.alpha.11")
static boolean isProtocolAware(Entity entity)

/**
 * 解析命中目标。若命中对象实现了 BFHitResolver，执行精确验证并返回
 * 修正后的目标与几何。否则若其自身是 BFHurtTarget，直接包装返回。
 * 返回 null 表示未命中（投射物应继续飞行）。
 * @param hit      命中对象（实体、物理体属主或其他包装体）
 * @param hitPoint 报告的命中点
 * @param delta    搜索矢量，其模为搜索距离上限（m），方向为命中方向
 * @return 解析结果；null 表示未命中
 */
@Nullable
static BFHitResolveResult resolveHitTarget(Object hit, Vec3 hitPoint, Vec3 delta)

/**
 * resolveHitTarget(Object, Vec3, Vec3) 的实体版重载，仅作转发。
 * @deprecated 参数类型已放宽到 Object；本重载保留用于二进制兼容。
 */
@Deprecated(since = "1.0.0.alpha.11")
@Nullable
static BFHitResolveResult resolveHitTarget(Entity hitEntity, Vec3 hitPoint, Vec3 delta)

/**
 * 按解析结果重建"交给实际目标"的上下文。框架自身的两条转发路径
 * （hurt 的分支1.5、BFHurtInterceptor 情况3）共用它，使"框架代劳解析"
 * 与"调用方自行解析"走同一套规则：
 *   命中点 = correctedHitPoint()（无条件替换——护甲侧 mapHitToSlot
 *            用上下文命中点判定着弹槽位，入参命中点通常是代理 AABB 的交点）
 *   法线   = correctedHitNormal()（为零矢量时保留原上下文的法线，零矢量是
 *            "未修正"哨兵：resolveHitTarget 对纯 BFHurtTarget 即如此填充）
 *   扩展   = 原容器拷贝为底，再并入解析结果的容器（后者覆盖同名键）
 * 其余字段（source、baseDamage、hitVelocity、penetration、handler）原样保留。
 * 外部模组一般不需要调用——按 resolveHitTarget 的示例自行构造上下文即可，
 * 两者语义一致。契约归属：docs/BFDamageApi-hurt解析器转发计划.md。
 * @param ctx      调用方传入的上下文（几何未经修正）
 * @param resolved 解析结果
 * @return         应用了修正几何与合并扩展的新上下文
 */
static BFDamageContext contextForResolvedTarget(BFDamageContext ctx, BFHitResolveResult resolved)

/**
 * 从原版 HitResult 解析命中目标。自动从 EntityHitResult 中提取命中实体和命中点。
 * 非 EntityHitResult（如方块命中）返回 null。
 * @param hitResult 原版命中结果
 * @param delta     搜索矢量
 * @return 解析结果；null 表示未命中或无效命中类型
 */
@Nullable
static BFHitResolveResult resolveHitTarget(HitResult hitResult, Vec3 delta)
```

---

## BFDamageContext

**`public record BFDamageContext(...)`** — Java 16 record，一次命中的完整上下文。构造后只读。

### Record 组件

| 组件 | 类型 | 说明 |
|------|------|------|
| `source` | `DamageSource` | 原版伤害来源。必须非 null |
| `baseDamage` | `float` | 标称伤害量 |
| `hitVelocity` | `Vec3` | 命中速度矢量（世界坐标，m/s） |
| `hitPoint` | `Vec3` | 命中点世界坐标 |
| `hitNormal` | `Vec3` | 命中面法线，指向面外侧 |
| `penetration` | `float` | 理论穿深（mm RHA） |
| `extensions` | `BFDamageExtensions` | 扩展数据容器 |
| `handler` | `@Nullable BFDamageHandler` | 回调接口（可为 null） |

### 静态方法

```java
// 获取 Builder，通过它流式构造上下文
static BFDamageContextBuilder builder()
```

### 实例方法

```java
// 获取 handler（不参与 equals/hashCode）
@Nullable
BFDamageHandler getHandler()

// 返回替换了 handler 的新上下文实例（其余字段原样拷贝）
BFDamageContext withHandler(@Nullable BFDamageHandler handler)

// 构造子上下文——仅替换 baseDamage 和 penetration，其余字段不变。
// 用于双层串联管线中护甲层向本体层传递修正后的弹头状态
BFDamageContext childContext(float newBaseDamage, float newPenetration)

// 穿深 → 穿甲等级映射（等于 ArmorLevel.fromRha(penetration)）
ArmorLevel getPenetrationLevel()
```

---

## BFDamageContextBuilder

**`public final class BFDamageContextBuilder`** — `BFDamageContext` 的流式构建器。通过 `BFDamageContext.builder()` 获取。

### Setter 方法

```java
// source 为唯一必须字段，其余均有默认值
BFDamageContextBuilder source(DamageSource source)    // 【必须设置】
BFDamageContextBuilder baseDamage(float baseDamage)    // 默认 0
BFDamageContextBuilder hitVelocity(Vec3 hitVelocity)   // 默认 Vec3.ZERO
BFDamageContextBuilder hitPoint(Vec3 hitPoint)         // 默认 Vec3.ZERO
BFDamageContextBuilder hitNormal(Vec3 hitNormal)       // 默认 (0,1,0) 朝上
BFDamageContextBuilder penetration(float penetration)  // 默认 0
BFDamageContextBuilder extensions(BFDamageExtensions extensions)  // 默认自动新建空实例
BFDamageContextBuilder handler(@Nullable BFDamageHandler handler) // 默认 null
```

### 终端方法

```java
// 构建上下文。source 为 null 时抛出 NullPointerException
BFDamageContext build()
```

---

## BFDamageExtensions

**`public final class BFDamageExtensions`** — 类型安全的扩展数据读写容器。每个 `BFDamageContext` 持有一个实例。

### 静态方法

```java
/**
 * 注册一个扩展 key（全局单例）。
 * @param id                 唯一标识符（ResourceLocation）。全局唯一，重名将抛异常
 * @param type               值类型（用于编译期类型检查）
 * @param defaultValueFactory 默认值工厂（get 未命中时调用）
 * @param <T>                值类型
 * @return 注册完成的 key。建议存为 public static final 常量
 */
static <T> BFDamageExtensionKey<T> register(ResourceLocation id, Class<T> type, Supplier<T> defaultValueFactory)
```

### 预定义扩展 Key 常量

```java
static final BFDamageExtensionKey<Float> FUSE_DELAY  // 引信延迟（秒），默认 0（瞬发）
static final BFDamageExtensionKey<Float> CALIBER     // 弹体口径（mm），默认 7.62（典型步枪弹）
static final BFDamageExtensionKey<Float> MASS        // 弹体质量（kg），默认 10
static final BFDamageExtensionKey<Float> IMPULSE     // 命中冲量标量（N·s），默认 0（无额外冲量）
```

### 构造器

```java
// 创建一个空扩展容器
BFDamageExtensions()

// 创建已有扩展容器的浅拷贝（后续 set 互不影响）
BFDamageExtensions(BFDamageExtensions other)
```

### 实例方法

```java
// 读取扩展值。未设置时返回 key 注册时的默认值（永不返回 null）
<T> T get(BFDamageExtensionKey<T> key)

// 检查扩展值是否已被显式设置（与 get 不同，不会退回默认值）
// 用于区分"未设置"与"显式设为默认值"——例如 IMPULSE 为 0 时：
//   未设置 → 接收方使用自有击退公式；显式设为 0 → 明确不施加击退
boolean contains(BFDamageExtensionKey<?> key)

// 写入扩展值。key 和 value 均不能为 null
<T> void set(BFDamageExtensionKey<T> key, T value)

// 返回此扩展容器的浅拷贝（独立内部映射）
BFDamageExtensions copy()

// 把另一个容器的值合并进本容器：other 中显式设置过的键覆盖同名键，
// 本容器其余键保持不变。other 为空容器时是空操作；other == this 时也是空操作。
// 框架用它把解析结果的扩展数据并入转发上下文（先 copy 再 merge，故不修改来源）。
void mergeFrom(BFDamageExtensions other)
```

---

## BFDamageExtensionKey

**`public final class BFDamageExtensionKey<T>`** — 类型安全的泛型扩展键。通过 `BFDamageExtensions.register()` 创建。构造器为包可见，外部不可直接 new。

### 实例方法

```java
ResourceLocation getId()    // 获取注册时的唯一标识符
Class<T> getType()          // 获取值类型
T getDefaultValue()         // 获取注册时的默认值
```

---

## BFDamageHandler

**`public interface BFDamageHandler`** — 伤害发起方回调接口。由武器/弹头模组实现。所有回调均有 `default` 空实现，按需覆写。

### 事件回调（均为 default 空实现）

```java
default void onPenetrated(BFHurtTarget target, BFDamageContext ctx)  // 击穿回调
default void onBlocked(BFHurtTarget target, BFDamageContext ctx)     // 未击穿回调
default void onRicochet(BFHurtTarget target, BFDamageContext ctx)    // 跳弹回调
default void onOvermatch(BFHurtTarget target, BFDamageContext ctx)   // 超匹配回调（穿深远超装甲厚度）
default void onSpall(BFHurtTarget target, BFDamageContext ctx)       // 破片回调（弹体碎裂）
```

### 判定方法（均为 default 实现，可覆写）

```java
// 判定是否为超匹配（碾压）。默认：击穿 且 modifiedPen > RHA × 1.5
default boolean isOvermatch(BFHurtTarget target, BFDamageContext ctx, PenetrationResult result)

// 判定是否产生破片。默认：BLOCKED → true；PENETRATED 且非超匹配 → true；RICOCHET → false
default boolean isSpall(BFHurtTarget target, BFDamageContext ctx, PenetrationResult result)
```

### 便捷方法

```java
// 发起协议伤害，并将自身注入上下文。等价于 BFDamageApi.hurt(target, ctx.withHandler(this))
// @return 实际造成的伤害量
default float dealDamage(Object target, BFDamageContext ctx)
```
---
## BFHurtTarget

**`public interface BFHurtTarget`** — 协议伤害目标接口。声明实体自行处理穿甲判定。

### Abstract 方法（必须实现）

```java
// 返回命中部位对应的离散护甲等级
ArmorLevel getArmorLevel(BFDamageContext ctx)

// 执行实际伤害。通常委托 super.hurt(source, amount)
boolean hurt(DamageSource source, float amount)

// 将原版伤害转换为协议上下文。返回 null 则退回原版流程
@Nullable
BFDamageContext createContextFromVanilla(DamageSource source, float amount)
```

### Default 方法（可选覆写，按管线顺序排列）

```java
// ① 返回命中部位 RHA 等效厚度。默认取 getArmorLevel().medianRha()
default float getRHA(BFDamageContext ctx)

// ② 修正穿深（减效：爆反拦截、间隙衰减等）。默认直接返回 ctx.penetration()
default float modifyPenetration(BFDamageContext ctx)

// ③ 穿甲判定。默认委托 isArmorPenetrated，仅区分 PENETRATED/BLOCKED
default PenetrationResult resolvePenetration(BFDamageContext ctx)

// ④ 根据穿甲结果计算最终伤害。默认三级模型：越级 100%、同级 65%、未击穿/跳弹 0
default float calculateFinalDamage(BFDamageContext ctx, PenetrationResult result)

// ⑤ 伤害施加完成后的回调。提供完整上下文供后效处理（音效、粒子、技能触发等）
default void afterHurt(BFDamageContext ctx, PenetrationResult result, float finalDamage)

// ③的辅助方法。默认基于等级比较（>=，等于算击穿）
default boolean isArmorPenetrated(BFDamageContext ctx)

// 获取协议目标对应的实体引用。默认：若自身是 Entity 则返回 this，否则 null
@Nullable
default Entity getBFEntity()
```

---

## BFArmorMaterial

**`public interface BFArmorMaterial`** — 协议护甲物品接口。供护甲物品类实现，使穿戴者自动获得穿甲判定能力。每个方法均以 `(EquipmentSlot slot, BFDamageContext ctx)` 为签名——操作粒度下沉到装备槽位。

### Default 方法（简易模式只需实现 getArmorLevel）

```java
// 返回此护甲物品在指定槽位提供的护甲等级。ctx 可为 null
default ArmorLevel getArmorLevel(EquipmentSlot slot, @Nullable BFDamageContext ctx)

// 返回此护甲物品在指定槽位的 RHA 等效厚度（mm）。默认取 getArmorLevel 中位值
default float getRHA(EquipmentSlot slot, @Nullable BFDamageContext ctx)

// 将原版伤害转换为协议上下文。默认始终返回有效上下文（穿深 = amount / 2）。
// 返回 null 则此伤害类型不被护甲拦截，走原版流程
@Nullable
default BFDamageContext createContextFromVanilla(DamageSource source, float amount)
```

### Default 方法（精密模式逐槽位覆写）

```java
// 修正穿深（此槽位护甲的减效：爆反拦截、间隙衰减等）。默认直接返回 ctx.penetration()
default float modifyPenetration(EquipmentSlot slot, BFDamageContext ctx)

// 判断此槽位护甲是否被击穿（纯击穿判定，不含跳弹）。默认基于离散等级比较
default boolean isArmorPenetrated(EquipmentSlot slot, BFDamageContext ctx)

// 解析此槽位护甲的最终穿甲结果（含跳弹判定）。默认委托 isArmorPenetrated
default PenetrationResult resolvePenetration(EquipmentSlot slot, BFDamageContext ctx)

// 根据穿甲结果计算此槽位护甲的最终伤害量。默认三级模型：越级100%/同级65%/未击穿0
default float calculateFinalDamage(EquipmentSlot slot, BFDamageContext ctx,
                                    PenetrationResult result)

// 护甲层职责完成后的回调。此时穿甲判定和伤害削减已结束，实体尚未实际受伤害。
// 用于耐久损耗、爆反消耗、碎裂降级等后效
default void afterHurt(LivingEntity wearer, EquipmentSlot slot, BFDamageContext ctx,
                        PenetrationResult result, float finalDamage)
```

### Default 方法（槽位映射，可选覆写）

```java
// 根据命中上下文确定此护甲物品对应的装备槽位。
// 默认按命中点高度占比划分：头部 >85%，躯干 55%~85%，腿部 35%~55%，脚部 <35%。
// 命中点无效时返回 null。
// @param wearer 穿戴此护甲的实体
// @param ctx    命中上下文
// @return 对应装备槽位；命中点无效或此物品不应处理时返回 null
@Nullable
default EquipmentSlot mapHitToSlot(LivingEntity wearer, BFDamageContext ctx)
```

---

## BFHitResolver

**`public interface BFHitResolver`** — 命中前目标解析接口。由代理对象实现（实体、物理体属主或其他包装体），在 `BFDamageApi.hurt()` 调用之前执行，将 AABB 命中重定向到真正的物理伤害目标。

### Abstract 方法

```java
/**
 * 解析命中的实际伤害目标（主方法）。
 * 必须是幂等且无副作用的纯查询——投射物命中通常只解析一次
 * （Projectile.onHit 阶段的结果经缓存传给 Entity.hurt 阶段）；
 * 仅当伤害来源的 direct entity 不是该投射物时，hurt 阶段才按来源重新解析一次。
 * 同一组 (hitPoint, delta) 必须始终返回同一结果。
 * @param hitPoint 原版报告的命中点（世界坐标）
 * @param delta    搜索矢量，其模为搜索距离上限（m），方向为命中方向
 * @return 解析结果；null 表示实际未命中
 */
@Nullable
BFHitResolveResult resolveHit(Vec3 hitPoint, Vec3 delta)
```

### Default 方法

```java
/**
 * 便利重载：从原版 HitResult 提取命中点后委托给二参数方法。
 * @param hitResult 原版命中结果（取其 getLocation() 作为命中点）
 * @param delta     搜索矢量
 * @return 解析结果；null 表示未命中
 */
@Nullable
default BFHitResolveResult resolveHit(HitResult hitResult, Vec3 delta)
```

### Static 方法

```java
/**
 * 由攻击者的速度矢量导出解析搜索矢量。
 * 速率钳制在 [0.5, 4.0] 后取两倍位移，即搜索距离上限落在 [1.0, 8.0] m。
 * 速率低于 0.001 或为非有限值时返回 Vec3.ZERO，调用方应对零矢量早退。
 * @param velocity 攻击者速度矢量
 * @return 搜索矢量
 */
static Vec3 searchDelta(Vec3 velocity)

/**
 * 由协议外伤害来源导出解析搜索几何，按伤害类别分派几何来源
 * （爆炸 / 投射物 / 活体近战 / 其他有源位置 / 无源位置）。
 * 爆炸分支优先：其 direct entity 类型不固定（苦力怕、TNT、火球）。
 * @param self   被命中的代理对象
 * @param source 原版伤害来源
 * @return 长度为 2 的数组 [hitPoint, delta]；无法构造几何时返回 null
 */
@Nullable
static Vec3[] searchRay(Entity self, DamageSource source)
```

---

## BFHitResolveCache（internal）

**`public interface BFHitResolveCache`**（位于 `io.github.sweetzonzi.ballistics_framework.internal`）— 投射物命中结果缓存。**内部接口，不属于公开 API**，外部模组不应引用。

它存在的原因：`Projectile#onHit` 阶段持有精确几何并已完成一次解析，而 `Entity#hurt` 阶段未必能还原那份几何，重新解析可能得到相反结论。因此 `ProjectileHitResolverMixin` 在判定为真命中后把结果写入投射物自身的字段，拦截器在 `hurt` 阶段直接取用。复用范围限于单次命中事件——读取即清空，且仅在命中实体身份匹配时返回。

接口声明为 `public` 只表达"能被 mixin 包看到"：唯一实现方 `ProjectileHitResolverMixin` 位于 mixin 包，其覆写方法必须是 public，而接口方法不能比接口本身更可见。

```java
// 一次命中的判定结果：被命中的实体 + 该次命中的解析结果
record CachedResolve(Entity hitEntity, BFHitResolveResult result)

/**
 * 写入本次命中的解析结果。应在 resolveHit 判定为真命中之后、放行原版流程之前调用。
 * @param hitEntity 原版报告的命中实体，即随后 hurt 的接收者
 * @param result    本次命中的解析结果
 */
void bf$cacheResolve(Entity hitEntity, BFHitResolveResult result)

/**
 * 取出并清空缓存。仅当缓存的命中实体与 hitEntity 身份相等时返回该记录，其余返回 null。
 * @param hitEntity 当前正在承受伤害的实体
 * @return 本次命中的解析结果；无记录或命中实体不匹配时返回 null
 */
@Nullable
CachedResolve bf$takeResolve(Entity hitEntity)
```

---

## BFHitResolveResult

**`public record BFHitResolveResult(BFHurtTarget actualTarget, Vec3 correctedHitPoint, Vec3 correctedHitNormal, BFDamageExtensions extensions)`** — 命中解析结果 record。携带修正后的目标引用、命中几何和扩展数据，可直接用于构造 `BFDamageContext`。

### 组件

| 组件 | 类型 | 说明 |
|------|------|------|
| `actualTarget` | `BFHurtTarget` | 真正的协议伤害目标 |
| `correctedHitPoint` | `Vec3` | 修正后的命中点世界坐标 |
| `correctedHitNormal` | `Vec3` | 修正后的命中面法线 |
| `extensions` | `BFDamageExtensions` | 解析器提供的扩展数据 |

### 便利构造器

```java
// 无扩展数据的便利构造器
BFHitResolveResult(BFHurtTarget actualTarget, Vec3 correctedHitPoint, Vec3 correctedHitNormal)
// 等价于 new BFHitResolveResult(actualTarget, correctedHitPoint, correctedHitNormal, new BFDamageExtensions())
```

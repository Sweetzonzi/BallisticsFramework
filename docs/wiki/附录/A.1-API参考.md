# A.1 API 参考

本页以类为单位，按字母顺序列出协议层全部公开 API 的完整签名和说明。所有类均位于 `io.github.sweetzonzi.ballistics_framework.api` 包。

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
 * 发起一次协议伤害。
 * @param target 伤害目标（BFHurtTarget 或普通 Entity）
 * @param ctx    完整命中上下文
 * @return       实际造成的伤害量。
 *               目标为 BFHurtTarget 时走完整穿甲管线；
 *               目标为普通 Entity 时走原版 entity.hurt()；
 *               目标非上述两种类型时返回 0
 */
static float hurt(Object target, BFDamageContext ctx)

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
static final BFDamageExtensionKey<Float> CALIBER     // 弹体口径（米），默认 0.1
static final BFDamageExtensionKey<Float> MASS        // 弹体质量（千克），默认 10
```

### 实例方法

```java
// 读取扩展值。未设置时返回 key 注册时的默认值（永不返回 null）
<T> T get(BFDamageExtensionKey<T> key)

// 写入扩展值。key 和 value 均不能为 null
<T> void set(BFDamageExtensionKey<T> key, T value)
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

// ③的辅助方法。默认基于等级比较（>=，等于算击穿）
default boolean isArmorPenetrated(BFDamageContext ctx)
```

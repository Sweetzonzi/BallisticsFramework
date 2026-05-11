# 项目术语表

> 生成时间：2026-05-11
> 项目：BallisticsFramework（弹道框架 — 内弹道、外弹道、终点弹道伤害协议层）

## 概念列表

### 命中上下文（BFDamageContext）

- **职责**：携带一次命中行为的完整高维上下文数据，在管线调用链内隐式传递
- **描述**：一个 Java 16 record，包含 8 个组件：`source`（原版 DamageSource）、`baseDamage`（标称伤害量）、`hitVelocity`（命中速度矢量）、`hitPoint`（命中点坐标）、`hitNormal`（命中面法线）、`penetration`（理论穿深 mm RHA）、`extensions`（类型安全扩展容器）、`handler`（伤害发起方回调接口，可为 null）。构造后不可变，整个穿甲判定过程中仅读取不写入。通过 `BFDamageContext.builder()` 经 Builder 模式构造，提供 `withHandler(handler)` 生成替换 handler 的新实例和 `getPenetrationLevel()` 便捷映射到 `ArmorLevel`。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.api.BFDamageContext` — 上下文 record
  - `io.github.sweetzonzi.ballistics_framework.api.BFDamageContextBuilder` — 构建器，构造器包可见。除 source 为必须字段外，其余均有安全默认值

---

### 穿甲等级体系（ArmorLevel）

- **职责**：将连续 RHA 值（mm）映射为 13 级离散穿甲/护甲等级，提供简易模式下的等级判定
- **描述**：13 级枚举（12 级实体等级 + 1 级元等级 `UNPENETRABLE`），等级值约 ×2 非线性增长（0→1→3→5→10→20→40→80→150→300→600→1200→2000mm），覆盖从血肉到现代主战坦克的完整范围。核心方法：`fromRha(float)` 向上映射（跳过 UNPENETRABLE）、`canDefeat(ArmorLevel)` 用 ordinal `>=` 比较、`medianRha()` 中位值、`getArmorDisplayName()`/`getPenetrationDisplayName()` 中英本地化显示名。`UNPENETRABLE` 只能显式引用获取，对一切等级返回不可击穿。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.api.ArmorLevel` — 枚举定义 + 映射与判定工具

---

### 穿甲判定结果（PenetrationResult）

- **职责**：表达一次穿甲判定的主结果，三者互斥
- **描述**：`PENETRATED`（击穿）、`BLOCKED`（未击穿）、`RICOCHET`（跳弹）。由 `BFHurtTarget.resolvePenetration()` 返回，作为 `calculateFinalDamage` 的入参和 `BFDamageHandler` 回调触发的唯一依据。超匹配(碾压)与破片不在枚举中表达，由 `BFDamageHandler.isOvermatch()`/`isSpall()` 在回调阶段动态判定。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.api.PenetrationResult` — 三元枚举

---

### 协议伤害目标（BFHurtTarget）

- **职责**：声明一个实体参与协议穿甲判定的核心接口
- **描述**：协议层的核心抽象。任何希望参与协议判定的实体都必须实现此接口。包含 3 个抽象方法（`getArmorLevel`、`hurt`、`createContextFromVanilla`）和 6 个 default 方法（`getRHA`、`modifyPenetration`、`isArmorPenetrated`、`resolvePenetration`、`calculateFinalDamage`、`getEntity`）。简易模式只需实现 `getArmorLevel`，其余方法基于离散等级提供完整默认行为。`getBFEntity()` 默认返回 `this`（若自身是 Entity 子类），适配器应覆写为返回被包裹的实体。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget` — 协议目标接口

---

### 护甲物品接口（TBArmorMaterial）

- **职责**：护甲物品接入协议的标准接口，使穿戴者自动获得穿甲判定能力
- **描述**：护甲模组让物品实现此接口后，穿戴该物品的实体——无论是否实现 `BFHurtTarget`——在受到协议伤害或原版伤害时，协议层都会通过 `TBArmorAdapter` 自动将其纳入穿甲判定管线。简易模式只需实现 `getArmorLevel(EquipmentSlot, BFDamageContext)`，其余管线方法均有逐槽位的默认实现（与 `BFHurtTarget` 的默认行为对称）。精密模式可覆写 `modifyPenetration`（爆反拦截、间隙衰减）、`resolvePenetration`（跳弹判定）、`calculateFinalDamage`（自定义伤害计算）。提供 `mapHitToSlot` 按命中点高度占比（HEAD > 85% / CHEST > 55% / LEGS > 35% / FEET）映射到装备槽位。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.api.BFArmorMaterial` — 护甲物品接口

---

### 护甲适配器（TBArmorAdapter）

- **职责**：内部适配器，将穿戴了 TBArmorMaterial 护甲的 LivingEntity 包裹为 BFHurtTarget
- **描述**：内部实现类（非公开 API），外部模组不可直接引用。在管线首次调用时惰性解析命中槽位（先按 `mapHitToSlot` 精确匹配，无命中点时退回到取最高护甲等级的保守策略），整个管线周期内缓存解析结果。逐方法委托到对应槽位护甲的 `TBArmorMaterial` 方法，使护甲模组拥有与 `BFHurtTarget` 实现者同等的定制权限。`hurt()` 委托 `entity.hurt()` 走原版管线，因此原版 ARMOR/ARMOR_TOUGHNESS/保护附魔会进行二次减免（两层防护模型）。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.internal.BFArmorAdapter` — 适配器实现
  - `io.github.sweetzonzi.ballistics_framework.api.BFArmorMaterial` — 被委托的护甲接口

---

### 伤害发起方回调（BFDamageHandler）

- **职责**：武器/弹头模组接收穿甲判定事件回调的接口
- **描述**：武器模组在构造 `BFDamageContext` 时通过 `builder.handler(myHandler)` 或 `ctx.withHandler(this)` 注入。提供三个主结果回调（`onPenetrated`、`onBlocked`、`onRicochet`）和两个条件回调（`onOvermatch` 超匹配碾压、`onSpall` 破片）。`isOvermatch` 默认规则为击穿 + 穿深 > 装甲厚度 × 1.5；`isSpall` 默认规则为未击穿或击穿但非超匹配。提供便捷方法 `dealDamage(target, ctx)` 自动将自身注入上下文后调用 `BFDamageApi.hurt()`。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.api.BFDamageHandler` — 回调接口

---

### 穿甲判定管线（Armor Penetration Pipeline）

- **职责**：定义一次协议伤害从接手到执行完毕的标准调用顺序
- **描述**：`BFDamageApi.hurt()` 是管线的唯一执行入口，内部按固定顺序执行：`resolvePenetration(ctx)`（含默认委托链 `isArmorPenetrated` → `modifyPenetration` 获取修正穿深 → `ArmorLevel.fromRha` 映射等级 → `canDefeat(getArmorLevel)` 判定）→ `calculateFinalDamage(ctx, result)` → `hurt(source, amount)`。各步骤通过接口 default 方法的委托链串联，实现者可单独覆写任意步骤。管线前后自动完成 ThreadLocal 栈的 push/pop 和回调触发。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.api.BFDamageApi` — 管线执行者，控制调用顺序和 try/finally 栈管理
  - `io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget` — 管线参与者，各步骤的定义接口

---

### 类型安全扩展机制（BFDamageExtensions / BFDamageExtensionKey）

- **职责**：允许武器模组和护甲模组在核心字段之外携带和交换任意结构化数据
- **描述**：扩展容器使用类型安全的泛型 key-value 存储。key 通过 `BFDamageExtensions.register()` 注册为全局单例，绑定 `ResourceLocation`、`Class<T>` 和默认值工厂。读操作总返回非 null（未设置时退回注册的默认值）。容器支持浅拷贝（`copy()`/拷贝构造器）——预构造基础扩展数据后在每次命中时复制并追加命中特定字段。协议预定义了 3 个标准 key：`FUSE_DELAY`（引信延迟 Float，默认 0）、`CALIBER`（弹体口径 Float，默认 0.1m）、`MASS`（弹体质量 Float，默认 10kg）。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.api.BFDamageExtensions` — 扩展容器 + 预定义 key 常量 + `register()` 方法
  - `io.github.sweetzonzi.ballistics_framework.api.BFDamageExtensionKey` — 泛型 key 类，包可见构造器
  - `io.github.sweetzonzi.ballistics_framework.internal.BFExtensionKeyRegistry` — 注册表，检查 ID 重名

---

### ThreadLocal 上下文栈（TBContextStack）

- **职责**：在调用链内隐式传播命中上下文，并提供重入守卫
- **描述**：由于原版 `hurt` 方法签名无法传递额外参数，协议层使用 `ThreadLocal<Deque<Entry>>` 栈在调用链内传播 `(target, context)` 对。栈元素绑定目标实体（`==` 引用比较），精确区分"同一目标的协议管线内重入"（放行原版）与"副作用触发的新目标伤害如荆棘反伤"（进入协议拦截）。push/pop 由 `BFDamageApi.hurt()` 的 try/finally 保证成对出现。空栈 pop 时记录错误日志以便调试调用链不匹配的 bug。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.internal.BFContextStack` — 栈管理单例
  - `io.github.sweetzonzi.ballistics_framework.api.BFDamageApi` — 负责 push/pop 的调用者

---

### Mixin 注入拦截（BFHurtInterceptor / EntityHurtMixin / LivingEntityHurtMixin）

- **职责**：通过 Mixin 注入拦截协议外伤害，将非协议来源的伤害引入协议管线
- **描述**：必须同时注入 `Entity#hurt` 和 `LivingEntity#hurt` 的 HEAD 阶段（cancellable=true），避免遗漏任意一方的实现者。拦截逻辑为四态判断：① 已在协议管线内（`hasContextFor`）→ 放行原版不拦截；② 实体自身是 `BFHurtTarget` → 通过 `createContextFromVanilla` 构造上下文后走完整管线；③ 实体是 `LivingEntity` 且穿戴了 `TBArmorMaterial` 护甲 → 通过 `TBArmorAdapter` 接管并走完整管线；④ 其他普通实体 → 不做干预，放行原版流程。共享逻辑抽离到 `BFHurtInterceptor` 避免在 `@Mixin` 类中声明静态方法。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.internal.BFHurtInterceptor` — 共享拦截逻辑
  - `io.github.sweetzonzi.ballistics_framework.mixin.EntityHurtMixin` — `@Mixin(Entity.class)`
  - `io.github.sweetzonzi.ballistics_framework.mixin.LivingEntityHurtMixin` — `@Mixin(LivingEntity.class)`
  - `io.github.sweetzonzi.ballistics_framework.api.BFDamageApi` — `hasContextFor()` 重入守卫

---

### 双层防护模型（Two-Layer Protection Model）

- **职责**：协议穿甲判定与原版护甲减免的分层协作机制
- **描述**：协议层处理穿甲判定（第 1 层）——决定是否击穿、计算穿透后的伤害量；原版护甲系统（第 2 层）——ARMOR/ARMOR_TOUGHNESS/保护附魔对协议计算出的伤害量进行二次减免。这意味着 `BFDamageApi.hurt()` 和 `BFHurtTarget.calculateFinalDamage()` 的返回值 ≥ 实体实际减少的 HP。例如：协议穿甲成功计算最终伤害为 20 HP，但目标身穿全套钻石甲（约 80% 减伤），原版管线进一步减免后实际仅扣 4 HP。这是设计意图——护甲模组可以将全部精力放在穿甲判定逻辑上，无需重复实现伤害数值减免。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.internal.BFArmorAdapter` — `hurt()` 委托 `entity.hurt()` 触发第二层
  - `io.github.sweetzonzi.ballistics_framework.api.BFDamageApi` — 返回值说明中明确标注此行为

---

### RHA 等效厚度（RHA Equivalent Thickness）

- **职责**：衡量装甲防护能力的标准化单位，协议层的统一参考基准
- **描述**：Rolled Homogeneous Armor（轧制均质装甲）的等效厚度，单位为 mm。武器模组在 `BFDamageContext.penetration` 和扩展字段中填入理论穿深，护甲模组在 `BFHurtTarget.getRHA()`/`TBArmorMaterial.getRHA()` 中返回命中部位的等效厚度。默认击穿判定使用离散等级比较：先调用 `modifyPenetration` 获取修正后的有效穿深，映射为 `ArmorLevel`，再与护甲等级通过 `canDefeat`（`>=` 比较）判定。精密模组可覆写为直接比较 `modifyPenetration > getRHA` 的纯数值模型。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.api.BFDamageContext` — `penetration` 字段
  - `io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget` — `getRHA()`、`modifyPenetration()` 方法
  - `io.github.sweetzonzi.ballistics_framework.api.ArmorLevel` — `fromRha()` 映射 + `canDefeat()` 判定

---

### 斜穿修正与穿深减效（Angle Correction & Penetration Modifiers）

- **职责**：在护甲侧对武器穿深进行修正——包括角度效应、爆反拦截、间隙衰减等
- **描述**：角度效应由弹头/武器模组在构造 `BFDamageContext` 前自行计算（v2 设计），填入的 `penetration` 已是考虑过入射角的最终值。护甲侧的 `modifyPenetration` 默认直接返回 `ctx.penetration()` 不做减效。高级护甲模组可覆写此方法实现爆反拦截（ERA——仅对化学能弹头减去固定等效厚度）、间隙衰减（穿深随间隙距离递减）、附加装甲块的额外防护等逻辑。修正后的值将替代原始穿深进入 `isArmorPenetrated` 判定。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget` — `modifyPenetration()` 默认实现（恒等返回）
  - `io.github.sweetzonzi.ballistics_framework.api.BFArmorMaterial` — 槽位维度的 `modifyPenetration()`
  - `io.github.sweetzonzi.ballistics_framework.api.BFDamageContext` — `hitVelocity`、`hitNormal` 字段供武器侧计算入射角

---

### 协议外伤害兼容（Vanilla Damage Compatibility）

- **职责**：使非协议来源的伤害（原版生物攻击、TNT 爆炸等）在命中协议感知目标时也能走部分管线
- **描述**：原版伤害在 Mixin 拦截后，通过 `BFHurtTarget.createContextFromVanilla()` 或 `TBArmorAdapter.createContextFromVanilla()` 转换为低信息量的 `BFDamageContext`（仅 source + baseDamage 有值，弹道字段为零值，穿深由 `estimatePenetration` 按伤害量/2 估算），然后走完整穿甲判定管线。返回 null 则退回原版流程（如虚空、指令伤害）。`BFHurtInterceptor` 的 `hasContextFor` 重入守卫确保已在管线内时不重复拦截。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget` — `createContextFromVanilla()` 抽象方法
  - `io.github.sweetzonzi.ballistics_framework.internal.BFHurtInterceptor` — 拦截后调用转换
  - `io.github.sweetzonzi.ballistics_framework.api.BFDamageApi` — `hasContextFor()` 重入守卫

---

### 协议层对外入口（BFDamageApi）

- **职责**：协议层的唯一静态入口，封装 ThreadLocal 栈管理、管线执行和回调触发
- **描述**：三个静态方法：`hurt(Object, BFDamageContext)` 发起协议伤害，内部完成三路分支调度（BFHurtTarget → 直接管线 / LivingEntity+TBArmorMaterial → 适配器管线 / 普通 Entity → 原版回退）和 try/finally 栈管理；`hasContextFor(Object)` 供 mixin 判断重入；`getContextFor(Object)` 供管线方法内部取回当前上下文以读取命中信息播放特效等。`hurt` 的返回值是协议计算伤害量，由于原版护甲二次减免，此值 ≥ 实体实际减少的 HP。
- **关键类**：
  - `io.github.sweetzonzi.ballistics_framework.api.BFDamageApi` — 三个静态方法
  - `io.github.sweetzonzi.ballistics_framework.internal.BFContextStack` — 被委托的栈操作
  - `io.github.sweetzonzi.ballistics_framework.internal.BFArmorAdapter` — 分支 2 的适配器创建

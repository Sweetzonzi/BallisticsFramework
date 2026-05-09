# 项目术语表

> 生成时间：2026-05-10
> 项目：TerminalBallistics（终点弹道伤害协议层）

## 概念列表

### 穿甲判定管线（Armor Penetration Pipeline）

- **职责**：定义一次协议伤害从接手到执行完毕的标准调用顺序
- **描述**：协议层在 `TBDamageApi.hurt()` 内按固定顺序调用 `TBHurtTarget` 的 5 个方法：`getRHA` → `modifyPenetration` → `isArmorPenetrated` → `calculateFinalDamage` → `hurt`。各步骤通过接口 default 方法的委托链串联，实现者可单独覆写任意步骤以自定义行为。
- **关键类**：
  - `io.github.sweetzonzi.terminal_ballistics.api.TBDamageApi` — 管线执行者，控制调用顺序
  - `io.github.sweetzonzi.terminal_ballistics.api.TBHurtTarget` — 管线参与者，各步骤的定义接口

---

### 命中上下文（TBDamageContext）

- **职责**：携带一次命中行为的完整高维上下文数据
- **描述**：一个 Java 16 record，包含 6 个必备字段（DamageSource、baseDamage、hitVelocity、hitPoint、hitNormal、penetration）和 1 个扩展容器（TBDamageExtensions）。构造后不可变，整个穿甲判定过程中仅读取不写入。通过 `TBDamageContext.builder()` 经 Builder 模式构造。
- **关键类**：
  - `io.github.sweetzonzi.terminal_ballistics.api.TBDamageContext` — 上下文 record
  - `io.github.sweetzonzi.terminal_ballistics.api.TBDamageContextBuilder` — 构建器，构造器包可见

---

### 协议伤害目标（TBHurtTarget）

- **职责**：声明一个实体参与协议穿甲判定的接口
- **描述**：协议层的核心抽象。任何希望参与协议判定的 Entity 都必须实现此接口。包含 4 个 default 方法（getRHA、modifyPenetration、isArmorPenetrated、calculateFinalDamage）和 2 个 abstract 方法（hurt、createContextFromVanilla）。LivingEntity 子类可将 hurt 委托给 `super.hurt()` 走原版管线。
- **关键类**：
  - `io.github.sweetzonzi.terminal_ballistics.api.TBHurtTarget` — 协议目标接口

---

### 类型安全扩展机制（TBDamageExtensions / TBDamageExtensionKey）

- **职责**：允许武器模组和护甲模组在核心字段之外携带和交换任意结构化数据
- **描述**：扩展容器使用类型安全的泛型 key-value 存储。key 通过 `TBDamageExtensions.register()` 注册为全局单例，绑定 ResourceLocation、类型和默认值工厂。读操作总返回非 null（未设置时退回注册的默认值）。协议预定义了 4 个标准 key：RICOCHET、SPALL、OVERMATCH、FUSE_DELAY。
- **关键类**：
  - `io.github.sweetzonzi.terminal_ballistics.api.TBDamageExtensions` — 扩展容器 + 预定义 key 常量
  - `io.github.sweetzonzi.terminal_ballistics.api.TBDamageExtensionKey` — 泛型 key 类
  - `io.github.sweetzonzi.terminal_ballistics.internal.TBExtensionKeyRegistry` — 注册表，检查 ID 重名

---

### ThreadLocal 上下文栈（TBContextStack）

- **职责**：在调用链内隐式传播命中上下文，并提供重入守卫
- **描述**：由于原版 `hurt` 方法签名无法传递额外参数，协议层使用 `ThreadLocal<Deque<Entry>>` 栈在调用链内传播 `(target, context)` 对。栈元素绑定目标实体，精确区分"同一目标的协议管线内重入"（放行原版）与"副作用触发的新目标伤害如荆棘反伤"（进入协议拦截）。push/pop 由 `TBDamageApi.hurt()` 的 try/finally 保证。
- **关键类**：
  - `io.github.sweetzonzi.terminal_ballistics.internal.TBContextStack` — 栈管理单例

---

### Mixin 注入拦截（TBHurtInterceptor / EntityHurtMixin / LivingEntityHurtMixin）

- **职责**：拦截协议外伤害命中 TBHurtTarget 的场景，将其纳入协议管线
- **描述**：必须同时注入 `Entity#hurt` 和 `LivingEntity#hurt` 的 HEAD 阶段（cancellable=true），避免遗漏任意一方的 TBHurtTarget 实现者。拦截逻辑为三态判断：已在协议管线内则放行；非 TBHurtTarget 则放行；否则通过 `createContextFromVanilla` 构造低信息量上下文后走完整协议管线。共享逻辑抽离到 `TBHurtInterceptor` 避免在 `@Mixin` 类中声明静态方法。
- **关键类**：
  - `io.github.sweetzonzi.terminal_ballistics.internal.TBHurtInterceptor` — 共享拦截逻辑
  - `io.github.sweetzonzi.terminal_ballistics.mixin.EntityHurtMixin` — `@Mixin(Entity.class)`
  - `io.github.sweetzonzi.terminal_ballistics.mixin.LivingEntityHurtMixin` — `@Mixin(LivingEntity.class)`

---

### RHA 等效厚度（RHA Equivalent Thickness）

- **职责**：衡量装甲防护能力的标准化单位
- **描述**：Rolled Homogeneous Armor（轧制均质装甲）的等效厚度，单位为 mm。武器模组在 `TBDamageContext.penetration` 中填入理论穿深，护甲模组在 `TBHurtTarget.getRHA()` 中返回命中部位的等效厚度。双方均以 RHA 为共同参考标准。默认击穿条件为 `modifyPenetration > getRHA`。
- **关键类**：
  - `io.github.sweetzonzi.terminal_ballistics.api.TBDamageContext` — `penetration` 字段
  - `io.github.sweetzonzi.terminal_ballistics.api.TBHurtTarget` — `getRHA()` 方法

---

### 斜穿修正（Angle Correction / modifyPenetration）

- **职责**：根据入射角修正有效穿深
- **描述**：默认实现为 `penetration / cos(θ)`，其中 θ 为弹体速度矢量与命中面法线反方向的夹角。射入角越倾斜（cosθ 越小），有效穿深越大。`modifyPenetration` 是可覆写的 default 方法，高级护甲模组可实现间隙衰减、爆反拦截等更复杂的修正逻辑。
- **关键类**：
  - `io.github.sweetzonzi.terminal_ballistics.api.TBHurtTarget` — `modifyPenetration()` 默认实现
  - `io.github.sweetzonzi.terminal_ballistics.api.TBDamageContext` — `hitVelocity`、`hitNormal` 字段

---

### 协议外伤害兼容（Vanilla Damage Compatibility）

- **职责**：使非协议来源的伤害在命中 TBHurtTarget 时也能走部分协议管线
- **描述**：原版生物攻击、TNT 爆炸等非协议来源的伤害，在 Mixin 拦截后通过 `createContextFromVanilla()` 转换为低信息量的 `TBDamageContext`（仅 source + baseDamage 有值，弹道字段为零值），然后走完整穿甲判定管线。返回 null 则退回原版流程（如虚空、指令伤害）。
- **关键类**：
  - `io.github.sweetzonzi.terminal_ballistics.api.TBHurtTarget` — `createContextFromVanilla()` 方法
  - `io.github.sweetzonzi.terminal_ballistics.internal.TBHurtInterceptor` — 拦截后调用转换
  - `io.github.sweetzonzi.terminal_ballistics.api.TBDamageApi` — `hasContextFor()` 重入守卫

---

### 侧信道扩展（Side-Channel Extensions）

- **职责**：在武器模组与护甲模组之间传递非伤害数值的命中事件信息
- **描述**：协议的预定义扩展 key 专为此设计。护甲模组的 `getRHA` 覆写可以通过 `ctx.getExtensions().set(RICOCHET, true)` 标记跳弹，武器模组在伤害返回后读取同一扩展触发跳弹音效/粒子。协议核心不读取也不依赖这些扩展，它们是纯侧信道通信工具。
- **关键类**：
  - `io.github.sweetzonzi.terminal_ballistics.api.TBDamageExtensions` — RICOCHET、SPALL、OVERMATCH、FUSE_DELAY 常量
  - `io.github.sweetzonzi.terminal_ballistics.api.TBDamageExtensionKey` — 类型安全的 key

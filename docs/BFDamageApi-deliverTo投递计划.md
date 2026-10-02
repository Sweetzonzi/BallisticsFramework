# `BFDamageApi.deliverTo` —— 承载者投递计划

> 关联文档：
>
> - [BFHitResolver-去实体化与命中转发计划.md](./BFHitResolver-去实体化与命中转发计划.md) —— 拦截器情况 3 的落地记录，本文沿用其情况编号
> - [BFHitResolver-实现计划.md](./BFHitResolver-实现计划.md) —— `BFHitResolver` / `BFHitResolveResult` / `resolveHitTarget` 的接口定义记录
> - wiki：[3.5-协议外伤害兼容](./wiki/3-护甲侧开发/3.5-协议外伤害兼容.md)、[3.7-代理实体：实现 BFHitResolver](./wiki/3-护甲侧开发/3.7-代理实体：实现BFHitResolver.md)、[4.1-穿甲判定管线](./wiki/4-协议内幕/4.1-穿甲判定管线.md)、[4.3-ThreadLocal与Mixin](./wiki/4-协议内幕/4.3-ThreadLocal与Mixin.md)
>
> **本文自包含**：不要求读者先读上述任何一份文档。引用本仓库源码时按 `路径#符号` 给出坐标，符号取源码里可检索的声明名；引用下游仓库时按「仓库名 + `路径#符号`」给出坐标。正文在被引用处就地说明该符号的职责。本文要新增、尚未存在于源码中的符号，一律按本文 §号引用，不写 `路径#符号`——符号落地后引用才成立。
>
> **本文同时是实施依据与验收依据**：§五～§七 给出的签名、编排与调用契约即为落地形态；§十 的用例为验收清单。

## 一、概述

新增一个协议入口：**承载者投递**。

```java
public static boolean deliverTo(Entity carrier, BFDamageContext ctx)
public static boolean deliverTo(Entity carrier, BFDamageContext ctx, boolean ignoreBFArmor)
```

它解决的是这一类情形：**伤害先落在某个对象上（部件、护甲、代理体），经过该对象的结算后，最终必须由它所属的另一个实体真正承受。** 该实体称为**承载者**（carrier）。

### 1.1 投递与发起的关系

`BFDamageApi.hurt`（`api/BFDamageApi.java#hurt`）是**发起**入口：它按目标的身份选取管线——目标实现 `BFHurtTarget`、目标穿有 `BFArmorMaterial` 护甲、或两者皆是，分别对应不同的穿甲判定流程，最后把伤害交给目标。

`deliverTo` 是**投递**入口：它不做穿甲判定，也不修正数值。传入的 `BFDamageContext` 必须已经携带穿透后的修正值（伤害量与残余穿深），构造它是调用者的义务，见 §5.7。

三个入口的分工：

| 入口 | 回答的问题 | 与上下文栈的关系 |
| --- | --- | --- |
| `BFDamageApi.hurt`（`api/BFDamageApi.java#hurt`） | "这次命中打到了谁、这个目标承受多少" | 压栈，跑完整分支管线 |
| `BFDamageApi.resolveHitTarget`（`api/BFDamageApi.java#resolveHitTarget`） | "这一下实际打到了哪个对象" | 不压栈，管线外调用 |
| `deliverTo` | "这些承受最终扣谁的血" | 压栈，只跑护甲层后落地 |

### 1.2 绕行范围：投递跳过了管线的哪些环节

**一句话定义：`deliverTo` 是一次不再判定的协议调用。** 它保留协议上下文与协议回调，跳过的是"判定"这一类环节——包括目标身份判定、路由判定，以及（可选的）护甲层判定。传入的 `ctx` 必须已经是第一趟的结算结果。

把"BF 管线"拆成环节后，"绕开"的精确含义如下（`③`～`⑨` 对应 §5.3 的实现骨架）：

| 环节 | `hurt` | `deliverTo(…, false)` | `deliverTo(…, true)` |
| --- | --- | --- | --- |
| ① 用 `resolveHit` 解析实际目标 | 由调用方在管线外做 | **不做** | **不做** |
| ② 入栈（`BFContextStack.push`） | 做 | 做 | 做 |
| ③ 判定目标身份、选取分支（复合 / `BFHurtTarget` / 适配器 / 普通实体） | 做 | **不做**——只走护甲层 | **不做**——一层都不走 |
| ④ 承载者作为 `BFHurtTarget` 的本体层：`resolvePenetration` / `calculateFinalDamage` | 做 | **不做** | **不做** |
| ⑤ 承载者穿戴的 `BFArmorMaterial` 护甲层三件套 | 做 | 做 | **不做** |
| ⑥ 护甲层的穿甲回调（`before*` / `on*`）与 `armorAfterHurt` | 做 | 做 | **不做** |
| ⑦ 本体层回调（`before*` / `on*`）与 `afterHurt` | 做 | **不做** | **不做** |
| ⑧ 通用落地回调（`beforeNormalEntityHit` / `onNormalEntityHit`） | 分支3 做 | **不做** | **不做** |
| ⑨ 原版 `carrier.hurt(ctx.source(), ctx.baseDamage())` | 做 | 做 | 做 |
| ⑩ 拦截器 `BFHurtInterceptor.intercept` 的判定 | 做（在 `hurt` 内部递归进入时按情况 1 放行） | 做，但压栈使其命中情况 1 后立即放行 | 同左 |

`ignoreBFArmor` 的语义因此是**在"已经绕开判定"的基础上，再额外跳过护甲这一层判定**（`⑤` 与 `⑥`）。两个重载的差别只有这一处，其余绕行是共同的。

三条容易读错的推论，就地写清：

- **`ignoreBFArmor = true` 时，整条投递路径上不存在任何 BF 环节**，只剩"入栈 + 原版落地"。这是"这次命中已经判定过、不要再让它进入协议"的最强形态。
- **`ignoreBFArmor = false` 时，投递仍然绕开 `BFHitResolver` 的路由**。承载者即使是解析器也不会被重新解析——这正是"投递不会被路由回触发它的那个部件"的原因（§2.3）。
- **投递不等于"没有经过协议"。** `ctx` 携带协议上下文，护甲物品的回调能读到它（§5.5）。"绕开"针对的是**判定**，不是**上下文与回调**。

还有一条边界必须说清：**投递并不绕开拦截器。** 承载者的 `hurt` 仍然会进入 `mixin/EntityHurtMixin.java` 与 `mixin/LivingEntityHurtMixin.java` 的 HEAD 注入点，只是压栈使 `internal/BFHurtInterceptor.java#intercept` 的情况 1 直接命中并放行，因此不会到达情况 2 / 3 / 4——"不会重新进入判定"是压栈换来的，不是跳过注入点换来的。这条区别在压栈失败时有实际后果：那时拦截器会按普通路径重新分发（§5.8）。

### 1.3 改动范围

改动集中在三处：`api/BFDamageApi.java` 新增两个 `deliverTo` 重载与共享的压栈目标选取，`internal/BFArmorAdapter.java` 新增供投递使用的护甲层入口，`api/BFDamageHandler.java` 的类 Javadoc 补投递期回调的边界。不触碰 `BFHurtTarget`、`BFHitResolver`、`BFDamageContext`、`BFDamageExtensions`、`BFArmorMaterial` 的接口定义，不触碰穿甲判定管线、`internal/BFContextStack.java` 与 `internal/BFHurtInterceptor.java`。

## 二、现状锚点

### 2.1 协议的两个通道

| 通道 | 入口 | 用途 |
| --- | --- | --- |
| 发起 | `api/BFDamageApi.java#hurt` | 武器模组、投射物、物理碰撞、护甲适配器发起一次协议伤害 |
| 重入守卫 | `internal/BFHurtInterceptor.java#intercept` | 挂在 `mixin/EntityHurtMixin.java` 与 `mixin/LivingEntityHurtMixin.java` 的 `hurt` HEAD 上，判断到达 `hurt` 的这一次调用该由谁承担 |

`BFHurtInterceptor.intercept` 的判定顺序（沿用《去实体化与命中转发计划》§2.1 的编号）：

| # | 判据 | 处理 |
| --- | --- | --- |
| 1 | `BFDamageApi.hasContextFor(self)` | 已在同一目标的协议管线内，放行原版流程 |
| 2 | `self instanceof BFHurtTarget` | 经 `createContextFromVanilla` 转入协议管线 |
| 3 | `self instanceof BFHitResolver` | 按解析出的实际目标转发伤害 |
| 4 | 穿戴 `BFArmorMaterial` 护甲 | 经适配器转入协议管线 |
| 5 | 以上皆不满足 | 放行原版流程 |

情况 1 是**方法级返回**：命中它时情况 2/3/4 都不会执行。情况 1 只存在于 `Entity#hurt` 与 `LivingEntity#hurt` 这两个被注入的方法体里；子类若覆写 `hurt`，该覆写体不被注入。

### 2.2 重入守卫的判据是"栈顶是调用者本人"

`BFContextStack`（`internal/BFContextStack.java`）是一个 `ThreadLocal<Deque<Entry>>`，压栈目标由 `BFDamageApi.hurt` 决定（`api/BFDamageApi.java#hurt`）：

```java
Object stackTarget = target;
if (target instanceof BFHurtTarget bfTarget && bfTarget.getBFEntity() != null) {
    stackTarget = bfTarget.getBFEntity();
}
BFContextStack.INSTANCE.push(stackTarget, ctx);
try { ... } finally { BFContextStack.INSTANCE.pop(); }
```

而守卫只比较**栈顶元素**与调用者，判据是引用相等（`internal/BFContextStack.java#hasContextFor`）：

```java
public boolean hasContextFor(Object target) {
    Deque<Entry> deque = stack.get();
    return !deque.isEmpty() && deque.peek().target() == target;
}
```

两段合起来给出一个前提：**只有当"承受伤害的那个对象"在管线期间就是栈顶 target 时，情况 1 才会命中。**

`internal/BFArmorAdapter.java` 满足该前提：它作为 `BFHurtTarget` 被压栈时，`getBFEntity()` 返回被包裹的实体（`internal/BFArmorAdapter.java#getBFEntity`），因此它的 `hurt` 里那一句 `entity.hurt(source, amount)`（`internal/BFArmorAdapter.java#hurt`）进去时 `hasContextFor(entity) == true`，放行原版流程，原版护甲与附魔在协议计算之上做二次减免。

该前提的适用边界：上述成立依赖"`hurt` 的接收者等于压栈时选取的 target"。非实体 `BFHurtTarget`（`getBFEntity()` 返回 null 的实现）若在 `hurt` 内部转调某个实体的 `hurt`，压栈目标会是它自身而不是那个实体，情况 1 不会命中。

### 2.3 该前提在"部件 → 装配体 → 实体"链上不成立

当伤害先落在部件上、由部件结算、再由部件所属的装配体把结果交给一个实体时，"投递的接收者"与"压栈时选取的 target"会分离：

```text
hurt(host, ctx)                       push(host)                   栈顶 = host
  → 情况 3：host 是 BFHitResolver
  → resolveHit → part
  → hurt(part, ctx2)                  push(part)                   栈顶 = part
      → 情况 2：part 是 BFHurtTarget → 穿甲 → part.hurt(source, finalDmg)
          → part 结算（耐久、子系统事件、摧毁判定）
          → 装配体汇总后调用 host.hurt(source, residual)
              hasContextFor(host) == false   ← 栈顶是 part
              → 情况 2 或情况 3 再次接管
              → resolveHit → part → …… 无限递归
```

**根因可以一句话概括：投递的接收者（host）在投递发生的那一刻不是栈顶。** 于是守卫把它当作一次新伤害，而 host 恰好又是 `BFHitResolver`，于是它重新路由回部件。

第一趟的 `hurt` 不受此问题影响：`hurt(host, ctx)` 在执行期间栈顶就是 host，其内部任何转调 `host.hurt` 的调用都会命中情况 1。处于危险中的是**第一趟结束之后**由装配体发起的那一次。

这不是某个模组的用法问题，而是"承载者不是当前受击对象"这一类拓扑在协议里没有对应语义。

**下游的现行代码已经是这个形态，只差最后一步尚未接上**：兄弟仓库 Machine-Max 的 `common/mech/vehicle/SubPart.java` 继承 `common/mech/DestroyableObject.java`，后者实现 `BFHurtTarget`（`DestroyableObject.java` 的类声明），而 `SubPart` 不是 `Entity`，因此 `BFHurtTarget#getBFEntity` 的默认实现对它返回 null；该仓库的 `common/entity/MMPartEntity.java#hurt` 从不调用 `super.hurt()`，而是把伤害转交 `SubPart`（`MMPartEntity.java#hurt` 内的 `BFDamageApi.hurt(hitSubPart, ctx)`）。于是压栈目标是 `SubPart`，而 `MMPartEntity#hurt` 内的 `BFDamageApi.getContextFor(this)` 因为栈顶不是它而恒为 null。

### 2.4 一次命中可能经过两次协议调用

一次命中可能经历两个相互独立的阶段：

1. **第一趟（发起 + 承受）**：`BFDamageApi.hurt` 在命中那一刻跑穿甲管线，实际目标承受伤害并完成自身结算；
2. **第二趟（投递）**：结算结果在稍后的一个相位被汇总，交付给承载实体。

两趟各自回答一个独立问题：**第一趟问"谁承受了这次命中"，第二趟问"这些承受最终扣谁的血"。**

第二趟的入口形态与第一趟的差别有两处。**其一**，`BFDamageApi.hurt(carrier, ctx)` 会在投递期执行承载者作为 `BFHurtTarget` 的**本体层**（`resolvePenetration` / `calculateFinalDamage`）；到达投递时点时弹体参数已被折算成"伤害量 + 残余穿深"两个标量，"再穿一层本体装甲"既无法判定，又会让已折算的数值再叠一遍。**其二**，投递需要一个"跳过贴身护甲层"的显式开关（§5.2 的 `ignoreBFArmor`）。

需要说明的是：**协议管线本身不做路由。** `BFDamageApi.hurt` 的方法体内不调用 `resolveHit` / `resolveHitTarget`——`BFHitResolver` 只出现在 `api/BFDamageApi.java#isProtocolAware` 与 `api/BFDamageApi.java#resolveHitTarget` 两处解析方法中，而 `hurt` 在进入分支判定之前就已完成压栈。因此"第二趟若走 `hurt` 会被重新路由回部件"这个说法不成立；选择 `deliverTo` 的理由是上面那两处形态差别。

**投递是独立的协议调用，而非第一趟的回调**：下游的结算相位已经把第二趟独立出来。兄弟仓库 Machine-Max 的 `docs/伤害结算相位与装配体投递修改计划.md` §5.1 把零件伤害结算放在 `LevelTickEvent.Post`，§5.4 的 `common/mech/vehicle/IPartAssembly.java#onPartDamage(Part, List)` 把"本零件本次结算产生的全部命中记录"通告给装配体，Machine-Max 的 `docs/伤害结算相位与装配体投递修改计划.md` §七明确"不规定投递"，投递由装配体实现自行扇出。

## 三、实际场景需求

### 3.1 宿主是解析者，部件是承受者，宿主是最终承载者

以"义体化玩家"为例（兄弟仓库 ARMS-Core 的 `docs/宿主接入与伤害管线设计.md` §一、§4.4）：

- **宿主只有一个实体**：玩家自己的 `net.minecraft.world.entity.player.Player`。机体不是实体，而是挂在玩家身上的一份数据（ARMS-Core 的 `docs/宿主接入与伤害管线设计.md` §二），因此不存在"玩家实体"与"机体实体"的位置同步与伤害仲裁问题。
- **玩家实体实现 `BFHitResolver`**：它回答"这一下实际打到了哪个零件"，不回答"我能吸收多少"（ARMS-Core 的 `docs/宿主接入与伤害管线设计.md` §4.1、§4.2）。
- **玩家实体不实现 `BFHurtTarget`**：装甲层由零件承担；玩家一旦声明自己是协议伤害目标，协议管线里就会多出一条与零件装甲重叠的判定路径（ARMS-Core 的 `docs/宿主接入与伤害管线设计.md` §4.2 第 3 条）。
- **伤害的最终落地必须走原版 `hurt`**：原版的护甲与附魔减免发生在 `net.minecraft.world.entity.LivingEntity#actuallyHurt` 内部，无敌帧、荆棘反伤与 `LivingEntityHurtEvent` / `PlayerHurtEvent` 也在这一层。直接把结算值写进生命值会绕过上述全部语义（ARMS-Core 的 `docs/宿主接入与伤害管线设计.md` §4.4）。

由此得到的链路是：

```text
玩家被命中
  → Player#hurt（下游钩子：hasContextFor(玩家) == false）
      → resolveHit 解析到 SubPart → BFDamageApi.hurt(subPart, ctx)     ← 第一趟
          → 零件装甲判定 → SubPart.hurt 入队
  …tick 末尾统一结算…
  → 装配体汇总 → BFDamageApi.deliverTo(玩家, ctx修正后)                ← 第二趟
      → 玩家穿戴的贴身护甲独立判定
      → push(玩家) → 玩家.hurt（原版：护甲/附魔/无敌帧/事件）
```

### 3.2 承载者的伤害量在第一趟的 `hurt` 调用期内不可得

零件在 `hurt` 调用内拿不到装配体实际承受的伤害量。零件把每次命中的伤害入队，在之后的一次统一结算中把整批伤害折算成"装配体伤害"，再通告装配体（兄弟仓库 Machine-Max 的 `docs/伤害结算相位与装配体投递修改计划.md` §5.3、`common/mech/vehicle/SubPart.java#settleAccumulatedDamage`）。因此"宿主承受多少"这个值在 `hurt` 返回之后才存在，投递天然是**第二次**协议调用。

该结论适用于 §3.1 的部件拓扑。若承载者本身就是一个 `BFHurtTarget` 实体（例如"伤害先落到外骨骼实体，再由它交给玩家"），则协议已经在 `api/BFDamageApi.java#hurt` 的本体层把最终伤害量交给了它的 `hurt(source, amount)`，投递方在调用点上直接持有该值——此时 `deliverTo` 的职责是"重入护栏 + 只跑护甲层"，而不是"延迟取数"。

### 3.3 投递不得被路由回起点

装配体拿到"本次应向宿主投递多少"之后，必须有一个入口让这次投递**只做落地、不做路由**：

- **不经过 `BFHitResolver`**：路由一旦执行就会回到触发它的那个零件，形成 §2.3 的递归；
- **不经过 `BFHurtTarget` 的协议管线**：这次命中已经过零件装甲的判定；承载者的本体层参数（弹体质量、速度、入射角）在投递时点已不存在，重跑一次只能得到无意义的结论；
- **完整走原版 `hurt`**：无敌帧、原版护甲、附魔、荆棘反伤、原版伤害事件全部保留。

第三条是本需求与"直接写生命值"的分水岭。

## 四、意义

1. **补齐一条协议缺失的语义。** 协议现在能表达"这次命中打到了谁"（`BFHitResolver`）与"这个目标承受多少"（`BFHurtTarget`），但不能表达"这个目标的伤害最终算在谁头上"。`deliverTo` 补的是后者。
2. **把既有的隐式前提升格为显式契约。** `BFArmorAdapter` 已经在做"包装体转发 + 最终应用到被包裹的实体"，它成立的条件是被包裹的实体恰好是栈顶。`deliverTo` 把这个条件从巧合变成保证，其余同类拓扑不必再各自发明办法。
3. **让落地结果可判定。** 投递入口返回原版 `hurt` 的原始结果，配合投递期护甲层回调（§六）即可区分"护甲挡下"与"原版拒绝"，见 §5.6。
4. **不改动 `DamageSource`。** 原始伤害来源对象全程原样透传，第三方模组按伤害类型/来源做的判定不受影响。区分"路由"与"投递"的依据是调用点与显式开关，不是载荷字段。

## 五、调用契约

### 5.1 签名与语义

```java
/**
 * 承载者投递：把一次已结算的伤害交给它的承载实体。承载者穿戴的
 * {@link BFArmorMaterial} 护甲参与本次判定。
 *
 * <p>等价于 {@code deliverTo(carrier, ctx, false)}。
 */
public static boolean deliverTo(Entity carrier, BFDamageContext ctx)

/**
 * 承载者投递：把一次已结算的伤害交给它的承载实体。
 *
 * <p><b>不做穿甲判定，也不修正数值。</b>{@code ctx} 必须由调用者以穿透后的修正值
 * 构造：{@code baseDamage} 是要投递的伤害量，{@code penetration} 是到达承载者的
 * 残余穿深（见 §5.7）。本方法只做三件事：把承载者压为上下文栈顶 → 若承载者穿戴
 * {@link BFArmorMaterial} 护甲且 {@code ignoreBFArmor} 为 false 则过一遍它的护甲层
 * → 交给承载者的原版 {@code hurt}。
 *
 * <p><b>绕行范围</b>：本方法不判定目标身份、不选取分支、不执行承载者作为
 * {@link BFHurtTarget} 的本体层（{@code resolvePenetration} /
 * {@code calculateFinalDamage}），也不经过 {@link BFHitResolver} 的路由——承载者
 * 即使是解析器也不会被重新解析。两个重载的差别只有一处：{@code ignoreBFArmor} 为
 * true 时再额外跳过承载者穿戴的 {@link BFArmorMaterial} 护甲层及其回调。逐环节的
 * 对照见 §1.2。
 *
 * <p>落地仍走原版 {@code hurt}，因此无敌帧、原版护甲、附魔、荆棘反伤与伤害事件
 * 全部保留。
 *
 * <p><b>调用约束</b>：调用时上下文栈中不得已存在任何正在结算的 {@code BFHurtTarget}，
 * 承载者自身也不例外。栈中已存在承载者时，本次投递被拒绝（返回 false）并记录警告；
 * 栈中存在其它目标时的后果见 §5.8。
 *
 * @param carrier       承载实体；调用期内被压为上下文栈顶，压栈目标取
 *                      {@code carrier.getBFEntity()}（与 {@link #hurt} 同一规则）
 * @param ctx           已修正的投递上下文。source、命中几何、extensions、handler
 *                      均取自此处
 * @param ignoreBFArmor true 表示在"投递已绕开判定"的基础上，再跳过承载者穿戴的
 *                      {@code BFArmorMaterial} 护甲层：不跑三件套、不触发任何穿甲
 *                      回调、不消耗护甲耐久，伤害直接交给原版 {@code hurt}。
 *                      false 表示护甲层照常参与（见 §1.2 的环节对照表）
 * @return 一次落地的结果，取值见 §5.6。false 表示未落地——贴身护甲判定为未击穿/跳弹
 *         且 {@code calculateFinalDamage} 返回 0，或原版拒绝（无敌帧内且未超过上次
 *         伤害、已死亡、免疫、玩家受到的伤害量恰为 0）。
 *         true 不保证扣了血：原版护甲、附魔、吸收都可能把伤害削到 0
 */
public static boolean deliverTo(Entity carrier, BFDamageContext ctx, boolean ignoreBFArmor)
```

### 5.2 两个重载的差别

两个重载**只差一件事**：`ignoreBFArmor` 为 true 时额外跳过承载者穿戴的 `BFArmorMaterial` 护甲层及其回调。其余绕行（不判定目标身份、不跑承载者本体层、不路由）两者相同，逐环节对照见 §1.2。

| `ignoreBFArmor` | 护甲层三件套 | 护甲层回调与 `armorAfterHurt` | `carrier.hurt` | 结果 |
| --- | --- | --- | --- | --- |
| `false`（二参重载的等价形态） | 跑 | 触发 | `delivered > 0` 时调用 | 护甲挡下则返回 false，**回调照发**、耐久照扣 |
| `true` | 不跑 | 不触发 | 恒调用 | 投递路径上不再有任何 BF 判定环节（§1.2） |

运行时行为重合的一种情形：承载者未穿戴 `BFArmorMaterial` 时，`false` 与 `true` 的实际效果相同。二者不冗余——`ignoreBFArmor` 表达**意图**（"这次命中已判定过，不要再判"），未穿戴表达**事实**（没有甲可判）；调用方无法预知承载者装备时应使用前者。

### 5.3 实现骨架

```java
public static boolean deliverTo(Entity carrier, BFDamageContext ctx, boolean ignoreBFArmor) {
    Objects.requireNonNull(carrier, "carrier");
    Objects.requireNonNull(ctx, "ctx");
    if (ctx.baseDamage() <= 0f) return false;
    if (carrier.level().isClientSide()) return false;
    if (hasContextFor(carrier)) {
        BallisticsFramework.LOGGER.warn("deliverTo 被拒绝：承载者已在上下文栈中");
        return false;
    }

    BFDamageHandler handler = ctx.getHandler();
    BFContextStack.INSTANCE.push(stackTargetOf(carrier));
    try {
        // ---- 护甲层：与 hurt 的护甲分支同构（绕行范围见 §1.2 的 ⑤⑥）----
        if (!ignoreBFArmor
                && carrier instanceof LivingEntity living
                && BFArmorAdapter.hasBFArmor(living)) {
            BFArmorAdapter adapter = new BFArmorAdapter(living);
            CarrierArmorResult r = BFArmorAdapter.reduceForCarrier(living, ctx);
            // 伤害前回调——护甲层判定已确认
            if (handler != null) triggerBeforeCallbacks(handler, adapter, ctx, r.armorResult());
            // 护甲层 afterHurt（耐久损耗等，在 hurt 之前触发）
            adapter.armorAfterHurt(ctx, r.armorResult(), r.delivered());
            // 伤害后回调——护甲层处理完毕
            if (handler != null) triggerCallbacks(handler, adapter, ctx, r.armorResult());

            if (r.delivered() <= 0f) return false;      // 护甲挡下：回调已发、耐久已扣
        }
        // ---- 本体层：不执行。直接落地 ----
        return carrier.hurt(ctx.source(), ctx.baseDamage());
    } finally {
        BFContextStack.INSTANCE.pop();
    }
}
```

```java
// internal/BFArmorAdapter.java 新增
/** 投递期护甲层的三件套结果。只承载数据，不触发任何回调。 */
public record CarrierArmorResult(PenetrationResult armorResult,
                                 float residualPen,
                                 float delivered) {}

/** 只在承载者穿戴的护甲物品上跑三件套，不触碰本体层、不触发回调。 */
public static CarrierArmorResult reduceForCarrier(LivingEntity carrier, BFDamageContext ctx) {
    BFArmorAdapter adapter = new BFArmorAdapter(carrier);
    float residualPen = adapter.modifyPenetration(ctx);
    PenetrationResult armorResult = adapter.resolvePenetration(ctx);
    float delivered = adapter.calculateFinalDamage(ctx, armorResult);
    return new CarrierArmorResult(armorResult, residualPen, delivered);
}
```

```java
// api/BFDamageApi.java 新增：从 hurt 中抽取的压栈目标选取规则，供两个入口共用
private static Object stackTargetOf(Object target) {
    if (target instanceof BFHurtTarget bfTarget && bfTarget.getBFEntity() != null) {
        return bfTarget.getBFEntity();
    }
    return target;
}
```

要点：

- **编排留在 `BFDamageApi`**，与 `hurt` 的护甲分支逐行同构（护甲三件套 → 伤害前回调 → `armorAfterHurt` → 伤害后回调 → 落地）。`BFArmorAdapter` 只提供数据，不触发回调——`triggerBeforeCallbacks` / `triggerCallbacks` 是 `api/BFDamageApi.java` 的 `private static` 方法，`internal` 包不可见。
- **`push` 早于护甲层**，因此投递期内 `getContextFor(carrier)` 与护甲物品的 `getContextFor(wearer)` 都返回传入的 `ctx`，见 §八。
- **投递期只可能过一个护甲层。** 承载者穿戴 `BFArmorMaterial` 时跑该层；否则一层都不跑，直接交原版 `hurt`。两层以上的情形只出现在 §2.3 的"第一趟"，不属于投递。
- **`amount <= 0f` 早退**：非正伤害量不产生任何副作用、不触发任何回调，返回 `false`。这是协议管线内"回调不取决于伤害量"规则的例外，理由见 §6.3。
- **客户端早退**：`carrier.level().isClientSide()` 时返回 `false`。承载者是实体，其 `hurt` 在客户端不产生伤害结算。
- **`push` / `finally pop` 成对**：与 `hurt` 的结构相同，异常路径下同样保证弹出。因此栈帧不存在"按深度回收"的需要，`BFContextStack` 只保留既有的 `push` / `pop` / `hasContextFor`。
- **`ctx` 不得为 null**：以 `Objects.requireNonNull` 拒绝，避免出现"投递成功但监听方读不到任何几何"的静默态。

### 5.4 压栈目标的选取规则

`hurt` 与 `deliverTo` 共用 §5.3 的 `stackTargetOf`。这条抽取不改变 `hurt` 的现有行为。规则本身是两条路径的正确性依赖：情况 1 的判据是"栈顶 target 与调用 `hurt` 的实体引用相等"，而 `BFHurtTarget` 委托实体 `hurt()` 时真正被调用的实体会被 mixin 注入点捕获——两者必须一致，否则守卫失效。

**承载者形态与压栈结果**：

| 承载者形态 | 压栈目标 | 投递期的结果 |
| --- | --- | --- |
| 普通 `Entity`（不实现 `BFHurtTarget`） | 实体自身 | 情况 1 命中，放行原版 |
| 实体且实现 `BFHurtTarget` | `getBFEntity()`（默认返回 `this`） | 情况 1 命中，放行原版；本体不跑协议管线，只跑它穿戴的护甲层 |
| 实体且实现 `BFHurtTarget` 但覆写 `getBFEntity()` 返回别的实体 | 那个实体 | 情况 1 不命中，守卫失效 |

第三行是误用：`BFHurtTarget#getBFEntity` 的 Javadoc 已经要求"委托实体 `hurt()` 就必须返回该实体"，承载实体不应例外。

**承载者自身是 `BFHurtTarget` 时，投递期不执行它的 `resolvePenetration` / `calculateFinalDamage`。** 该实体的本体装甲属于"穿透外装后的剩余弹体还要再穿一层"；到达投递时点时，弹体参数已被折算成伤害量与残余穿深，因此"再穿一层本体装甲"无法判定；即使强行判定，也会把已经穿透外装的弹体再拿去做一次本体护甲判定，数值叠两遍。投递期只执行它穿戴的 `BFArmorMaterial` 护甲层，本体交由原版 `hurt` 处理。

这也是 §5.3 里护甲层**不能复用 `BFDamageApi.hurt` 的复合目标与适配器两条分支**的原因：那两段会把本体层一并跑成协议路径。

### 5.5 投递期护甲层的减深与回调

**减深（三件套）**：`reduceForCarrier` 依次调用 `modifyPenetration` → `resolvePenetration` → `calculateFinalDamage`，与 `hurt` 的护甲分支相同。因此：

- `ctx.penetration()` 是打在贴身护甲上的穿深，直接参与判定（默认 `resolvePenetration` 用 `ArmorLevel.fromRha(modifyPenetration(ctx))` 与 `getArmorLevel(slot, ctx)` 比较，见 `api/BFArmorMaterial.java#isArmorPenetrated`）；
- `penetration <= 0` 时默认实现返回 `BLOCKED`：`ArmorLevel.fromRha` 把非正值映射到最低等级，`BFHurtTarget#isArmorPenetrated` 的默认实现在该等级下判定失败，`calculateFinalDamage` 返回 0，投递被贴身护甲完全挡下；
- `calculateFinalDamage` 的伤害输入是 `ctx.baseDamage()`。

**回调**：护甲层作为一次独立的穿甲事件触发两轮回调，顺序与 `hurt` 的护甲分支一致：

| 顺序 | 动作 | target | ctx | result |
| --- | --- | --- | --- | --- |
| 1 | `before*` | 该层的 `BFArmorAdapter` | 传入的 `ctx` | 该层的 `armorResult` |
| 2 | `armorAfterHurt` | —— | 传入的 `ctx` | 该层的 `armorResult` |
| 3 | `on*` | 该层的 `BFArmorAdapter` | 传入的 `ctx` | 该层的 `armorResult` |

`armorAfterHurt` 委托到 `BFArmorMaterial#afterHurt`（耐久损耗、爆反消耗、碎裂降级、统计追踪）。**未击穿时它同样触发**：护甲挡下这一发本身就是一次命中，耐久应当消耗。

### 5.6 回调边界与返回值

**投递期只触发上述护甲层回调。** 本体层回调与 `beforeNormalEntityHit` / `onNormalEntityHit` 一律不触发，理由有三条：

1. 投递的伤害**已经过外装判定**。再发一轮"击穿/未击穿"回调等于让武器侧对同一次命中收到两次结论。
2. `beforeNormalEntityHit` / `onNormalEntityHit` 的语义是"协议没有做穿甲判定、直接交原版"（`api/BFDamageHandler.java` 的方法 Javadoc），与投递的语义相反。
3. 投递发生在第一趟结算之后，此时发起方（如投射物）的命中结果往往已经定案。

`ignoreBFArmor` 为 true 时**连护甲层回调也没有**，整条投递路径不产生任何协议信号——这正是"已判定过"的含义。

**返回值的语义由"返回值 + 护甲层回调"共同确定**：

| `ignoreBFArmor` | 返回值 | 已触发的护甲层回调 | 可判定的含义 |
| --- | --- | --- | --- |
| `false` | `false` | `onBlocked`（可能追加 `onSpall`） | 贴身护甲挡下 |
| `false` | `false` | `onPenetrated` / `onRicochet` | 原版拒绝，或玩家受到的伤害量恰为 0 |
| `true` | `false` | 无 | 原版拒绝，或玩家受到的伤害量恰为 0 |
| 任意 | `true` | 视形态 | 已落地（原版护甲、附魔、吸收仍可能把伤害削到 0） |

"玩家受到的伤害量恰为 0 返回 false"来自 `Player#hurt` 的入口判断 `amount == 0.0F ? false : super.hurt(...)`；`LivingEntity#hurt` 本身没有零值早退。

**一条必须写进 `BFDamageHandler` 的约束**：投递期护甲层回调的 `target` 是护甲层适配器、不是发起方最初命中的那个目标，且投递发生在第一趟结算之后。兄弟仓库 Machine-Max 的 `common/mech/projectile/BallisticProjectile.java` 把回调用于驱动自身生命周期——`#onBlocked` 写入 `AfterHitResult.DESTROYED`、`#onRicochet` 写入跳弹结果、`#onPenetrated` / `#onSpall` 折算穿透后速度、`#beforePenetrated` 向 `ctx.extensions()` 写入 `IMPULSE`。因此**`BFDamageHandler` 的实现不得依据投递期回调改写发起方自身状态**；需要区分两次事件的实现应按 `target` 的**引用身份**分支——`BFArmorAdapter` 每次进入管线都是新实例（`internal/BFArmorAdapter.java#BFArmorAdapter`），不能用 `equals` 比较，只能比较 `target` 是否等于发起方最初命中的那个对象。

### 5.7 上下文构造约定（调用者义务）

`deliverTo` 不修正任何数值，因此调用者必须按下表构造 `ctx`：

| 字段 | 要求 | 理由 |
| --- | --- | --- |
| `baseDamage` | 穿透后的伤害量（第一趟协议的结算结果） | 投递期护甲层 `calculateFinalDamage` 的唯一输入 |
| `penetration` | 到达承载者的**残余穿深**（mm RHA） | 护甲层按它独立判定击穿 |
| `source` | 原始伤害来源，原样透传 | 第三方模组按伤害类型/来源做的判定 |
| `hitPoint` | **承载者身体坐标系**下的命中点 | `BFArmorMaterial#mapHitToSlot` 按 `hitPoint - wearer.position()` 与 `wearer.getBbHeight()` 的比值划分槽位。若沿用零件/外骨骼的命中点，该比值对承载者无意义，槽位会选错。无法换算时显式传 `Vec3.ZERO`，走加权平均兜底（`internal/BFArmorAdapter.java#resolveBestSlot`） |
| `hitNormal` / `hitVelocity` | 从原始命中上下文拷贝 | 覆写了 `resolvePenetration` 的护甲按它们计算入射角 |
| `extensions` / `handler` | 原样带过来 | 护甲层的判定与回调按需读取 |

**残余穿深的定义**：本次投递采用**串联衰减**模型——外装与贴身护甲各有等效厚度，逐层削弱。因此残余穿深 = "击穿外层后剩余的可穿深"，典型形式为 `原始穿深 − 被击穿目标的等效护甲`，例如 `ctx.penetration() - getRHA(ctx)`。

该差值**不含** `modifyPenetration` 的减效修正（爆反拦截、间隙衰减等）。框架内部另有一个含减效的同名量——`api/BFDamageApi.java#hurt` 护甲分支里的 `residualPen`——它只用于决定未击穿时是否把穿深归零，不随返回值导出。两者不是同一个数：调用方算出的值偏高，方向上偏保守（更容易判成击穿贴身护甲）。

**残余穿深的计算时点**：应在**命中时刻**（`BFHurtTarget#hurt` 内）算得，随同伤害量一起入队。放在批次结算期重算会引入两处偏差：结算期读到的耐久是整批结算前的值，而 `getRHA` 是耐久派生量；且批次结算在主线程执行，而 `BFDamageApi.hurt` 可由物理线程发起（兄弟仓库 Machine-Max 的 `common/mech/vehicle/collision/CollisionHandler.java` 即在碰撞回调内调用协议入口），跨线程重算 `getRHA` 会产生竞态。

### 5.8 调用约束

`deliverTo` 必须在**上下文栈中不存在正在结算的 `BFHurtTarget`** 时调用——承载者自身也不例外。§5.3 的实现以 `hasContextFor(carrier)` 做入口自检，命中即拒绝并记录警告。

自检只覆盖承载者一个身份，它拦不住"外层目标仍在结算"的调用（例如从发起方自己的 `hurt` 内投递）。那类调用虽然能正确落地，却会把外层帧压在承载者帧之下，使投递期内 `hasContextFor(外层目标)` 恒为 false——外层目标的下游钩子会把这当成一次新伤害而重新路由。因此该约束同时是**调用方义务**：

1. **推荐形态**：在结算相位（栈为空）投递，例如 `LevelTickEvent.Post`；
2. **次选形态**：在发起方自己的 `hurt(source, amount)` 方法体内投递。此时外层的那一帧就是发起方，投递期内它不再需要被守卫；
3. **禁止形态**：在另一个 `BFHurtTarget` 正在结算时投递第三个实体。

**投递不得重试**：返回 `false` 表示未落地，调用方不得据此重投。**同一批次对同一承载者只投递一次**：结算批次应自行汇总，重复调用会让承载者按原版无敌帧规则只吃到第一次（`LivingEntity#hurt` 在 20 tick 窗口内对不大于 `lastHurt` 的命中直接返回 false），或在窗口外重复扣血。

## 六、投递期的回调，以及它为什么比发起期少

### 6.1 通用回调一概不发

既有的规则是"每过一个穿甲层，该层各触发一轮伤害前与伤害后回调"（见 `docs/wiki/4-协议内幕/4.1-穿甲判定管线.md` 的回调双阶段模型一节）。`deliverTo` 对这个规则的落地是：**只对投递期真正跑过的那一层触发回调。**

- **跑过护甲层时**：触发该层的两轮回调，`target` 为该层的 `BFArmorAdapter`。第一趟的零件装甲与投递期的贴身护甲是两次独立的穿甲事件，武器侧的 `BFDamageHandler` 因此各收到一次回调。
- **未跑护甲层时**（涵盖 `ignoreBFArmor == true` 与承载者未穿戴 `BFArmorMaterial` 两种情形）：一层都没过，**不产生任何回调**。

### 6.2 护甲层 `on*` 的时序语义

护甲层的 `on*` 排在 `armorAfterHurt` 之后、`carrier.hurt` 之前，因此它**不是**"承载者已受伤"的信号。这与 `hurt` 护甲分支的形状相同——该层的 `before*` 表示"该层穿甲判定已确认"，`on*` 表示"该层处理完毕"。

### 6.3 零伤害早退是回调规则的例外

`amount <= 0f` 时 `deliverTo` 直接返回 `false`，不触发任何回调。协议管线内的规则是"回调是否触发不取决于 `dealt` 是否大于 0"，投递入口在此处不沿用：零伤害量意味着调用方没有可投递的内容，它既不是一次被挡下的命中，也不构成一次穿甲事件。

## 七、拦截器为何不需要改动

`deliverTo` 在压栈之后调用 `carrier.hurt`，此刻 `hasContextFor(carrier) == true`，`internal/BFHurtInterceptor.java#intercept` 的情况 1 直接命中并放行原版流程，**不会到达情况 2 / 3 / 4**。因此拦截器无需任何新增分支——新增的语义只体现在"压的是谁"上。

情况 1 只存在于 `Entity#hurt` 与 `LivingEntity#hurt` 两个被注入的方法体中（`mixin/EntityHurtMixin.java`、`mixin/LivingEntityHurtMixin.java`）。承载者若覆写 `hurt` 且不调用 `super.hurt(...)`，投递不会进入注入体、情况 1 无从命中——此时投递仍然按预期工作，因为**压栈本身已经让承载者退出 BF 管线的判定范围**：承载者覆写体里的 `BFDamageApi.getContextFor(this)` 返回非 null，该约定即"本次调用来自协议管线内部，直接落地"（见 `docs/wiki/3-护甲侧开发/3.1-实现BFHurtTarget.md` 的不委托 super.hurt() 的自定义处理一节）。**因此投递的终止不依赖"承载者的 `hurt` 是否调用 super"这一前提**；调用 super 只是让情况 1 也参与放行。

**`hasContextFor` 是路由守卫，不是投递守卫。** 它回答"这次 `hurt` 要不要改道"，不回答"这次伤害是否已经投递过"。§5.3 的入口自检因此判的是"承载者是否已在栈中"，而不是"栈顶是否是承载者"。

下游若在承载实体自己的 `hurt` 上另有钩子（兄弟仓库 ARMS-Core 的 `docs/宿主接入与伤害管线设计.md` §4.4 即如此设计：`Player#hurt` 的钩子以同一个 `hasContextFor` 为判据），该钩子同样会因为栈顶是承载实体而放行原版流程。**该钩子的判据必须写成"栈顶是本实体"**——写成"栈中存在任意目标即放行"会让落在发起方帧内的投递被静默吞掉。

## 八、线程与生命周期

- **调用线程**：`deliverTo` 只能在**服务端主线程**调用。`carrier.hurt` 会触发原版事件、附魔结算与死亡判定，这些都不允许在物理线程执行；而 `BFContextStack` 是 `ThreadLocal`，压栈与回收必须发生在同一线程——`deliverTo` 的结构（同方法内 `push` / `finally pop`）满足该要求。
- **线程纪律的归属**：框架在客户端早退（§5.3），服务端主线程的约束由调用方保证。这与 `BFDamageApi.hurt` 的既有分工一致：协议伤害可从任意线程发起、结算在主线程，投递发生在结算之后的主线程上。兄弟仓库 Machine-Max 的 `docs/伤害结算相位与装配体投递修改计划.md` §5.1 把零件结算放在 `LevelTickEvent.Post`，其 §5.5 的约束是"投递必须晚于全部零件的结算"，具体扇出相位由装配体实现选择；ARMS-Core 的 `docs/宿主接入与伤害管线设计.md` §八 第 2 项要求"玩家实体的 `hurt` 钩子不得在物理线程进入机甲管线"。
- **`ctx` 承载的上下文语义**：投递期内 `getContextFor(carrier)` 返回传入的 `ctx`，它遮蔽了外层的栈帧。调用方应让 `ctx` 复用批次中代表性一次命中的上下文，使通过 `getContextFor` 读取命中几何的监听方拿到可用的几何数据。
- **`ctx` 不得为 `null`**：构造投递上下文是调用者的义务（§5.7），实现以 `Objects.requireNonNull` 拒绝 `null`，避免出现"投递成功但监听方读不到任何几何"的静默态。

## 九、影响面

| 文件 | 改动 |
| --- | --- |
| `api/BFDamageApi.java` | 新增 `deliverTo(Entity, BFDamageContext)` 与 `deliverTo(Entity, BFDamageContext, boolean)`；新增私有静态 `stackTargetOf`，`hurt` 改为调用它；类 Javadoc 补第二入口 |
| `api/BFDamageHandler.java` | 类 Javadoc 补 §5.6 的约束：投递期回调的 `target` 是护甲层适配器、不得用于改写发起方自身状态 |
| `internal/BFArmorAdapter.java` | 新增静态入口 `reduceForCarrier(LivingEntity, BFDamageContext)` 与结果类型 `CarrierArmorResult`：只做护甲层三件套，不触发回调、不进入本体层 |
| `internal/BFContextStack.java` | 不改（`push` / `pop` / `hasContextFor` / `getContextFor` 的行为保持不变）；`#pop` 的错误日志措辞可顺带带上 `deliverTo` |
| `internal/BFHurtInterceptor.java` | 不改 |
| `api/BFHitResolver.java` / `api/BFHurtTarget.java` / `api/BFDamageContext.java` / `api/BFDamageExtensions.java` / `api/BFArmorMaterial.java` | 不改 |
| `example/entity/` | 新增示例代理实体：实现 `BFHitResolver`、不实现 `BFHurtTarget`，供 §十 场景 1/2/3 使用；在 `example/ExampleContent.java` 注册 |
| `example/gametest/BallisticsGameTest.java` | 新增 §十 的场景；类 Javadoc 的场景计数同步 |
| `AGENTS.md`（仓库根） | 同步 GameTest 场景计数与版本号 |
| `docs/wiki/附录/A.1-API参考.md` | `BFDamageApi` 的方法清单补 `deliverTo` 两个重载 |
| `docs/终点弹道设计文档.md` | §二 的类职责行与 §十 的 `BFDamageApi` 方法清单补 `deliverTo` |
| `docs/wiki/4-协议内幕/4.1-穿甲判定管线.md` | 入口小节补"发起"与"投递"两个入口的分工 |
| `docs/wiki/1-快速上手/1.6-核心API速览.md` | `BFDamageApi` 条目补 `deliverTo` |

**向后兼容**：`deliverTo` 是新增静态方法，既有调用方不受影响；`stackTargetOf` 是私有静态方法，不进入对外 API 面；`reduceForCarrier` 与 `CarrierArmorResult` 是 `internal` 包内的新增类型。

**本计划的非目标**：

1. **不解决既有护甲路径的穿深出口问题。** `BFDamageApi.hurt` 的护甲分支在把伤害交给本体时，修正后的残余穿深只在方法内部使用，不随返回值交出。投递所需的残余穿深由投递方自行算得（§5.7）。
2. **不改变穿甲判定管线**、`BFHurtTarget` 接口定义、`BFDamageContext` 的字段集合。
3. **不规定投递的批次粒度。** 装配体按 `DamageSource` 分组投递还是合并为一次投递，属于调用方的设计（兄弟仓库 Machine-Max 的 `docs/伤害结算相位与装配体投递修改计划.md` §5.5、§七）。
4. **不为"部分绕过"提供开关。** `ignoreBFArmor` 是全有或全无：不提供"只跳过某几个槽位"或"只跳过回调、仍跑判定"的中间态。
5. **不校验 `hitPoint` 的坐标系。** 换算到承载者身体坐标系是调用方义务（§5.7）；框架不检测、不修正、不回退。
6. **不提供"实际扣血量"的出口。** 原版 `hurt` 只返回布尔值；若确有需求，应在 `BFDamageHandler` 上另开口子，而不是让 `deliverTo` 返回更多信息——本文不包含该扩展。

## 十、验证

以 GameTest 覆盖。递归失败的表现是栈溢出或静默不生效，无法靠阅读代码确认。

**前置件**：`GameTestHelper#makeMockPlayer(GameType)` 返回的是 `net.minecraft.gametest.framework.GameTestHelper` 内的匿名 `Player` 子类，无法在其上追加 `BFHitResolver`。场景 1/2/3 因此需要一个新增的示例代理实体（实现 `BFHitResolver`、不实现 `BFHurtTarget`）。该前置件与《去实体化与命中转发计划》§八 末记录的是同一件工作。

**mock 玩家的三条性质**（均是该匿名类自身的性质，不是可配置项）：

1. 它**不加入 level、不参与 tick**，因此 `invulnerableTime` 不递减、`lastHurt` 在整条测试方法内共享。同一测试内第二次 `hurt` 会落在同一个 20 tick 窗口里：伤害不大于 `lastHurt` 时返回 false 且不掉血，更大时只扣差值。需要多次落地的用例必须自建多个 mock 玩家。
2. 它的 `abilities.invulnerable` 与 `abilities.instabuild` 均为默认的 false，构造期不调用 `GameType#updatePlayerAbilities`。因此 `GameType.CREATIVE` 既不产生创造免疫，也不让 `DamageSource#isCreativePlayer()` 为真（后者读的是 `abilities.instabuild`）。
3. `Player#setItemSlot` 写的是玩家物品栏的护甲槽，mock 玩家同样可用，可以直接穿戴 `example/item/ExampleArmorItem.java`（`BFArmorMaterial`，`getRHA` 返回 40mm）。

**场景**：

1. **直击 + 路由**：宿主实现 `BFHitResolver`、零件实现 `BFHurtTarget`。以宿主为命中对象发起一次协议伤害，断言：零件耐久下降、`resolveHit` 被调用、宿主生命值**未**变化（第一趟不含投递）。
2. **投递**：在上一场景之后调用 `deliverTo(宿主, ctx修正后)`，断言：宿主生命值按 `ctx.baseDamage()` 经原版管线后的结果下降、返回值为 `true`、调用期内 `hasContextFor(宿主)` 为真。
3. **不递归**：在场景 2 中让宿主同时实现 `BFHitResolver`，断言整条链路在一次 `deliverTo` 内结束（`resolveHit` 不被调用第二次），且不抛栈溢出。
4. **贴身护甲层**：承载者穿戴一件 `BFArmorMaterial`、`penetration` 取大于该护甲 RHA 的值。断言 `deliverTo` 返回 `true`、`carrier.hurt` 收到经 `calculateFinalDamage` 折算后的量，且该层的 `before*` / `on*` 各触发一次、`afterHurt` 触发一次。伤害来源用 `damageSources().mobAttack(...)` 或 `playerAttack(...)`——`damageSources().generic()` 属于 `bypasses_armor` 标签，会绕过原版护甲，使"协议层与本体层两处减免"被观测成一处。
5. **贴身护甲独立击穿**：`penetration` 取小于该护甲 RHA 的值、`baseDamage > 0`。断言 `deliverTo` 返回 `false`、承载者生命值不变、该层 `onBlocked` 触发一次、`afterHurt` 仍触发一次（耐久消耗）。
6. **承载者同时是 `BFHurtTarget`**：让承载者实现 `BFHurtTarget` 并记录其 `resolvePenetration` / `calculateFinalDamage` 的调用次数，断言投递期内本体层调用次数为 0 而护甲层三件套各调用 1 次。
7. **`ignoreBFArmor = true`**：承载者穿戴 `BFArmorMaterial`，`penetration` 取小于该护甲 RHA 的值。断言：`deliverTo` 返回 `true`、承载者生命值下降、该护甲物品的三件套与 `afterHurt` **均未被调用**、handler 未收到任何回调。
8. **重复投递与自检**：在承载者自己的 `hurt` 内再调用一次 `deliverTo(承载者, ...)`，断言第二次返回 `false` 并记录警告，不发生栈溢出。
9. **边界**：`ctx.baseDamage() == 0` 时返回 `false`、不调用 `carrier.hurt`、不触发任何回调。无敌帧路径单独成条：先 `carrier.setInvulnerable(true)` 验 `isInvulnerableTo`，再显式给定 `lastHurt` 验 20 tick 窗口路径。两种 `false` 都必须与"护甲挡下"区分开，因此断言要读生命值而不只是布尔返回。

既有 8 个 GameTest 全部是手工构造 `ctx` 后直接调用 `BFDamageApi.hurt`，没有覆盖"`Entity#hurt` → mixin → 拦截器"这条链路。场景 2、3、8 是首次把该链路纳入自动化验证，落地时的调试成本会高于既有场景。

## 附录 A：设计决策记录

本文的形态由下列决策确定。每条记录"决定了什么"与"为什么"，读者不需要了解任何其它版本即可理解正文。

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | 投递期的减深采用串联衰减模型：残余穿深 = 原始穿深 − 被击穿目标的等效护甲 | 外装与贴身护甲各有等效厚度，逐层削弱；打穿第一层而未打穿第二层是应有语义。护甲的判定本就依赖穿深数值，数值偏大或偏小直接决定"是否被击穿"这一二值结论，这是护甲的定义而非缺陷 |
| D2 | `ignoreBFArmor` 做成显式布尔参数，并提供默认走护甲的二参重载 | 调用方在无法预知承载者装备时需要一个"已判定过、不要再判"的开关；默认值取"走护甲"使常规调用点最短，`true` 只出现在刻意的例外处 |
| D3 | 投递期只触发护甲层回调，本体层回调与 `beforeNormalEntityHit` / `onNormalEntityHit` 一概不发 | 投递的伤害已经过外装判定，再发一轮结论等于让武器侧对同一次命中收到两次通知；这两个通用回调的语义是"协议没有做穿甲判定直接交原版"，与投递相反 |
| D4 | 护甲挡下时仍触发该层回调与 `armorAfterHurt` | 护甲挡下一发本身就是一次命中，耐久应当消耗；挡下的结论也需要回报给发起方，返回值单靠布尔无法与"原版拒绝"区分 |
| D5 | `push` 排在护甲层之前 | 让投递期内的 `getContextFor(carrier)` 与 `getContextFor(wearer)` 都返回传入的 `ctx`，与 `hurt` 的护甲分支同构 |
| D6 | 投递期的三件套与回调编排留在 `BFDamageApi`，`BFArmorAdapter` 只返回数据 | `triggerBeforeCallbacks` / `triggerCallbacks` 是 `BFDamageApi` 的私有静态方法，`internal` 包不可见 |
| D7 | 入口自检判"承载者是否已在上下文栈中"，而不是"栈顶是否是承载者" | `hasContextFor` 是路由守卫、不是投递守卫；判"已在栈中"同时拦住退化的自投递与同链路的重复投递 |
| D8 | 不改 `BFDamageContext` 的字段集合 | 区分"发起"与"投递"发生在调用点，由压栈与显式开关完成；承载者侧的判据是 `getContextFor` 返回非 null |

## 附录 B：待决

1. **残余穿深的表达式精度**：本文采用"原始穿深 − 被击穿目标的等效护甲"（§5.7）。该差值不含 `modifyPenetration` 带来的减效修正（爆反拦截、间隙衰减），因此对带爆反的目标会偏大，方向上偏保守（更容易判成击穿贴身护甲）。若后续需要精确的减效修正，需要在命中时刻另行记录修正后的穿深。
2. **`BFDamageApi.hurt` 护甲分支中 `residualPen` 的用途**：该值只用于"未击穿时把交给本体的穿深归零"，不参与护甲层自身的 `calculateFinalDamage`（后者读的是传入 `ctx` 的 `penetration`）。覆写了 `modifyPenetration` 的护甲因此只影响是否归零、不影响自身的伤害折算。本文不改动该行为；是否让三件套整体基于修正后的穿深运行，属于独立议题。
3. **是否需要"实际扣血量"的出口**：见 §九 非目标第 6 条。

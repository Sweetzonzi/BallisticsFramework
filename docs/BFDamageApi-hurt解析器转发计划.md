# BFDamageApi.hurt 解析器转发分支计划

> 关联文档：
>
> - [BFHitResolver-去实体化与命中转发计划.md](./BFHitResolver-去实体化与命中转发计划.md) —— `BFHitResolver` 接口、`BFHitResolveResult`、`searchDelta` / `searchRay`、场景 3 协议外伤害转发与命中结果缓存
> - [BFDamageApi-deliverTo投递计划.md](./BFDamageApi-deliverTo投递计划.md) —— 承载者投递入口的分工（本计划改写其 §2.2 的一处不变量措辞）
> - [终点弹道设计文档.md](./终点弹道设计文档.md) —— §十一 伤害路径总表、§十七 BFHitResolver 设计意图
> - wiki：[2.6-代理实体与命中解析](./wiki/2-武器侧开发/2.6-代理实体与命中解析.md)、[3.5-协议外伤害兼容](./wiki/3-护甲侧开发/3.5-协议外伤害兼容.md)、[3.7-代理实体：实现 BFHitResolver](./wiki/3-护甲侧开发/3.7-代理实体：实现BFHitResolver.md)、[4.1-穿甲判定管线](./wiki/4-协议内幕/4.1-穿甲判定管线.md)
>
> 本文件持有的契约：`BFDamageApi.hurt` 对"纯解析器目标"的处理、以及"无分支匹配"时的失败语义。
> 其余文档涉及同一规则时一律指向本文对应小节，不再另立副本。

## 一、概述

三项改动：

1. **新增分支 1.5（纯解析器转发）** —— 目标实现 `BFHitResolver` 但不实现 `BFHurtTarget` 时，用上下文自带的命中几何解析出实际目标，把这次协议伤害转发给它，而不是把伤害当作"普通实体回退"落在目标自身。
2. **转发上下文按解析结果重建** —— 由 `BFDamageApi.contextForResolvedTarget` 应用 `correctedHitPoint` / `correctedHitNormal` 并合并 `extensions`。拦截器情况 3（协议外伤害转发）同时改用它，两条转发路径共用一条重建规则。
3. **无分支匹配时响亮失败** —— 原实现的末行 `return 0f` 会让"既不是 `BFHurtTarget`、也不是 `Entity`"的目标被无声丢弃，改为记录 error 日志后再返回 0f。

改动落在 `api/BFDamageApi.java`（`hurt` 的分支 1.5、新建 `contextForResolvedTarget`）、`api/BFDamageExtensions.java`（新增 `mergeFrom`）与 `internal/BFHurtInterceptor.java`（情况 3 调用重建）。`deliverTo` 的行为、`BFHitResolver` / `BFHurtTarget` / `BFDamageContext` 的既有成员、穿甲管线与回调编排均不变。

## 二、现状锚点

### 2.1 `hurt` 的分支调度对 `BFHitResolver` 零处理

`api/BFDamageApi.java#hurt` 按目标身份选取管线，进入分支判定之前先压栈（`BFContextStack.INSTANCE.push(stackTargetOf(target), ctx)`）。`BFHitResolver` 只出现在 `api/BFDamageApi.java#isProtocolAware` 与 `api/BFDamageApi.java#resolveHitTarget` 两个解析方法中，`hurt` 的方法体内不引用它。

于是同一个"纯解析器目标"（实现 `BFHitResolver`、不实现 `BFHurtTarget`，如 `example/entity/ExampleProxyEntity.java`）会走出两种错误结果：

| 目标形态 | 落到的分支 | 后果 |
| --- | --- | --- |
| 纯解析器 **且是 `Entity`** | 普通 `Entity` 回退（`entity.hurt(ctx.source(), ctx.baseDamage())`） | 伤害扣在解析器自身、返回值非 0；调用方以为转发成功，实际去向错误 |
| 纯解析器 **且不是 `Entity`**（包装体、物理体属主） | 方法末尾 `return 0f` | 无伤害、无日志、无异常——本次协议调用被无声丢弃 |

两种结果都不会咨询解析器。`BFDamageApi.hasContextFor(Object)` 也救不了：`hurt` 已经把解析器压为栈顶，解析器自身的 `hurt` 会被 mixin 重入守卫放行，拦截器的场景 3 不会介入。

### 2.2 为什么文档说"不做路由"

`docs/BFDamageApi-deliverTo投递计划.md` §2.2 记录的不变量是"协议管线本身不做路由：`BFDamageApi.hurt` 的方法体内不调用 `resolveHit` / `resolveHitTarget`"。该不变量有两处来源：

- `api/BFDamageApi.java` 的 `hurt` 类内 Javadoc 与 `deliverTo` 的 Javadoc 都把"不判定目标身份"写成投递与发起的差别；
- `deliverTo` 存在的理由之一是"不再被路由回起点"（同文件 §2.3 的递归形态）。

本计划推翻的只是"`hurt` 不做路由"这半句，**不推翻 `deliverTo` 不路由**。两者的分工在 §四重新表述。

### 2.3 `resolveHit` 的结果类型决定不会有无限递归

`BFHitResolveResult.actualTarget()` 的静态类型是 `BFHurtTarget`（`api/BFHitResolveResult.java#actualTarget`）。分支 1.5 转发时调用的 `hurt(actualTarget, ctx)` 因此必然命中既有的 `BFHurtTarget` 分支——解析只发生一次，递归深度恒为 1。

前提是分支 1.5 的判据必须排除 `BFHurtTarget`：目标若同时实现两个接口，它已经声明"我就是受击者"，走既有 `BFHurtTarget` 分支。这与拦截器的优先级一致（`internal/BFHurtInterceptor.java#intercept` 情况 2 先于情况 3）。

## 三、目标与非目标

**目标**

- G1 纯解析器目标收到协议伤害时，伤害转发给 `resolveHit` 解析出的实际目标。
- G2 `hurt` 不出现"无伤害、无痕迹"的出口：任何未被分支接住的目标都留下错误日志。
- G3 `BFHurtTarget` 分支、护甲适配器分支、普通实体回退分支、复合目标双层管线的行为全部不变。
- G4 上下文档的重入语义不变：栈顶始终代表"本次协议调用真正把伤害交给谁"。

**非目标**

- 不改 `BFHitResolver` / `BFHurtTarget` / `BFHitResolveResult` / `BFDamageContext` 的定义。
- 不改 `deliverTo` 的任何行为与 Javadoc 契约；`deliverTo` 仍不路由。
- 不改 `BFHurtInterceptor` 的协议外路径（情况 1-5 与命中结果缓存均不动）。
- 不改穿甲管线的分支编号（0/1/2/3）——新增分支记为 **1.5**，即"排在 1 之后、2 之前"，不重排既有编号。
- 不为"解析失败"补原版回退：协议伤害没有可回退的原版语义（详见 §五 Step 1 的失败语义）。

## 四、与 `deliverTo` 的分工（本计划重新表述的不变量）

| 入口 | 是否解析路由 | 理由 |
| --- | --- | --- |
| `hurt(Object, BFDamageContext)` | **是**（仅当目标是纯解析器时） | 发起的语义是"把这次伤害交给它应该落到的目标"。解析器已经声明"我知道该落到谁"，不咨询它就只能把伤害错落到解析器自身 |
| `deliverTo(Entity, BFDamageContext[, boolean])` | **否** | 投递的语义是"这次结算结果由这个承载实体落地"。承载者已在上一趟承担过解析职责，再路由一次就会回到触发它的那个零件，形成递归 |

这条分工替换掉 `docs/BFDamageApi-deliverTo投递计划.md` §2.2 中"`hurt` 的方法体内不调用 `resolveHit` / `resolveHitTarget`"的绝对表述；该文件其余结论（投递不做穿甲判定、不跑本体层、不修正数值）不受影响，其 §2.3 "路由一旦执行就会回到触发它的零件"针对的是 `deliverTo`，仍然成立。

## 五、详细步骤

### Step 1：新增分支 1.5 与响亮失败

**文件**：`api/BFDamageApi.java`

在既有的 `BFHurtTarget` 分支（分支 1）之后、护甲适配器分支（分支 2）之前插入：

```java
// 分支1.5：纯解析器——实现 BFHitResolver 但不实现 BFHurtTarget
//   解析出的实际目标必然实现 BFHurtTarget，转发后由分支1 承接，递归深度恒为 1
if (target instanceof BFHitResolver resolver) {
    BFHitResolveResult resolved = resolver.resolveHit(
            ctx.hitPoint(), BFHitResolver.searchDelta(ctx.hitVelocity()));
    // 解析判定为未命中：本次协议调用不造成伤害（与拦截器情况 3 的假阳性出口一致）
    if (resolved == null) return 0f;
    // 用解析结果重建上下文：应用修正几何、合并扩展数据
    return hurt(resolved.actualTarget(), contextForResolvedTarget(ctx, resolved));
}
```

新增共享的重建方法——`hurt` 的分支 1.5 与 `BFHurtInterceptor` 的情况 3 都调用它，保证"框架代劳解析"与"调用方自行解析"走同一套规则：

```java
public static BFDamageContext contextForResolvedTarget(BFDamageContext ctx, BFHitResolveResult resolved) {
    Vec3 normal = resolved.correctedHitNormal();
    // 零矢量是"未修正"哨兵：保留原法线，避免下游算出无意义的入射角
    Vec3 hitNormal = normal.lengthSqr() > 0.0 ? normal : ctx.hitNormal();
    // 以调用方容器为底拷贝，再并入解析器的容器（后者覆盖同名键）
    BFDamageExtensions extensions = ctx.extensions().copy();
    extensions.mergeFrom(resolved.extensions());
    return BFDamageContext.builder()
            .source(ctx.source())
            .baseDamage(ctx.baseDamage())
            .hitVelocity(ctx.hitVelocity())
            .hitPoint(resolved.correctedHitPoint())
            .hitNormal(hitNormal)
            .penetration(ctx.penetration())
            .extensions(extensions)
            .handler(ctx.getHandler())
            .build();
}
```

`BFDamageExtensions` 相应新增一个合并方法（容器的 `data` 是私有 map，外部无法逐项搬运）：

```java
public void mergeFrom(BFDamageExtensions other) {
    Objects.requireNonNull(other, "other");
    if (other == this) return;                 // 同一个实例：合并为空操作
    this.data.putAll(other.data);
}
```

末行改为响亮失败：

```java
// 分支4：既不是 BFHurtTarget、也不是 Entity——没有任何分支能承接这次伤害
LOGGER.error("BFDamageApi.hurt 的目标既不是 BFHurtTarget 也不是 Entity，本次伤害被丢弃："
        + "class={}，请让目标实现 BFHurtTarget，或先经 BFDamageApi.resolveHitTarget 解析出实际目标",
        target.getClass().getName());
return 0f;
```

要点：

- **判据是"不是 `BFHurtTarget` 的 `BFHitResolver`"**，靠分支顺序表达：`instanceof BFHitResolver` 写在分支 1 之后，走到这里的目标必然不满足分支 0/1。这与拦截器的优先级一致（§2.3）。
- **非 `Entity` 的纯解析器同样被接住**：分支 1.5 的位置在普通实体回退之前，因此"包装体形式的解析器"与"实体形式的解析器"走同一条路径，不再分别表现为"静默丢弃"与"错落自身"。
- **压栈仍在分支判定之前，转发调用在其上再压一帧**：`hurt` 先把目标压为栈顶，分支 1.5 解析后调用 `hurt(实际目标, ctx)`，那一帧被压成新的栈顶。转发期内栈自顶向下是 `实际目标 → 解析器`：`hasContextFor(实际目标)` 为 true（守卫正常放行），解析器的帧被压在下面、不参与栈顶匹配（判据即"栈顶是本实体"）。**不把压栈挪到分支 1.5 之后**——那样转发帧之下没有解析器的帧，`hasContextFor(解析器)` 在后续嵌套里会变成 false，解析器上的其它钩子就可能把它重新当成协议外伤害拦截。压栈先于分支判定保证每层协议调用都有对应的一帧。
- **`ctx` 按解析结果重建**：转发用的上下文不是原 `ctx`，而是由 `BFDamageApi.contextForResolvedTarget` 重建的新实例——命中点取 `correctedHitPoint()`（无条件替换）、命中面法线取 `correctedHitNormal()`（零矢量时保留原值）、扩展数据以原容器拷贝为底再并入解析结果的容器。source / baseDamage / hitVelocity / penetration / handler 原样保留。这与 `BFDamageApi.resolveHitTarget` 的 Javadoc 所载标准用法一致——框架代劳解析时不能省掉调用方本该做的那一步。**命中点必须替换**：护甲侧的 `BFArmorMaterial.mapHitToSlot` 用上下文命中点判定着弹槽位，而调用方传入的是代理 AABB 的交点，不是子部件上的真实着弹点。**法线判零回退**：`Vec3.ZERO` 是既有的"未修正"哨兵（`resolveHitTarget` 对纯 `BFHurtTarget` 即如此填充），直接写入会让下游算出无意义的入射角。**扩展合并方向**：调用方的容器为底、解析器的覆盖同名键；先拷贝保证不修改调用方传入的容器。
- **几何取上下文自带值**：`hitPoint` 用 `ctx.hitPoint()`（调用方在构造上下文时写入的真实命中点），搜索矢量用 `BFHitResolver.searchDelta(ctx.hitVelocity())` 而非裸 `hitVelocity`。理由与已知偏差见 §七.1。**注意这与"转发用哪份几何"是两件事**：解析的入参是"调用方给的代理 AABB 交点"，而返回的 `correctedHitPoint` 才是子部件上的真实着弹点；前者用于搜索，后者用于转发（见上一条）。
- **解析判定为未命中时静默返回 0f，不记日志**：这与拦截器情况 3 的假阳性出口同义（`cir.setReturnValue(false)`），是合法的业务结论，不是错误配置。`example/entity/ExampleProxyEntity.java#resolveHit` 在未装配零件时返回 null，即属此类。
- **失败语义的取舍**：解析失败时**不**回退到"伤害落在目标自身"。协议伤害由调用方显式发起，没有可回退的原版调用；把伤害落到解析器自身正是本计划要消除的行为。调用方若需要"解析失败也要落一份伤害"，应在自己的 `resolveHit` 里返回一个兜底目标。

**Javadoc 同步**：`hurt` 的方法 Javadoc 补一段"目标是纯解析器时先用上下文几何解析再转发、解析为未命中返回 0f"，以及"目标既不是 `BFHurtTarget` 也不是 `Entity` 时记录 error 后返回 0f"。类 Javadoc 的"两个伤害入口分工"不涉及分支枚举，但 `deliverTo` 的 Javadoc 有一句"不经过 `BFHitResolver` 的路由"，需就地补上与 `hurt` 的分工说明（不改变该条自身的行为）。

### Step 2：文档同步

本计划新增的规则需要在下列位置落地。**规则的定义在本文 §四与 Step 1，其余位置指向本文，不得另写一份副本。**

| 文件 | 改法 |
| --- | --- |
| `docs/BFDamageApi-deliverTo投递计划.md` | §2.2 第 156 行"协议管线本身不做路由……`hurt` 的方法体内不调用 `resolveHit` / `resolveHitTarget`"改写为本文 §四的分工表；`deliverTo` 自身不路由的结论保留。§2.3 的递归论证限定为 `deliverTo` |
| `docs/glossary.md` | `BFDamageApi` 条目"内部完成四路分支调度"改为五路，补分支 1.5 与响亮失败；`BFHitResolver` 条目补一句"协议入口对纯解析器会先解析再转发"（`BFHurtInterceptor` 条目描述的是 mixin 路径，未涉及协议入口，无需改动） |
| `docs/wiki/附录/A.1-API参考.md` | `hurt(Object, BFDamageContext)` 条目补分支 1.5 与响亮失败；`deliverTo` 条目的"不经过 `BFHitResolver` 路由"保持，但补一句"与 `hurt` 的分工见计划文档" |
| `docs/wiki/2-武器侧开发/2.6-代理实体与命中解析.md` | "没有命中前解析时的降级行为"一节：第二条"协议外伤害到达时，拦截器会自动执行一次解析"需补"协议入口对纯解析器同样会转发"；第一条与第三条的结论不变 |
| `docs/wiki/3-护甲侧开发/3.7-代理实体：实现BFHitResolver.md` | 代理形态表补一行"协议入口 `hurt(代理, ctx)`"的行；补 `resolveHit` 在协议入口收到的是 `ctx.hitPoint()` 与 `searchDelta(ctx.hitVelocity())` |
| `docs/wiki/3-护甲侧开发/3.5-协议外伤害兼容.md` | 说明分工边界：本章描述的是**原版伤害**的拦截路径（mixin 场景 1-5）；协议内伤害走 `hurt` 的分支 1.5，见本文 |
| `docs/wiki/4-协议内幕/4.1-穿甲判定管线.md` | 分支编号表补 1.5，并注明"编号顺序即判定顺序、既有编号不重排"；分支 1.5 小节写明转发上下文按解析结果重建（命中点替换、法线判零回退、扩展合并） |
| `docs/wiki/4-协议内幕/4.3-ThreadLocal与Mixin.md` | 压栈小节补一段：压栈仍在分支判定之前，转发调用在其上再压一帧，因此转发期内栈自顶向下是"实际目标 → 解析器"；并写明不能把压栈挪到分支 1.5 之后 |
| `docs/GameTest自动化指南.md` | 场景 9 的"管线路径"列（第 402 行"协议入口不做路由"）按新契约改写；补 Step 3 新增场景的条目；分支说明图补 1.5 与 4 |
| `docs/wiki/1-快速上手/1.6-核心API速览.md` | `hurt` 条目补一句"目标是纯解析器时会先解析再转发、解析为未命中返回 0" |
| `docs/终点弹道设计文档.md` | §10.4 的 `hurt` 示例补分支 1.5 与分支 4，示例后补一段五路分支总览；§11.3 的伤害路径总表补"协议内伤害命中纯解析器"一行（编号取 4.5，避免重排既有 1-9），小节标题从"九种"改为"十种"。§十七 不另写 `hurt` 侧转发——该节持有 `BFHitResolver` 的设计意图，`hurt` 的实现形态归属 §10.4 |
| `docs/BFHitResolver-去实体化与命中转发计划.md` | 第 488 行"取用方只读取 `result.actualTarget()`，不消费 `result.extensions()`"改写：修正几何与扩展数据现在由转发方应用到转发上下文；§九.4 与 `internal/BFHitResolveCache.java` 的"不得读写该容器"改写为"缓存容器只作为 `mergeFrom` 的来源读取，不得写入"（写入会污染缓存期间的共享引用）。缓存本身的单次命中、取用即清空、身份校验三条约束不变 |
| `AGENTS.md` | "Pipeline branches in `BFDamageApi.hurt()`" 列表补分支 1.5，并订正分支 3 的说明（现文写"Plain Entity → vanilla `entity.hurt()` fallback"，实际该回退会再次经过 mixin，命中拦截器的场景 2/3/4） |

### Step 3：GameTest

**文件**：`example/gametest/BallisticsGameTest.java`

五个场景，复用既有的 `ExampleProxyEntity`、`TestPart` 与 `deliveryContext` 辅助方法。夹具为此扩展了两处能力：`ExampleProxyEntity#setHitCorrection` 让宿主声明 `resolveHit` 返回的修正几何，`TestPart#resolvePenetration` 记录本次进入管线时的上下文（`getLastContext`）以便断言几何与扩展。

| 场景 | 前置 | 断言 |
| --- | --- | --- |
| 协议入口转发到零件 | 宿主实现 `BFHitResolver`、装配 `TestPart`；**不**预先调用 `resolveHitTarget`，直接 `BFDamageApi.hurt(宿主, ctx)` | 零件 `hurt` 被调用一次且承受全部伤害；`resolveHit` 被调用一次；宿主生命值不变、宿主 `hurt` 未被触碰；返回值等于零件承受量 |
| 协议入口对未装配零件的宿主 | 宿主未装配零件；直接 `BFDamageApi.hurt(宿主, ctx)` | 返回 0f；宿主生命值不变；`hurt` 未被触碰 |
| 修正几何与扩展被应用 | 宿主报告 `correctedHitPoint = 入参命中点 + 偏移`、非零 `correctedHitNormal`，解析结果携带扩展容器；上下文本带调用方扩展字段 | 零件看到的命中点 = 入参命中点 + 偏移（不是入参命中点）；法线 = 修正法线；解析器的扩展键可读；调用方原有扩展字段未丢；调用方传入的容器仍可用 |
| 零修正法线回退 | 宿主只给命中点偏移，`correctedHitNormal = Vec3.ZERO` | 零件看到的法线 = 原上下文法线，且非零矢量 |
| 拦截器转发同样应用修正 | 对宿主施加原版近战伤害（经 mixin → 拦截器 → 情况 3） | 以"零修正"为对照取入参命中点，实验组 = 对照组 + 偏移；法线为修正法线；宿主不掉血 |

场景 2 同时钉住"解析失败不回退到目标自身"这条取舍（§五 Step 1）。场景 3 的命中点断言是关键：若转发沿用入参 `ctx`，零件看到的会是代理 AABB 的交点而非子部件着弹点——护甲侧的 `mapHitToSlot` 正是拿这个值定槽位。

### Step 4：构建与测试

```bash
./gradlew build
./gradlew runGameTestServer
```

场景计数从 19 增加到 24，`AGENTS.md` 与 `docs/GameTest自动化指南.md` 的计数需同步。

## 六、对既有调用方的影响

| 调用方 | 调用形式 | 结论 |
| --- | --- | --- |
| 框架内武器模组（先 `resolveHitTarget` 再 `hurt(实际目标)`） | `hurt(resolved.actualTarget(), ctx)` | 不受影响：实际目标是 `BFHurtTarget`，仍走分支 1 |
| 框架内武器模组（直接 `hurt(代理, ctx)`） | `hurt(proxy, ctx)` | **行为变更**：由"伤害落在代理自身"改为"转发给解析出的实际目标"。这正是本计划的目的 |
| 非 `Entity` 包装体目标 | `hurt(wrapper, ctx)` | **行为变更**：由"静默返回 0f"改为"先解析转发；无解析能力时记 error 并返回 0f" |
| 穿戴 `BFArmorMaterial` 护甲的 `LivingEntity` 解析器 | `hurt(proxy, ctx)` | **行为变更**：分支 1.5 排在护甲分支之前，因此转发给实际目标；代理自身的协议护甲不再参与。与拦截器情况 3 的既有取舍一致（护甲语义由实际目标承担） |
| `deliverTo` | `deliverTo(carrier, ctx)` | 不变（§四） |
| 既有 8 个管线 GameTest + 11 个投递 GameTest | —— | 不变。投递场景经 `deliverTo`，第一趟场景已自行 `resolveHitTarget` 后把伤害交给零件（`example/gametest/BallisticsGameTest.java#testDeliverFirstPassRoutesToPart`），不经过分支 1.5 |

## 七、已知偏差与登记事项

1. **`hitVelocity` 的单位与 `searchDelta` 的标定不一致。** `BFDamageContext#hitVelocity` 的约定是 m/s（见 `AGENTS.md` 的 SI 约定），而 `BFHitResolver.searchDelta` 的标定面向 m/tick——其 Javadoc 写"速率以 m/tick 计入时该距离约等于两个游戏刻的位移"。把 m/s 的值直接交给它，搜索段会被放大（速率钳制在 [0.5, 4.0]、取两倍，因此距离恒在 1.0-8.0 m，不会无界）。后果是物理射线型解析器的搜索段比预期长，判定偏宽松；`example/entity/ExampleProxyEntity.java#resolveHit` 忽略 `delta`，不受影响。取舍：改用 `searchDelta` 而非裸 `hitVelocity`，是为了保留"下限 1.0 m"的有界保证与"上限 8.0 m"的封顶；裸传会让 200 m/s 的弹丸问出一段 200 m 的搜索段。若后续把该单位对齐，属于 `BFHitResolver` 契约的改动，不在本计划范围内。
2. **解析失败不回退原版**：见 §五 Step 1 的"失败语义的取舍"。
3. **代理不应承载生命值**：该约束由 `docs/BFHitResolver-去实体化与命中转发计划.md` §七.5 持有，本计划不重复。分支 1.5 消除了"协议伤害落在代理自身"这一条，但拦截器路径的若干"放行原版"出口仍在，约束继续成立。
4. **`docs/wiki/4-协议内幕/4.1-穿甲判定管线.md` 的分支1 示例与源码有一处既有落差**（`boolean success = finalDmg > 0f && tb.hurt(...)`，源码无 `finalDmg > 0f` 前置守卫）。这是编辑该文件时发现的既有不一致，与分支 1.5 无关，本计划不改，仅登记以免被当成新引入的偏差。

## 八、验证

| 场景 | 期望 |
| --- | --- |
| 直接 `hurt(纯解析器, ctx)`，解析成功 | 转发到实际目标；返回值等于实际目标承受量；解析器自身不掉血、`hurt` 未被调用 |
| 同上，且解析结果带修正几何 | 实际目标看到的命中点是 `correctedHitPoint`，法线是 `correctedHitNormal`；调用方传入的 `ctx` 本身不被修改 |
| 同上，且 `correctedHitNormal` 为零矢量 | 实际目标看到原上下文的法线（非零） |
| 同上，且解析结果带扩展容器 | 解析器的扩展键可读，调用方原有扩展字段仍在；合并方向为"解析器覆盖同名键" |
| 同上，且解析结果与调用方共用同一个扩展容器实例 | `mergeFrom` 为空操作，不抛异常、不丢数据 |
| 拦截器情况 3 转发（原版伤害） | 与分支 1.5 同一套重建规则：修正几何被应用，`createContextFromVanilla` 给出的默认几何被覆盖 |
| 直接 `hurt(纯解析器, ctx)`，`resolveHit` 返回 null | 返回 0f；无伤害、无日志（合法业务结论） |
| `resolveHit` 返回的 `actualTarget` 不是 `Entity` | 由分支 1 承接（`BFHurtTarget` 分支不要求实体），正常结算 |
| 目标同时实现 `BFHitResolver` 与 `BFHurtTarget` | 走分支 1，不解析、不转发（与拦截器优先级一致） |
| 目标是非 `Entity` 的包装体、两个接口都不实现 | 记录 error 日志并返回 0f（不再无声丢弃） |
| 目标是普通 `Entity`、两个接口都不实现 | 走普通实体回退；不触发 error 日志 |
| 解析器是 `LivingEntity` 且穿戴 `BFArmorMaterial` 护甲 | 走分支 1.5，转发给实际目标；代理自身护甲不参与 |
| 转发链路内实际目标的 `hurt` | `hasContextFor(实际目标)` 为真（转发调用的 `hurt` 已压栈），代理不在栈顶 |
| `hurt(纯解析器, ctx)` 与随后 `deliverTo(纯解析器, ctx)` | 前者转发到实际目标；后者按承载者投递落地在解析器自身（两者分工见 §四） |
| `ctx.hitVelocity()` 为零矢量 | `searchDelta` 返回 `Vec3.ZERO`，以点查询调用 `resolveHit`；解析器的结论决定去向，不抛异常 |

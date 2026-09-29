# BFHitResolver 去实体化、代理命中转发与命中结果缓存计划

> 关联文档：
>
> - [BFHitResolver-实现计划.md](./BFHitResolver-实现计划.md) —— P1 阶段：`BFHitResolver` 接口、`BFHitResolveResult` record、`BFDamageApi.resolveHitTarget` 与 `ProjectileHitResolverMixin` 的落地记录
> - [终点弹道设计文档.md](./终点弹道设计文档.md) —— §十七 BFHitResolver（设计意图）
> - wiki：[2.6-代理实体与命中解析](./wiki/2-武器侧开发/2.6-代理实体与命中解析.md)、[3.7-代理实体：实现 BFHitResolver](./wiki/3-护甲侧开发/3.7-代理实体：实现BFHitResolver.md)
>
> 本文件描述的是**待实施**的改动，文中的方法签名以改动完成后的形态给出。
>
> 审阅修订记录（按审阅意见就地更新，未改动 Step 1-4 的技术方案）：
>
> - §2.1 补"同时实现两接口且 `createContextFromVanilla` 返回 null"时的分支归属（有意变更，与 `3.5` 现有描述不同）
> - §2.4 保留并加固原文论证：已对照 NeoForge 21.1.219 反编译源码确认 `AbstractArrow` 是**先 `onHit`／`hurt`、后 `setPos`**，故 `hurt` 阶段的位置最多落后一个 tick 的位移；补充 `DamageSource.java:120-126`、`AbstractArrow.java:214-216`/`:300`、`Projectile.java:191` 的行号依据；并把回落失败的后果从"精度下降"改为"伤害落到代理自身"
> - §三 非目标澄清：§七.4 复述的是 `BFHurtTarget#getBFEntity()` 的既有要求，不算改接口；新增"代理不应承载生命值"
> - §四 文件清单补 `docs/BFHitResolver-实现计划.md`；`docs/` 各条标注需改写的具体矛盾点；GameTest 降级为可选
> - §五 Step 1 爆炸分支补"自定义爆炸类型落到第 4 行、行为一致"与 `getSourcePosition()` 待确认说明；Step 3.1 补"接口与 record 必须 `public`"及其理由；Step 4 把 `cir.setReturnValue` 改为 **`ctx != null` 即接管**
> - §五 Step 5 把"三处措辞落差"扩为"必须改写的措辞落差"表（含 `4.3:241-243` 整节、`A.1:136` 的 `forRemoval`、`终点弹道设计文档.md` §17.4/§17.5）
> - §七 新增第 5 条；§八 补 4 行语义用例；§九.1 改为"已核实"并附反编译依据，§九.3/§九.4 修正后果描述
>
> 核实方式：上述结论取自本机 Gradle 缓存中 NeoForge 21.1.219 的反编译源码包（`neoformruntime/intermediate_results` 的 sources zip），未修改该缓存；仅在系统临时目录解出 6 个 `.java` 文件用于阅读，核对后已删除。
>
> 未在本次修订中处理、留给实施者判断的一项：情况 2/4 现有的 `cir.setReturnValue(dealt > 0f)` 与 `docs/终点弹道设计文档.md` §11.3 路径 5/7 的"ctx ≠ null 即接管"之间的落差（Step 4 已注明）。

## 一、概述

三项改动：

1. **去实体化** —— 命中解析的入口从 `Entity` 放宽到任意命中对象，使物理体属主、包装体等非实体对象也能参与解析。
2. **代理命中转发** —— 协议外（原版）伤害命中代理对象时，把伤害交给解析出的实际目标：代理只需实现 `BFHitResolver`，不必承载 `BFHurtTarget`。
3. **命中结果缓存** —— 投射物在 `onHit` 阶段把当次命中的解析结果（`BFHitResolveResult`）随同命中实体暂存到投射物自身，`hurt` 阶段直接取用。投射物命中因此通常只解析一次，`hurt` 阶段不再重建几何（少数取不到缓存的来源仍会回落解析一次，见 §2.4 与 Step 1 的幂等契约）。

改动集中在 `BFDamageApi`、`BFHitResolver`、`BFHurtInterceptor` 与 `ProjectileHitResolverMixin` 四处，另新增内部接口 `internal/BFHitResolveCache`（缓存载体契约，必须 `public`）。不触碰 `BFHurtTarget` 的接口定义、`BFDamageContext`、`BFDamageExtensions` 与穿甲管线。

**本计划不把测试列为交付项**：Step 1-4 与 §八 的验证可由下游首次接入时按 §七 自查代替；若要补 GameTest，需先加一个示例代理实体，见 §八 末。

## 二、现状锚点

### 2.1 拦截器的五种判定情况

`BFHurtInterceptor.intercept(Entity self, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir)` 是 `EntityHurtMixin`（注入 `Entity#hurt` 的 HEAD）与 `LivingEntityHurtMixin`（注入 `LivingEntity#hurt` 的 HEAD）共用的判定函数。两个 Mixin 必须各自注入，因为 JVM 的方法分派只会命中一层覆写。

它回答的问题是：**伤害到达 `self` 的那一刻，这条路该由谁走。** 当前实现按顺序判定四种情况，本计划插入一种，成为五种：

| # | 判据 | 谁承担受击语义 | 处理 |
| --- | --- | --- | --- |
| 1 | `BFDamageApi.hasContextFor(self)` | —— | 已在同一目标的协议管线内，直接放行（重入守卫，防止递归） |
| 2 | `self instanceof BFHurtTarget` | `self` 自身 | 经 `createContextFromVanilla` 转入协议管线；返回 null 则放行原版 |
| 3 | `self instanceof BFHitResolver` | `resolveHit` 解析出的**实际目标** | **本次新增**：投射物来源直接取用 `onHit` 阶段已完成的解析结果，其余来源由 `searchRay` 按伤害来源构造几何后解析；解析结果的实际目标承接伤害。假阳性时返回 false 不造成伤害；几何无法构造、解析回自身、或实际目标不接受协议外伤害时放行原版 |
| 4 | `self instanceof LivingEntity && BFArmorAdapter.hasBFArmor(living)` | 穿戴的**护甲物品**（经 `BFArmorAdapter` 适配为 `BFHurtTarget`） | 经适配器转入协议管线 |
| 5 | 以上皆不满足 | —— | 放行原版流程 |

新情况 3 补上的空白是"`self` 不是受击者，但知道受击者是谁"——它必须排在情况 2 之后、情况 4 之前。

**同时实现两个接口时的分支归属（有意变更）**：情况 2 的 `createContextFromVanilla` 返回非 null 时，行为完全不变。若它返回 **null**，对象会继续走到情况 3：此时由 `resolveHit` 决定归属，解析回自身才放行原版。这与现有文档（`wiki/3-护甲侧开发/3.5-协议外伤害兼容.md` 第三步的描述"继续下一步"）不同——差别在于，本计划不会让这类对象再落到情况 4。理由是"实现 `BFHitResolver` 就等于声明自己可以回答打中了谁"，若同时声明了 `BFHurtTarget` 却对该类伤害主动弃权，则解析结果比"自身的护甲语义"更权威。Step 5 需同步改写 3.5 的措辞。

**情况 4 的适用范围**：仅实现 `BFHitResolver` 的 `LivingEntity` 代理，其**自身**穿戴的协议护甲不参与本次判定——护甲语义由 `resolveHit` 返回的实际目标承担（`BFDamageApi.hurt` 内部会按其自身身份重新判定情况 2/4 的归属）。

### 2.2 解析入口与实体绑定

| 位置 | 现状 |
| --- | --- |
| `api/BFDamageApi.java:274` | `isProtocolAware(Entity entity)` —— 参数为 `Entity`，判据是 `instanceof BFHitResolver \|\| instanceof BFHurtTarget` |
| `api/BFDamageApi.java:307` | `resolveHitTarget(Entity hitEntity, Vec3 hitPoint, Vec3 delta)` —— 命中对象必须是实体 |
| `api/BFDamageApi.java:330` | `resolveHitTarget(HitResult, Vec3)` —— 从 `EntityHitResult` 解包，非实体命中直接返回 null |
| `mixin/ProjectileHitResolverMixin.java:32-33` | 注入条件是 `result instanceof EntityHitResult` 且其实体实现 `BFHitResolver` |

物理体属主（`PhysicsHost`）、包装体等非实体命中对象无法进入这条链路。

### 2.3 解析成功时不施加伤害

`ProjectileHitResolverMixin` 只处理"假阳性"分支：`resolveHit` 返回 null 时 `ci.cancel()`，让投射物继续飞行（`mixin/ProjectileHitResolverMixin.java:45-48`）。解析成功时放行原版流程，伤害由随后的原版 `entity.hurt(proxy)` 承担，落到 `BFHurtInterceptor` 的情况 2——而该分支要求 **proxy 自身实现 `BFHurtTarget`**（`internal/BFHurtInterceptor.java:42-49`）。

于是代理必须同时实现两个接口：`BFHitResolver` 回答"打中了谁"，`BFHurtTarget` 才能承接伤害。即便护甲与血量语义实际属于代理所引用的那个目标，代理仍要再实现一遍转发。

### 2.4 同一命中的两个阶段

一次投射物命中会依次经过两个阶段：

1. `ProjectileHitResolverMixin` 在 `Projectile#onHit` 的 HEAD 阶段调一次 `resolveHit`，用于判断假阳性并决定投射物是否继续飞行；
2. `BFHurtInterceptor` 在 `Entity#hurt` 的 HEAD 阶段需要知道这次命中归属哪个实际目标，用于决定伤害去向。

两个阶段各自能拿到的信息并不对等（以下结论已对照 NeoForge 21.1.219 反编译源码核实）：

- `onHit` 阶段持有 `EntityHitResult`：命中点是射线与 AABB 的精确交点，搜索矢量取自投射物自身的 `getDeltaMovement()`。
- `hurt` 阶段只持有 `DamageSource`：`DamageSource#getSourcePosition()` 在伤害源未显式记录位置时回退为 `getDirectEntity().position()`（`DamageSource.java:120-126`，无 direct entity 时返回 null）。对投射物伤害源，direct entity 就是该投射物，因此拿到的是**投射物当前坐标**。

关键在于这个"当前坐标"还不是命中点。以 `AbstractArrow` 为例，一个 `tick` 内的顺序是：

```text
tick() 开始
  vec32 = position()            // 本 tick 的起点（尚未移动）
  vec33 = vec32 + deltaMovement
  clip / findHitEntity(vec32, vec33)   // 用"起点→终点"的线段做碰撞检测
  hitTargetOrDeflectSelf → onHit → onHitEntity → entity.hurt(...)   ← 缓存的写入点与 hurt 都在这里
  ...
  setPos(d7, d2, d3)            // 位置在这里才更新到本 tick 的终点（AbstractArrow.java:300）
```

也就是说，`hurt` 阶段取到的位置是**本 tick 移动之前**的值，与精确交点相差最多一个 tick 的位移（`AbstractArrow.java:214-216` 用 `position()` 作线段起点，`:300` 才 `setPos`；`Projectile.java:191` 的 `onHit` 调用在位置更新之前）。`ThrowableProjectile` 等自行 `setPos` 的投射物同理，且若伤害延迟到更晚结算，偏差还会更大。

因此：

1. `hurt` 阶段无法自行还原出 `onHit` 阶段那份精确几何；
2. 若照此重建后再解析一次，同一个命中可能得到相反结论（`onHit` 判真命中、`hurt` 判未命中），而 `hurt` 阶段的结论会直接把伤害吞掉；
3. 更严重的是，回落路径（未取得缓存、由 `searchRay` 重建几何）判定失败时**不会中止流程**——`resolved == null` 会让原版 `hurt` 继续执行（见 §五 Step 4 的 `cir.setReturnValue` 语义）。因此回落几何的误差不是"精度下降"，而是"伤害落到代理自身"。

这是本计划必须处理的核心约束——**情况 3 的难点不是"参数放宽到 `Object`"，而是"`hurt` 阶段不能重新判定一次"**。§五 Step 3 的处理办法是不重建、也不重判：`onHit` 阶段把**已经完成的解析结果**暂存到投射物，`hurt` 阶段直接取用。每次投射物命中因此只解析一次，只要缓存写入覆盖率足够，回落路径就只是极端情况下的兜底。

## 三、目标与非目标

**目标**

- G1 `isProtocolAware` / `resolveHitTarget` 接受任意命中对象（实体、物理体属主、包装体均可）。
- G2 协议外伤害命中代理时，转发到 `resolveHit` 解析出的实际目标；代理只需实现 `BFHitResolver`。
- G3 既有调用方（框架内外）无需改动，语义不变。
- G4 投射物类伤害的两个阶段共用一份判定结果，同一命中只解析一次。

**非目标**

- 不改 `BFHurtTarget` 的接口定义与既有实现者——**唯一的例外是 §七.4**：那一条复述的是 `BFHurtTarget#getBFEntity()` 已写在接口 Javadoc 里的既有要求（"委托实体 `hurt()` 就必须返回该实体"），非实体实际目标必须照做，否则情况 3 的重入守卫失效。
- 不改 `BFDamageContext` / `BFDamageExtensions` / 穿甲管线分支。
- 不改 `BFHitResolveResult` 的字段。
- 缓存只绑定单次命中事件：不跨命中事件复用，也不得作为后续命中事件的判据（穿透投射物在同一 tick 内会连续产生多次命中，约束见 §五 Step 4）。
- 不改下游模组代码。本计划的交付物是框架侧；下游侧需要同步补做的前置条件集中记在 §七。

## 四、文件清单

路径前缀：`src/main/java/io/github/sweetzonzi/ballistics_framework/`

| 文件 | 操作 | 说明 |
| --- | --- | --- |
| `api/BFHitResolver.java` | 修改 | 类 Javadoc 去实体化；新增静态方法 `searchDelta(Vec3)` 与 `searchRay(Entity, DamageSource)`；接口契约补"`resolveHit` 须幂等" |
| `api/BFDamageApi.java` | 修改 | `isProtocolAware` / `resolveHitTarget` 参数放宽到 `Object`；两者的实体版重载一并提供 `@Deprecated` 转发（保二进制兼容） |
| `internal/BFHitResolveCache.java` | 新增 | 投射物命中结果缓存的载体接口与缓存记录类型。接口与 `CachedResolve` **必须是 `public`**（理由见 §五 Step 3.1） |
| `internal/BFHurtInterceptor.java` | 修改 | 新增情况 3：代理解析 + 协议外伤害转发；投射物来源直接取用缓存的解析结果 |
| `mixin/ProjectileHitResolverMixin.java` | 修改 | 实现 `BFHitResolveCache`；`onHit` 阶段写入解析结果；搜索矢量改用 `BFHitResolver.searchDelta` |
| `example/gametest/BallisticsGameTest.java` | **可选** | 四项场景（情况 3 转发、假阳性不销毁、缓存一致性、穿透多目标）。本计划不把测试列为交付项，见 §八；若要落成测试，另需一个实现 `BFHitResolver` 的示例代理实体及其在 `ExampleContent` 中的注册（见 §八 末） |
| `AGENTS.md`（仓库根） | **可选** | 仅在新增 GameTest 场景时同步场景计数 |
| `docs/wiki/1-快速上手/1.6-核心API速览.md` | 核对 | `resolveHitTarget` 两个签名条目 |
| `docs/wiki/2-武器侧开发/2.6-代理实体与命中解析.md` | 核对 + 微调 | 去实体化说明与重载取舍表；`:98` 小节标题"两个重载的取舍"与其下 3 行表格不一致，一并修正；`:217` "单次命中最多触发两次解析"需按 G4 改写 |
| `docs/wiki/3-护甲侧开发/3.5-协议外伤害兼容.md` | 核对 + 改写 | 拦截步骤的五步划分；第三步"继续下一步"的措辞与 §2.1 的有意变更冲突，需改写 |
| `docs/wiki/3-护甲侧开发/3.7-代理实体：实现BFHitResolver.md` | 核对 + 补写 | 去实体化、转发语义、`resolveHit` 幂等要求；补结果复用与缓存边界；`:110`、`:188` 的"最多被调用两次"需改写 |
| `docs/wiki/4-协议内幕/4.3-ThreadLocal与Mixin.md` | 核对 + 补写 | 拦截情况表；Mixin 代码与 `searchDelta`、结果写入对齐；`:241-243` "resolveHit 会被调用两次"整节与 G4 冲突，需改写；`:109` 示例首参需加 `(Object)` 转换 |
| `docs/wiki/附录/A.1-API参考.md` | 核对 + 补写 | 方法签名清单；补 `isProtocolAware(Entity)` 重载与 `internal/BFHitResolveCache`；`:136` 的 `forRemoval = true` 需去掉（与 Step 2 取舍一致）；`:438-439` 的"可能被调用两次"需按 G4 改写 |
| `docs/终点弹道设计文档.md` | 核对 + 补写 | §十一 拦截情况表、§17 签名与三层体系；补缓存优先与 `searchRay` 的爆炸分派；§17.4/§17.5 的旧签名与旧调用示例需修正（见 Step 5）；`:1801-1802` 的"最多被调用两次"需改写 |
| `docs/BFHitResolver-实现计划.md` | 补写横幅 | P1 落地记录，其签名部分已被本计划取代但正文仍留旧签名（`:66`、`:172-173`、`:190`、`:220`）与失配的 Mixin 示例（`:337-347`）。加一段"签名已过时，以去实体化计划为准"的说明或删除相关段落 |

`docs/` 下各文件已包含去实体化与代理转发相关的描述，Step 5 的工作是逐条核对，并把本计划新增的缓存内容补进去。

## 五、详细步骤

### Step 1：`BFHitResolver` 去实体化、共用搜索几何与幂等契约

**文件**：`api/BFHitResolver.java`

类 Javadoc 中"由代理实体实现"的表述放宽为"由代理对象实现（实体、物理体属主或其他包装体）"，并说明解析结果既可用于框架内攻击者，也可用于协议外伤害转发。

`resolveHit` 的签名与语义不变，但契约中补一条硬性要求：

> `resolveHit` 必须是幂等且无副作用的纯查询。投射物命中通常只解析一次（`onHit` 阶段的结果经缓存传给 `hurt` 阶段，见 §2.4）；但**当伤害来源的 direct entity 不是那个投射物时**（伤害延迟结算、反射弹、非投射物来源在同一 tick 内先打中代理），`hurt` 阶段取不到那份缓存，会按伤害来源重新解析一次——同一次命中因此仍可能被调用两次。实现不得依赖调用次数，也不得在解析过程中修改自身状态；**同一组 `(hitPoint, delta)` 必须始终返回同一结果**，否则两阶段的相反结论会让投射物在"继续飞行"与"销毁"之间反复（`ci.cancel()` 与后续 `onHit` 的交替）。

新增两个静态方法，把"由伤害来源导出搜索几何"的规则收敛到一处：

```java
/**
 * 由攻击者的速度矢量导出解析搜索矢量。
 * <p>
 * 规则：速率钳制在 [0.5, 4.0] 后取两倍位移，即搜索距离上限落在 [1.0, 8.0] m，
 * 方向与速度同向。用于把"伤害来源报告的位置"扩展成一段可用于精确复检的线段。
 * <p>
 * 速率低于 0.001 或为非有限值时返回 {@link Vec3#ZERO}；调用方应对零矢量做早退处理，
 * 避免以零长度线段发起解析。
 *
 * @param velocity 攻击者速度矢量
 * @return 搜索矢量；速率为零或无效时返回 {@link Vec3#ZERO}
 */
static Vec3 searchDelta(Vec3 velocity) {
    double speed = velocity.length();
    if (!(speed >= 0.001)) return Vec3.ZERO;          // 同时排除 NaN
    if (!Double.isFinite(speed)) return Vec3.ZERO;
    double clamped = Math.clamp(speed, 0.5, 4.0);
    return velocity.scale(clamped * 2.0 / speed);
}

/**
 * 由协议外伤害来源导出解析搜索几何。
 * <p>
 * "搜索矢量"约定为 {@code hitPoint.add(delta)} 即搜索终点，因此本方法返回的
 * 第二分量是搜索方向与搜索距离的合体：方向为搜索方向，模为搜索距离（m）。
 * <p>
 * 按伤害类别分派，顺序即优先级：
 * <ol>
 *   <li><b>爆炸</b>（{@code EXPLOSION} / {@code PLAYER_EXPLOSION}）：起点取来源位置，
 *       方向指向 {@code self}，长度取两点距离。爆炸的 direct entity 可能是活体
 *       （如苦力怕）、TNT 或火球，按 direct entity 类型分派会把它分别误判为
 *       "活体近战"或"投射物飞行方向"，故本类必须先于后两类判定。
 *       本行只匹配这两个原版伤害类型；模组自定义的爆炸类型不会命中本行，
 *       但会落到第 4 行（同样是以来源位置指向 {@code self} 的径向几何），行为一致。</li>
 *   <li><b>投射物</b>：起点取投射物坐标，方向取投射物自身速度。仅当调用方
 *       未持有该投射物的命中结果缓存时才会走到这里。</li>
 *   <li><b>活体近战</b>：起点取攻击者眼位，方向取攻击者视线，长度固定 5.0 m。</li>
 *   <li><b>其他有源位置伤害</b>：起点取来源位置，方向指向 {@code self}，长度取两点距离。</li>
 *   <li><b>无源位置伤害</b>（虚空、饥饿、{@code /kill} 等）：返回 null，调用方应直接放行原版流程。</li>
 * </ol>
 * <p>
 * 本方法依赖 {@code DamageSource#getSourcePosition()}（返回显式记录位置，否则取
 * {@code getDirectEntity().position()}，无 direct entity 时为 null——见 §九.1 已核实的实现）。
 *
 * @param self   被命中的代理对象
 * @param source 原版伤害来源
 * @return 长度为 2 的数组 {@code [hitPoint, delta]}；无法构造几何时返回 null
 */
@Nullable
static Vec3[] searchRay(Entity self, DamageSource source) {
    // 爆炸优先：其 direct entity 类型不固定（苦力怕 / TNT / 火球），
    // 落到下面两个分支会分别得到"视线方向"或"飞行方向"这类错误几何
    if (source.is(DamageTypes.EXPLOSION) || source.is(DamageTypes.PLAYER_EXPLOSION)) {
        return radialRay(self, source.getSourcePosition());
    }
    if (source.getDirectEntity() instanceof Projectile projectile) {
        Vec3 from = projectile.position();
        Vec3 delta = searchDelta(projectile.getDeltaMovement());
        return delta.equals(Vec3.ZERO) ? null : new Vec3[]{from, delta};
    }
    if (source.getDirectEntity() instanceof LivingEntity attacker) {
        Vec3 from = attacker.getEyePosition();
        return new Vec3[]{from, attacker.getViewVector(1.0f).scale(5.0)};
    }
    return radialRay(self, source.getSourcePosition());
}

/**
 * 由来源位置指向 {@code self} 的径向搜索几何。
 *
 * @param self 被命中的代理对象
 * @param from 来源位置；为 null 表示该伤害没有可用的几何来源
 * @return 长度为 2 的数组 {@code [hitPoint, delta]}；来源位置缺失或与 {@code self} 重合时返回 null
 */
@Nullable
private static Vec3[] radialRay(Entity self, @Nullable Vec3 from) {
    if (from == null) return null;
    Vec3 toSelf = self.position().subtract(from);
    double distance = toSelf.length();
    if (!(distance >= 0.001)) return null;
    return new Vec3[]{from, toSelf};
}
```

投射物分支的起点取 `projectile.position()`：`DamageSource#getSourcePosition()` 在未显式记录位置时回退为 `getDirectEntity().position()`，对投射物伤害源二者恒等，无需另设回退。`private static` 接口方法是 Java 9 起允许的形态。

按伤害类型判定爆炸的另一个收益：TNT、活体引爆、火球爆炸三种来源都会落到同一条径向分支，行为一致（见 §八 爆炸一行）。

搜索距离的推导依据（供实现者理解量级）：`speed` 为 0.5 时得 1.0 m，为 4.0 及以上时得 8.0 m。对投射物，速度以 m/tick 计入时该距离约等于两个游戏刻的位移；框架内调用方（Machine-Max 侧）传入的是 m/s 除以 20 后的值，与 `MinecraftTrajectory` 的单位约定一致。

调用方判定零矢量用 `delta.equals(Vec3.ZERO)` 或 `delta.lengthSqr() == 0` 均可：零/无效速率下 `searchDelta` 返回的就是 `Vec3.ZERO` 常量本身，两种写法结论一致。

### Step 2：`BFDamageApi` 参数放宽

**文件**：`api/BFDamageApi.java`

```java
/**
 * 判断命中对象是否具有协议感知能力。
 * <p>
 * 对象实现 {@link BFHitResolver} 或 {@link BFHurtTarget} 时返回 true。
 *
 * @param hit 命中对象（实体、物理体属主或其他包装体）
 * @return true 表示可走协议解析
 */
public static boolean isProtocolAware(Object hit) {
    return hit instanceof BFHitResolver || hit instanceof BFHurtTarget;
}

/**
 * 解析命中目标。
 * <p>
 * 若命中对象实现了 {@link BFHitResolver}，执行精确验证并返回修正后的目标与几何；
 * 否则若其自身是 {@link BFHurtTarget}，直接包装返回；两者都不是时返回 null。
 *
 * @param hit      命中对象（实体、物理体属主或其他包装体）
 * @param hitPoint 报告的命中点
 * @param delta    搜索矢量，其模为搜索距离上限（m），方向为命中方向
 * @return 解析结果；{@code null} 表示未命中
 */
@Nullable
public static BFHitResolveResult resolveHitTarget(Object hit, Vec3 hitPoint, Vec3 delta) {
    if (hit instanceof BFHitResolver resolver) {
        return resolver.resolveHit(hitPoint, delta);
    }
    if (hit instanceof BFHurtTarget bf) {
        return new BFHitResolveResult(bf, hitPoint, Vec3.ZERO);
    }
    return null;
}

/** {@link #isProtocolAware(Object)} 的实体版重载。 */
@Deprecated(since = "1.0.0.alpha.11")
public static boolean isProtocolAware(Entity entity) {
    return isProtocolAware((Object) entity);
}

/**
 * {@link #resolveHitTarget(Object, Vec3, Vec3)} 的实体版重载。
 *
 * @deprecated 请改用 {@link #resolveHitTarget(Object, Vec3, Vec3)}；本重载仅作转发。
 */
@Deprecated(since = "1.0.0.alpha.11")
@Nullable
public static BFHitResolveResult resolveHitTarget(Entity hitEntity, Vec3 hitPoint, Vec3 delta) {
    return resolveHitTarget((Object) hitEntity, hitPoint, delta);
}
```

这两个实体版重载与 `Object` 版并存，是为了**二进制兼容**：已编译的下游 mod 按 `(Lnet/minecraft/world/entity/Entity;)Z` 与 `(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lio/.../BFHitResolveResult;` 两个描述符查找方法，只提供 `Object` 版会让它们在运行时抛 `NoSuchMethodError`。两个重载都不带 `forRemoval`：它们承担的是长期存在的转发职责。

框架自身的调用点若静态类型是 `Entity`（例如 `BFHurtInterceptor` 情况 3 传入的 `self`、`HitResult` 重载内的 `ehr.getEntity()`），应显式写成 `resolveHitTarget((Object) x, ...)` 以免触发弃用告警。

`resolveHitTarget(HitResult, Vec3)` 的签名与实现形态不变（自动解包 `EntityHitResult` 后委托到 `Object` 版）。

`resolveHitTarget` 三个重载的选取由 Java 的重载解析决定：实参静态类型为 `Entity` 时选中实体版（最具体），为其他引用类型时选中 `Object` 版，为 `HitResult` 时选中两参数重载；`isProtocolAware` 的两个重载同理。

**Javadoc 同步**：实体版重载是 `{@link #resolveHitTarget(Object, Vec3, Vec3)}` 的转发入口，类内两处 `{@link #resolveHitTarget(Entity, Vec3, Vec3)}` 需更新为 `{@link #resolveHitTarget(Object, Vec3, Vec3)}`（`api/BFDamageApi.java:321`，以及 `api/BFHitResolver.java:19` 的 `@see`）。

### Step 3：投射物侧——缓存载体与几何写入

**文件**：`internal/BFHitResolveCache.java`（新增）、`mixin/ProjectileHitResolverMixin.java`

**3.1 缓存载体**

```java
package io.github.sweetzonzi.ballistics_framework.internal;

/**
 * 投射物命中结果缓存（内部实现）。
 *
 * 本接口存在于 internal 包中，但**必须是 public**：唯一的实现方
 * ProjectileHitResolverMixin 位于 mixin 包，它只能以 public 方法覆写接口方法，
 * 而接口方法不能比接口本身更可见。若把接口降为包私有，Mixin 的跨包接口应用
 * 会因可访问性不匹配而失败。public 只表达"能被 mixin 包看到"，
 * 不表达"属于公开 API"——外部模组不应引用本接口，理由与 internal 包内其他类一致。
 *
 * 由 {@code ProjectileHitResolverMixin} 在 {@link Projectile} 自身的字段上实现，
 * 供 {@code BFHurtInterceptor} 在 {@code hurt} 阶段取用。存在的理由见 §2.4：
 * {@code Projectile#onHit} 阶段持有精确几何、已经完成一次解析，而 {@code Entity#hurt}
 * 阶段未必能还原那份几何，重新解析可能得到相反结论、甚至把伤害送回代理自身。
 *
 * <b>复用范围限于单次命中事件。</b>写入发生在 {@code resolveHit} 判定为真命中之后，
 * 因此能取到缓存就说明这次命中已被判定通过，其中记录的解析结果对本次命中有效，
 * 可直接使用。缓存不跨命中事件：穿透投射物在同一 tick 内会连续产生多次命中，
 * 每次命中都必须有自己的判定结果。
 *
 * 缓存是一次性的：读取即清空，且仅在命中实体身份匹配时返回。
 *
 * 被缓存的 {@link BFHitResolveResult} 会连同其中的 {@code extensions} 容器一起
 * 存活到 {@code hurt} 阶段，取用方只能读 {@code actualTarget()}，
 * 不得读写该容器（理由见 §九.4）。
 */
public interface BFHitResolveCache {

    /** 一次命中的判定结果：被命中的实体 + 该次命中的解析结果 */
    public record CachedResolve(Entity hitEntity, BFHitResolveResult result) {}

    /**
     * 写入本次命中的解析结果。应在 {@code resolveHit} 判定为真命中之后、
     * 放行原版流程之前调用。
     *
     * @param hitEntity 原版报告的命中实体，即随后 {@code hurt} 的接收者
     * @param result    本次命中的解析结果
     */
    void bf$cacheResolve(Entity hitEntity, BFHitResolveResult result);

    /**
     * 取出并清空缓存。仅当缓存的命中实体与 {@code hitEntity} 身份相等时返回该记录，
     * 其余情况返回 null。无论命中与否都清空，缓存不可跨命中事件复用。
     *
     * @param hitEntity 当前正在承受伤害的实体
     * @return 本次命中的解析结果；无记录、或记录的命中实体不是 {@code hitEntity} 时返回 null
     */
    @Nullable
    CachedResolve bf$takeResolve(Entity hitEntity);
}
```

**3.2 Mixin 改动**

`ProjectileHitResolverMixin` 声明实现该接口并持有唯一字段；`onHit` 阶段的搜索矢量改用 `BFHitResolver.searchDelta`，判定为真命中后写入结果。

```java
@Mixin(Projectile.class)
public class ProjectileHitResolverMixin implements BFHitResolveCache {

    @Unique
    @Nullable
    private BFHitResolveCache.CachedResolve bf$cachedResolve;

    @Override
    public void bf$cacheResolve(Entity hitEntity, BFHitResolveResult result) {
        this.bf$cachedResolve = new BFHitResolveCache.CachedResolve(hitEntity, result);
    }

    @Override
    @Nullable
    public BFHitResolveCache.CachedResolve bf$takeResolve(Entity hitEntity) {
        BFHitResolveCache.CachedResolve cached = this.bf$cachedResolve;
        this.bf$cachedResolve = null;
        return cached != null && cached.hitEntity() == hitEntity ? cached : null;
    }

    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void bf$resolveHitBeforeProcess(HitResult result, CallbackInfo ci) {
        if (!(result instanceof EntityHitResult ehr)) return;
        if (!(ehr.getEntity() instanceof BFHitResolver)) return;

        Projectile self = (Projectile) (Object) this;
        Vec3 delta = BFHitResolver.searchDelta(self.getDeltaMovement());
        // 速率过低：本阶段无从判断假阳性，保持原版 onHit 行为
        if (delta.equals(Vec3.ZERO)) return;

        var resolved = BFDamageApi.resolveHitTarget(result, delta);
        if (resolved == null) {
            ci.cancel();
            return;
        }
        // 真命中：把本次判定结果留给 hurt 阶段复用；本阶段不施加伤害
        bf$cacheResolve(ehr.getEntity(), resolved);
    }
}
```

**零速度必须早退**：`searchDelta` 对零速率返回 `Vec3.ZERO`，若继续以零长度线段调用 `resolveHit`，解析器通常返回 null，会把本该放行的原版命中改成 `ci.cancel()`——投射物不再被销毁，与原版行为不符。两处搜索矢量规则收敛到 `searchDelta` 一处，仅在"零速率是否继续解析"这一点上各自决定：本阶段早退，情况 3 因 `searchRay` 返回 null 而放行。

该 Mixin 的职责限于假阳性 cancel（保证假阳性时投射物不被销毁）与结果写入；真实命中的伤害由 Step 4 承担。

### Step 4：`BFHurtInterceptor` 新增情况 3（代理解析与转发）

**文件**：`internal/BFHurtInterceptor.java`（新增 `net.minecraft.world.entity.projectile.Projectile` 导入）

在情况 2 之后、`BFArmorMaterial` 适配器分支之前插入：

```java
// 情况3：代理对象——实现 BFHitResolver 但不实现 BFHurtTarget，解析后转发协议外伤害
if (self instanceof BFHitResolver) {
    BFHitResolveResult resolved = null;

    // 投射物来源：直接取用 onHit 阶段已完成的判定结果
    if (source.getDirectEntity() instanceof Projectile projectile
            && projectile instanceof BFHitResolveCache cache) {
        BFHitResolveCache.CachedResolve cached = cache.bf$takeResolve(self);
        if (cached != null) resolved = cached.result();
    }
    // 其余来源（近战 / 爆炸等）：按伤害来源构造几何后判定
    if (resolved == null) {
        Vec3[] ray = BFHitResolver.searchRay(self, source);
        // 无源位置伤害（虚空、饥饿、/kill 等）没有可复检的几何，交回原版流程
        if (ray == null) return;
        resolved = BFDamageApi.resolveHitTarget((Object) self, ray[0], ray[1]);
    }

    // 假阳性：AABB 相交但几何未命中——不造成伤害
    if (resolved == null) {
        cir.setReturnValue(false);
        return;
    }

    BFHurtTarget actual = resolved.actualTarget();
    // 解析回自身：交回原版流程，避免自我递归
    if (actual == self) return;

    BFDamageContext ctx = actual.createContextFromVanilla(source, amount);
    // 目标不接受协议外伤害：交回原版流程
    if (ctx == null) return;

    // ctx 非 null 即"协议已接管这次伤害"——必须取消原版流程，
    // 返回值只表示是否实际造成了伤害（与情况 2/4 及 §十一.3 路径 5/7 的约定一致）。
    // 若这里写成 cir.setReturnValue(dealt > 0f)，穿透但被完全挡住（dealt == 0）时
    // 原版 hurt 会继续执行，伤害将落到代理自身。
    float dealt = BFDamageApi.hurt(actual, ctx);
    cir.setReturnValue(dealt > 0f);
    return;
}
```

要点：

- **`ctx != null` 即接管，不看 `dealt`**：这是本分支唯一与"原版伤害是否落到 `self`"相关的开关。写成 `> 0f` 会让"被挡住的命中"改打代理自己（`docs/终点弹道设计文档.md` §11.3 路径 5 已把该语义写成"ctx ≠ null 即接管，无论 dealt 为多少"）。情况 2/4 的现有代码也写作 `dealt > 0f`，Step 4 落地时建议一并核对（属于既有落差，不在本计划强制范围内）。
- **代理对象不应承载生命值**：即使上面改对了，`cir.setReturnValue(false)` 的路径（假阳性）与原版放行路径（无源位置伤害、解析回自身、`ctx == null`）仍会让 `self` 承受原版伤害。代理必须是无血量/无敌的技术性载体，否则"被挡住的一发"或"虚空伤害"就能摧毁代理。该约束登记在 §七.5。
- **投射物来源直接用缓存结果，不重复解析**：能取到缓存就说明 `onHit` 阶段已经把这次命中判定为真命中，结果对本次命中事件有效。`hurt` 阶段因此不需要重建几何，也不会出现两次判定结论相反的情况（§2.4）。取用方只读取 `result.actualTarget()`，不消费 `result.extensions()`。
- **其余来源按伤害类别构造几何后判定**：`source.getDirectEntity()` 不是该投射物时（近战 / 爆炸 / 其他），由 `BFHitResolver.searchRay` 构造几何（爆炸 / 投射物 / 活体近战 / 其他有源位置 / 无源位置，见 Step 1），再交给 `resolveHitTarget`。无源位置伤害直接放行，不猜测。
- **缓存的硬性约束**（违反任一条都会让穿透投射物产生错误判定）：
  1. **单次命中内有效**：缓存记录的是当次命中的判定结果；同一次命中可以直接复用。
  2. **不跨命中事件复用**：取用即清空；穿透投射物在同一 tick 内对多个实体依次触发 `onHit → hurt`，每次命中各写各读，第二次不得沿用第一次的结果。
  3. **身份校验**：仅当缓存记录的命中实体与 `self` 身份相等时才可用。写与读之间可能夹杂未触发 `hurt` 的命中（`canHitEntity` 为 false、`SKIP_ENTITY`、方块命中），此时缓存对应的是别的命中事件。
  4. **仅对投射物来源生效**：`source.getDirectEntity()` 不是该投射物时（近战、爆炸），缓存一律不参与。
  5. 阶段一只在判定为真命中时写入；判定为假阳性时走 `ci.cancel()`，不写入。
- **假阳性分支的归属**：取到缓存时 `resolved` 必然非 null，该分支只可能由无缓存的来源（近战 / 爆炸 / 其他）到达——对这些来源它是名副其实的"假阳性"判定。
- **解析走 `BFDamageApi.resolveHitTarget`**，不直接调 `resolver.resolveHit`——让"打中了谁"的判定规则只有一个入口。传入 `self` 时显式写 `(Object)`，避免命中已弃用的实体版重载。
- **复用 `BFHurtTarget.createContextFromVanilla`** 作为"原版伤害 → 协议上下文"的转换钩子，实体与非实体实际目标走同一路径。
- **`BFDamageApi.hurt` 会把实际目标压入上下文栈**，其内部（或实际目标的）`hurt` 再进入本拦截器时被 `hasContextFor` 守卫放行，不构成递归。该守卫成立的前提是实际目标的 `getBFEntity()` 与最终承接 `hurt` 的实体一致，见 §七。
- **`resolveHit` 的调用次数**：投射物命中一次，无缓存的来源一次；仅当伤害来源的 direct entity 不是该投射物但投射物另有 `onHit` 记录时才会出现第二次。Step 1 把幂等性写进接口契约，覆盖该情况。
- **缓存写入比解析更早**：`AbstractArrow` 的实体命中分支是先 `setPos(hitPos)` + 施加/结算伤害、**再**调用 `onHit`，因此缓存写入（`onHit` HEAD）晚于原版伤害结算。这不影响本计划——读取缓存的时机是 `Entity#hurt`，而 `hurt` 一定发生在 `onHit` 之后（同一 `tick` 内或更晚的实体 tick）。但实现者不应假定"缓存先于伤害"，也不要为缩短窗口而改注入点。
- **回落路径的后果已在 §2.4 说明**：未取得缓存时 `searchRay` 重建几何失败会导致 `resolved == null` → `cir.setReturnValue(false)` → 原版伤害落到代理。因此 §九.3 的缓存写入覆盖率不是"精度问题"，而是"伤害去向问题"。

### Step 5：文档修改与核对

**本计划新增、需要写入文档的内容**（缓存与爆炸分派是本计划引入的，现有文档尚未覆盖）：

- `wiki/4-协议内幕/4.3-ThreadLocal与Mixin.md`：情况 3 补"投射物来源直接取用缓存结果"；`ProjectileHitResolverMixin` 代码示例补结果写入；补一小节说明结果缓存的作用与边界（单次命中有效、不跨命中复用、身份校验）。
- `wiki/3-护甲侧开发/3.7-代理实体：实现BFHitResolver.md`：补"投射物命中通常只解析一次，`hurt` 阶段复用 `onHit` 阶段的结果"与"缓存不跨命中事件复用"。
- `wiki/附录/A.1-API参考.md`：补 `isProtocolAware(Entity)` 实体版重载条目；补 `internal/BFHitResolveCache` 条目并标注其为内部接口、不属于公开 API（并说明它必须 `public` 的原因见 Step 3.1）。
- `docs/终点弹道设计文档.md`：§11.2 情况 3 补缓存优先；§17 补搜索几何来源与 `searchRay` 的爆炸优先分派。
- **多处的"`resolveHit` 会被调用两次"必须改写为"通常一次、少数来源两次"**（见下表末行）：G4 与这些表述直接冲突，不改会让实现者以为缓存不存在。

**现有文档中必须改写的措辞落差**（不只是"补写"，是既有正文与改动后的实现相反）：

| 位置 | 现有内容 | 改法 |
| --- | --- | --- |
| `4.3-ThreadLocal与Mixin.md:241-243` | 整节标题就是"resolveHit 会被调用两次"，正文称"同一次命中最多触发两次解析……两次的搜索几何由各自阶段的信息构造" | 改为"投射物命中通常只解析一次（`onHit` 结果经缓存复用）；仅当伤害来源的 direct entity 不是该投射物时，`hurt` 阶段才回落到按来源重建的几何" |
| `2.6-代理实体与命中解析.md:217` | "单次命中最多触发两次解析" | 同上；另 `:98` 小节标题"两个重载的取舍"与其下 3 行表格不一致，一并修正 |
| `3.7-代理实体：实现BFHitResolver.md:110`、`:188` | "同一次命中最多被调用两次" / "两条路径都会调用 `resolveHit`" | 同上 |
| `A.1-API参考.md:438-439` | "同一次命中可能被调用两次（Projectile.onHit 阶段一次，Entity.hurt 阶段一次）" | 同上 |
| `终点弹道设计文档.md:1801-1802` | "同一次命中最多被调用两次" | 同上 |
| `3.5-协议外伤害兼容.md:11`、`:13` | 第二步"返回 null，**继续下一步**"；第三步"按伤害类别构造几何" | 按 §2.1 的有意变更改写分支归属；第三步补"投射物来源优先取缓存" |
| `终点弹道设计文档.md:1357`、`4.3:109` | 示例写 `resolveHitTarget(self, ray[0], ray[1])`，`self` 静态类型为 `Entity` → 绑定已弃用的实体版 | 改为 `(Object) self`，与 Step 4 源码一致 |
| `A.1-API参考.md:136` | `@Deprecated(since = "1.0.0.alpha.10", forRemoval = true)` | 去掉 `forRemoval`，与 Step 2 的取舍一致（该重载承担长期转发职责） |
| `终点弹道设计文档.md:1848`、`:1867-1868`、`:1996` | §17.4 示例传 `hitEntity` 而签名是 `Object hit`；`HitResult` 重载示例仍直接传 `ehr.getEntity()` | 与 Step 2 的最终签名对齐，实体静态类型处补 `(Object)` |

**逐条核对**（现有内容已与改动后的形态一致，比对即可）：

| 位置 | 现有内容 | 依据 |
| --- | --- | --- |
| `1.6-核心API速览.md` | 已是 `isProtocolAware(Object)` / `resolveHitTarget(Object, Vec3, Vec3)` | 第 45-46 行 |
| `2.6-代理实体与命中解析.md` | 已是 `Object` 版、"命中对象不限于实体"、三重重载取舍表 | 第 28、34-36、102-106 行（表头在 100-101） |
| `3.5-协议外伤害兼容.md` | 已是五步划分，第三步为 BFHitResolver 检查 | 第 7、13 行（分支归属需按上表改写） |
| `3.7-代理实体：实现BFHitResolver.md` | 已有"命中对象不限于实体"、`searchDelta` 规则 | 第 112-114、178-182 行 |
| `4.3-ThreadLocal与Mixin.md` | 已是情况 1-5，已用 `searchRay` / `searchDelta` | 第 93-134、209 行 |
| `A.1-API参考.md` | 已有 `Object` 版签名、实体版 `@Deprecated`、`searchDelta` / `searchRay` | 弃用实体版完整范围为第 132-138 行（原表写 130-133 有误）；`searchDelta` / `searchRay` 在第 471、481 行 |
| `终点弹道设计文档.md` | §11.2 已含情况 3；§11.3 已是九种伤害路径；§17.3、§17.4、§17.9.3 已用 `Object` / `searchDelta` | 第 1352-1371、1400、1791-1794、1867-1868、2066 行。**注意 §17.5 并无 `Object` 用法**（只有裸方法名引用，第 1885、1908 行），不要按"§17.3-17.5 已改完"跳过 |

**风险提示**：`docs/` 下这批文件当前已在描述本次改动后的行为，而代码尚未实现——在 Step 1-4 落地之前，这部分文档与代码事实不符；上表中"必须改写"的条目更是**现在就已自相矛盾**（`4.3:241-243` 与 G4 冲突，`A.1:136` 与 Step 2 冲突）。§十 把文档工作排在代码之后，正是为了收窄该窗口；若计划中止，这批文档需回退。

## 六、对既有调用方的影响

| 调用方 | 调用形式 | 结论 |
| --- | --- | --- |
| `IProjectile#onEntityHit`（Machine-Max 侧） | `isProtocolAware(entity)` | 命中实体版重载，语义不变 |
| `IProjectile#onEntityHit`（Machine-Max 侧） | `resolveHitTarget(entity, hitPoint, delta)` | 命中实体版重载，语义不变 |
| `RigidProjectile#onEntityHit`（Machine-Max 侧） | `resolveHitTarget(entity, hitPoint, delta)` | 命中实体版重载，语义不变 |
| `ProjectileHitResolverMixin` | `resolveHitTarget(result, delta)` | HitResult 重载未变 |
| `ProjectileHitResolverMixin` | 新增实现 `BFHitResolveCache` | 仅在投射物类上追加一个方法组与一个字段 |
| 下游自定义 `BFHitResolver` 实现者 | 实现 `resolveHit` | 接口方法签名未变 |

新增分支的触发条件是"`hurt` 的对象实现 `BFHitResolver` 但不实现 `BFHurtTarget`"。已在 Machine-Max 全仓检索 `BFHitResolver`，无任何实现者（`MMPartEntity` 等候选代理实体尚未实现该接口），其投射物系统走的是显式调用 `resolveHitTarget` 的同步/异步管线，不经过 `hurt` 阶段的拦截分支，因此对既有内容零影响。

投射物侧新增的字段与方法只出现在被 Mixin 处理的 `Projectile` 子类上，不改变任何既有方法的签名或行为。

## 七、下游需要同步补做的前置条件

本节只登记条件，不规定下游实现方式。以下五项在框架侧改动落地后仍然成立，缺任一项对应的场景即无法生效：

1. **代理对象必须实现 `BFHitResolver`，且其 `resolveHit` 返回的 `actualTarget` 具备可用的 `createContextFromVanilla`。** 若实际目标的 `createContextFromVanilla` 返回 null，情况 3 会放行原版流程，转发不产生任何效果。
2. **非实体命中对象要进入框架内攻击者的解析链路，需要调用方自己能拿到该对象。** 框架内的射线分派按其命中对象的类型分支，只覆盖它已识别的类型；`PhysicsHost` 这类非实体属主还需要调用方为它增加一条分派分支，否则该对象根本不会到达 `resolveHitTarget`。
3. **投射物实体作为可被命中对象的场景，需要自身实现 `BFHitResolver`，并为"投射物本体的受击语义"提供 `createContextFromVanilla`。** 该类若覆写了 `Projectile#onHit` 且未调用 `super.onHit`，`onHit` 阶段不执行，缓存不会写入，`hurt` 阶段只能回落到按伤害来源重建的几何——**该回落的后果是伤害去向错误（落回代理自身），不是精度下降**，见 §2.4 与 §九.3。
4. **实际目标的 `getBFEntity()` 必须指向最终承接 `hurt` 的实体。** 情况 3 经 `BFDamageApi.hurt(actual, ctx)` 进入管线，重入守卫依赖上下文栈顶与真正被调用的 `entity.hurt()` 的接收者一致（`BFDamageApi.hurt` 的栈顶取 `target.getBFEntity()`，为空则取 `target` 自身）。非实体实际目标（如 `PhysicsHost`）若在 `hurt()` 内部转调某个实体的 `hurt()` 却不覆写 `getBFEntity()` 返回该实体，守卫会失效，该次伤害会再次进入拦截流程。这是 `BFHurtTarget#getBFEntity()` 接口 Javadoc 里已写明的既有要求，不是本计划新增的约束。
5. **代理对象不应承载生命值，或必须对协议外伤害免疫。** 情况 3 有若干条"放行原版"的路径（无源位置伤害、解析回自身、`ctx == null`、以及假阳性），它们都会让伤害落到 `self` 自身。已对照反编译源码确认两种后果：非生物实体的 `Entity#hurt` 只标记 `markHurt()` 并返回 false（无血量可掉）；**`LivingEntity` 则会真的扣血**。若代理是有血量、会被击杀的生物实体，一次虚空/饥饿伤害或一次假阳性判定就可能摧毁代理。

## 八、验证

| 场景 | 期望 |
| --- | --- |
| 原版投射物命中实现 `BFHitResolver` 的代理，几何命中 | `onHit` 阶段写入缓存结果并放行；`hurt` 阶段直接取用该结果，伤害落在实际目标；返回 true；`resolveHit` 全程只被调用一次 |
| 原版投射物在代理 AABB 内但几何未命中 | `onHit` 阶段不写缓存并 `ci.cancel()`；不造成伤害，投射物继续飞行、不被销毁 |
| 高速投射物（`onHit` 阶段判定命中）命中代理 | `hurt` 阶段取用缓存结果，不重建几何、不重复判定，伤害不丢 |
| 穿透投射物在同一 tick 内连续命中两个代理 | 写读成对发生、取用即清；第二次不沿用第一次的判定结果 |
| 缓存记录的命中实体与当前受击实体不一致 | 取用返回 null，回落 `searchRay`；不误用其它命中事件的结果 |
| 同一投射物先命中 A（A 未触发 `hurt`）再命中 B | B 的 `hurt` 阶段取到 B 自己的判定结果，不受 A 影响 |
| 投射物子类覆写 `onHit` 且未调用 `super.onHit` | 缓存为空，`hurt` 阶段回落 `searchRay` 重建几何 |
| 静止的玩家近战攻击代理 | 以视线为搜索方向，仍能转发到实际目标 |
| 爆炸伤害命中代理（TNT / 活体引爆 / 火球） | 三者都走径向分支：以来源位置指向代理的方向为搜索方向；解析成功则转发 |
| 无源位置伤害（虚空、饥饿）命中代理 | 不构造几何，直接放行原版流程 |
| 同时实现 `BFHitResolver` 与 `BFHurtTarget` 的对象 | 走情况 2，行为不变 |
| 实现 `BFHitResolver` 且为 `LivingEntity`、穿戴 `BFArmorMaterial` 护甲的代理 | 走情况 3；护甲语义由实际目标承担 |
| `resolveHit` 返回代理自身 | 交回原版流程，无递归 |
| `createContextFromVanilla` 返回 null | 交回原版流程 |
| `resolveHit` 以相同输入连续调用两次 | 返回值一致，无副作用（幂等契约） |
| 投射物速率低于 0.001 时命中实现 `BFHitResolver` 的代理 | `onHit` 阶段原版行为不变（不 cancel），不写缓存 |
| 投射物命中代理，实际目标存在但协议判定被完全挡住（`dealt == 0`） | 情况 3 已接管（返回 `true`，原版流程被取消）；代理自身**不掉血**，实际目标也不掉血 |
| 原版箭矢命中代理（走缓存的正常路径） | `hurt` 阶段箭矢尚未移动到命中点，但缓存里有 `onHit` 阶段的精确结果，判定不受影响 |
| 同一场景中实际目标拒绝协议外伤害（`ctx == null`） | 放行原版流程；代理若是非生物实体，`Entity#hurt` 只 `markHurt()` 并返回 false（不掉血）；代理若是 `LivingEntity`，原版扣血会**真的扣在代理身上**——这正是 §七.5 要求代理不承载生命值的原因（已对照反编译源码：`Entity.java:1579-1586`、`Player.java:952`） |
| 普通实体（两个接口都不实现） | 拦截器直接放行，零行为变化 |

**关于测试**：本计划**不把 GameTest 列为交付项**。若后续要补，可行的是"情况 3 转发""假阳性不销毁""缓存一致性（高速投射物不丢伤害）""穿透多目标互不串扰"四项；但仓库当前没有任何 `BFHitResolver` 实现者，落成测试前需要先加一个示例代理实体（实现 `BFHitResolver`、不实现 `BFHurtTarget`）并在 `ExampleContent` 中注册，同时这些测试要自行搭建真实的"投射物 `tick → onHit → hurt`"链路——现有 8 个 GameTest 全部是手工构造 `ctx` 直接调 `BFDamageApi.hurt`，没有可复用的投射物驱动代码。缺测试时的替代验证方式：Downstream 首次接入时按 §七 逐条自查，或在创造模式下手持原版箭/雪球对代理实体实测。

## 九、待实测确认项

1. **（已核实）** `DamageSource#getSourcePosition()` 存在，且实现为"显式记录的位置优先，否则取 `getDirectEntity().position()`，无 direct entity 时返回 null"（NeoForge 21.1.219 反编译源码 `DamageSource.java:119-126`，方法带 `@Nullable`）。投射物伤害源的 direct entity 就是该投射物，故 Step 1 的投射物分支直接取 `projectile.position()` 而不另设回退；其他分支依赖 `radialRay` 的 null 早退来处理"无源位置"与"无 direct entity"两种 null 情况，与实现一致。
2. 活体近战分支的固定搜索长度取 5.0 m 是否足够覆盖常见近战距离与实体 AABB 尺寸。该值可按实测调整，不改变分派结构。
3. **缓存的写入覆盖率**：需确认会触发情况 3 的投射物来源都在 `onHit` 阶段写入了结果。真实条件是 **"`Projectile#onHit` 被调用到"**（缓存写入在该方法 HEAD）——子类覆写 `onHit` 但只要调用或不调用 `super.onHit` 都不影响写入时机；真正绕过缓存的是完全不经过 `Projectile#onHit` 的实体命中路径（自定义投射物自行结算伤害）与伤害延迟结算的来源。未写入的来源会回落 `searchRay`，**其后果按 §2.4 是伤害去向错误，而不是精度下降**，处理方式见 §七.3。
4. **缓存结果的使用面**：取用方只读取 `actualTarget()`，不得调用 `extensions()`。`BFDamageExtensions` 是可变容器（`set()` 写入内部 `HashMap`，`copy()` 是浅拷贝），把 `BFHitResolveResult` 存进缓存等于把解析器的扩展容器引用延长到 `hurt` 阶段之后。因此：解析器不应返回"之后还会被自己修改"的容器；若确实要复用，应返回 `extensions().copy()`。同一 `BFHitResolveResult` 也没有其它读取方（`BFHurtInterceptor` 只取 `actualTarget()`，缓存在 `onHit`/`hurt` 之间不经过第三方）。

## 十、实施顺序与优先级

| 步骤 | 优先级 | 依赖 |
| --- | --- | --- |
| Step 1：`BFHitResolver` 去实体化 + `searchDelta` + `searchRay` + 幂等契约 | 高 | 无 |
| Step 2：`BFDamageApi` 参数放宽（实体版重载一并提供 `@Deprecated` 转发） | 高 | Step 1 |
| Step 3：`BFHitResolveCache` + `ProjectileHitResolverMixin` 结果缓存写入与 `searchDelta` 复用 | 高 | Step 1 |
| Step 4：`BFHurtInterceptor` 情况 3（取用缓存结果 + `searchRay` 回落） | 高 | Step 1、2、3 |
| Step 5：文档修改与核对 | 中 | Step 1-4 |
| 验证：§八 场景表 | 高 | Step 1-4 |
| （可选）4 项 GameTest | 低 | Step 1-4 + 示例代理实体 |

实施顺序：Step 1 → Step 2 → Step 3 → Step 4 → 验证 → Step 5。GameTest 为可选项，可在任意后续版本补齐。

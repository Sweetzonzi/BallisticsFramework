# BFHitResolver 实现计划

> 关联设计文档：[终点弹道设计文档 §十七](./终点弹道设计文档.md#十七bfhitresolver--命中前目标解析接口)

## 一、概述

为 BallisticsFramework 新增命中前目标解析机制，解决代理实体模式下伤害路由和假阳性命中的问题。

核心新增内容：

- `BFHitResolver` 接口 — 由代理实体实现，在管线外解析真正的伤害目标
- `BFHitResolveResult` record — 携带修正后的目标引用、命中几何和扩展数据（可直接用于构造 `BFDamageContext`）
- `BFDamageApi.resolveHitTarget()` — 便利方法，对框架内攻击者统一入口

## 二、文件清单

### 2.1 BallisticsFramework 侧 (bf)

| 文件                                           | 操作     | 说明                                                 |
| -------------------------------------------- | ------ | -------------------------------------------------- |
| `api/BFHitResolver.java`                     | **新建** | 命中前目标解析接口（含 HitResult 便利重载 default 方法）             |
| `api/BFHitResolveResult.java`                | **新建** | 解析结果 record                                        |
| `api/BFDamageApi.java`                       | **修改** | 新增 `resolveHitTarget()` 两参数重载（实体版本 + HitResult 版本） |
| `mixin/ProjectileHitResolverMixin.java`      | **新建** | 注入 `Projectile.onHit` HEAD，自动执行精确命中验证              |
| `resources/ballistics_framework.mixins.json` | **修改** | 注册 `ProjectileHitResolverMixin`                    |

路径前缀：`src/main/java/io/github/sweetzonzi/ballistics_framework/`

### 2.2 Machine-Max 侧 (mm)

| 文件                                  | 操作     | 说明                                      |
| ----------------------------------- | ------ | --------------------------------------- |
| `common/entity/MMPartEntity.java`   | **修改** | 实现 `BFHitResolver`                      |
| `common/entity/PartHitHandler.java` | **修改** | `onProjectileHit` 使用 `resolveHitTarget` |
| `common/mech/vehicle/SubPart.java`  | 无改动    | 已实现 `BFHurtTarget`，无需修改                 |

路径前缀：`src/main/java/io/github/sweetzonzi/machine_max/`

## 三、详细步骤

### Step 1：创建 BFHitResolver 接口

**文件**: `bf/api/BFHitResolver.java`

```java
package io.github.sweetzonzi.ballistics_framework.api;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 命中前目标解析接口。
 * <p>
 * 由代理实体实现。在伤害管线之外（{@link BFDamageApi#hurt} 调用之前）执行，
 * 将原始的实体命中重定向到实际的物理伤害目标。
 * <p>
 * 与 {@link BFHurtTarget} 的职责分离：
 * <ul>
 *   <li>BFHitResolver 回答"打中了谁"——管线外</li>
 *   <li>BFHurtTarget 回答"打中了之后穿深多少、伤害多少"——管线内</li>
 * </ul>
 *
 * @see BFDamageApi#resolveHitTarget(Entity, Vec3, Vec3)
 */
public interface BFHitResolver {

    /**
     * 解析命中的实际伤害目标（主方法）。
     *
     * @param hitPoint 原版报告的命中点（世界坐标）
     * @param delta    搜索矢量，其模为搜索距离上限（m），方向为命中方向。
     *                 典型值：投射物的 deltaMovement 或其倍数。
     * @return 解析结果；{@code null} 表示实际未命中，投射物应继续飞行
     */
    @Nullable
    BFHitResolveResult resolveHit(Vec3 hitPoint, Vec3 delta);

    /**
     * 便利重载：从原版 HitResult 提取命中点后委托给二参数方法。
     * <p>
     * 默认实现取 {@code hitResult.getLocation()} 作为命中点。
     * 实现者可按需覆写以利用 HitResult 类型信息。
     *
     * @param hitResult 原版命中结果（取其 getLocation() 作为命中点）
     * @param delta     搜索矢量
     * @return 解析结果；null 表示未命中
     */
    @Nullable
    default BFHitResolveResult resolveHit(HitResult hitResult, Vec3 delta) {
        return resolveHit(hitResult.getLocation(), delta);
    }
}
```

需要新增 import：

```java
import net.minecraft.world.phys.HitResult;
```

### Step 2：创建 BFHitResolveResult record

**文件**: `bf/api/BFHitResolveResult.java`

```java
package io.github.sweetzonzi.ballistics_framework.api;

import net.minecraft.world.phys.Vec3;

/**
 * 命中解析结果——真正的目标 + 修正后的命中几何 + 扩展数据。
 *
 * @param actualTarget       真正的协议伤害目标（{@link BFHurtTarget} 实例）
 * @param correctedHitPoint  修正后的命中点世界坐标
 * @param correctedHitNormal 修正后的命中面法线
 * @param extensions         解析器提供的扩展数据，可直接用于构造 {@link BFDamageContext}。
 *                           调用方应先 {@link BFDamageExtensions#copy() copy} 后再追加自己的 key。
 *                           无扩展数据时为空容器。
 */
public record BFHitResolveResult(
    BFHurtTarget actualTarget,
    Vec3 correctedHitPoint,
    Vec3 correctedHitNormal,
    BFDamageExtensions extensions
) {
    /** 无扩展数据的便利构造器 */
    public BFHitResolveResult(BFHurtTarget actualTarget, Vec3 correctedHitPoint, Vec3 correctedHitNormal) {
        this(actualTarget, correctedHitPoint, correctedHitNormal, new BFDamageExtensions());
    }
}
```

### Step 3：BFDamageApi 新增便利方法

**文件**: `bf/api/BFDamageApi.java`

在 `BFDamageApi` 类中新增 `resolveHitTarget` 静态方法：

```java
/**
 * 解析命中目标。
 * <p>
 * 若 hitEntity 实现了 {@link BFHitResolver}，执行精确验证并返回修正后的目标与几何。
 * 否则，若 hitEntity 自身是 {@link BFHurtTarget}，直接包装返回。
 * 返回 null 表示未命中（投射物应继续飞行）。
 * <p>
 * 典型调用模式（武器模组侧）：
 * <pre>{@code
 * var resolved = BFDamageApi.resolveHitTarget(hitEntity, hitPoint, delta);
 * if (resolved == null) {
 *     event.setCanceled(true); // 假阳性，投射物继续飞行
 *     return;
 * }
 * var ctx = BFDamageContext.builder()
 *     .hitPoint(resolved.correctedHitPoint())
 *     .hitNormal(resolved.correctedHitNormal())
 *     .extensions(resolved.extensions().copy())  // 解析器产生的扩展数据
 *     // ...
 *     .build();
 * BFDamageApi.hurt(resolved.actualTarget(), ctx);
 * }</pre>
 *
 * @param hitEntity 原版碰撞检测命中的实体
 * @param hitPoint  原版报告的命中点
 * @param delta     搜索矢量，其模为搜索距离上限（m），方向为命中方向
 * @return 解析结果；{@code null} 表示未命中
 */
@Nullable
public static BFHitResolveResult resolveHitTarget(
        Entity hitEntity, Vec3 hitPoint, Vec3 delta) {
    if (hitEntity instanceof BFHitResolver resolver) {
        return resolver.resolveHit(hitPoint, delta);
    }
    if (hitEntity instanceof BFHurtTarget bf) {
        return new BFHitResolveResult(bf, hitPoint, Vec3.ZERO);
    }
    return null;
}
```

以及 HitResult 便利重载：

```java
/**
 * 从原版 HitResult 解析命中目标。
 * <p>
 * 相比 {@link #resolveHitTarget(Entity, Vec3, Vec3)}，
 * 本重载自动从 EntityHitResult 中提取命中实体和命中点。
 * 非 EntityHitResult（如方块命中）返回 null。
 *
 * @param hitResult 原版命中结果
 * @param delta     搜索矢量
 * @return 解析结果；null 表示未命中或无效命中类型
 */
@Nullable
public static BFHitResolveResult resolveHitTarget(
        HitResult hitResult, Vec3 delta) {
    if (hitResult instanceof EntityHitResult ehr) {
        return resolveHitTarget(ehr.getEntity(), hitResult.getLocation(), delta);
    }
    return null;
}
```

需要新增 import：

```java
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
```

**实体版本 + HitResult 版本的关系**：HitResult 版本是实体版本的便利包装，内部自动从 `EntityHitResult` 解包实体和命中点后委托给实体版本。两个重载分工明确：

| 重载           | 签名                                         | 用途                     |
| ------------ | ------------------------------------------ | ---------------------- |
| 实体版本         | `resolveHitTarget(Entity, Vec3, Vec3)`      | 调用方已分别持有命中实体和命中点       |
| HitResult 版本 | `resolveHitTarget(HitResult, Vec3)`         | 调用方持有原版 HitResult，自动解包 |

需要新增 import（HitResult 版本）：

```java
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
```

### Step 4：MMPartEntity 实现 BFHitResolver

**文件**: `mm/common/entity/MMPartEntity.java`

类声明修改为同时实现 `BFHitResolver`：

```java
public class MMPartEntity extends VehicleEntity
        implements IEntityAnimatable<MMPartEntity>, IEntityWithComplexSpawn, EntityPatch, BFHitResolver {
```

新增实现方法（放在 `rayTestSelf` 方法附近）：

```java
/**
 * 实现 BFHitResolver：通过物理引擎射线检测解析真正的命中目标。
 * <p>
 * 从原版命中点沿 delta 方向搜索，找到第一个有效的 SubPart 碰撞箱。
 * 返回 null 表示射线穿过了所有碰撞体——实际未命中（AABB 假阳性）。
 *
 * @param hitPoint 原版报告的命中点
 * @param delta    搜索矢量，其模为搜索距离，方向为命中方向
 * @return 解析结果，未命中时返回 null
 */
@Override
public BFHitResolveResult resolveHit(Vec3 hitPoint, Vec3 delta) {
    Vector3f start = PhysicsHelperKt.toBVector3f(hitPoint);
    Vector3f end = PhysicsHelperKt.toBVector3f(hitPoint.add(delta));
    var results = getPhysicsLevel().getWorld().getWorldSnapshot().rayTest(start, end);
    for (var result : results) {
        if (PhysicsBodyExtensionKt.getOwner(result.getCollisionObject()) instanceof SubPart sp) {
            if (sp.isWheel(result.triangleIndex()) && sp.isWheelSurface(result.triangleIndex()))
                continue;
            HitBox hb = sp.getHitBox(result.triangleIndex());
            if (!hb.isActive()) continue;

            BFDamageExtensions ext = new BFDamageExtensions();
            ext.set(MMDamageExtensions.HIT_BOX, hb);

            return new BFHitResolveResult(
                sp,
                SparkMathKt.toVec3(start.add(end.subtract(start).mult(result.getHitFraction()))),
                SparkMathKt.toVec3(result.getHitNormalLocal(null)),
                ext
            );
        }
    }
    return null; // 射线穿过——实际未命中
}
```

需要新增 import：

```java
import io.github.sweetzonzi.ballistics_framework.api.BFDamageExtensions;
import io.github.sweetzonzi.ballistics_framework.api.BFHitResolver;
import io.github.sweetzonzi.ballistics_framework.api.BFHitResolveResult;
import io.github.sweetzonzi.machine_max.common.mech.vehicle.data.MMDamageExtensions;
```

### Step 5：PartHitHandler 使用 resolveHitTarget

**文件**: `mm/common/entity/PartHitHandler.java`

`onProjectileHit` 方法中，当前的 `(IProjectileMixin) projectile` 数据设置逻辑保持不变（用于后续在 `MMPartEntity.hurt()` 中获取精确碰撞箱引用）。但在设置完数据后，可以额外通过 `BFDamageApi.resolveHitTarget()` 做一次防御性验证。

> **注意**：PartHitHandler 当前是防御性的——它在 `ProjectileImpactEvent` 中取消假阳性命中，让投射物继续飞行。Machine-Max 自身的投射物通过 `onProjectileTick` 做主动射线检测。因此 PartHitHandler 的改动优先级较低，可在 Step 1-4 和 Step 6 完成后根据需要再做。

### Step 6：创建 ProjectileHitResolverMixin

**文件**: `bf/mixin/ProjectileHitResolverMixin.java`

```java
package io.github.sweetzonzi.ballistics_framework.mixin;

import io.github.sweetzonzi.ballistics_framework.api.BFDamageApi;
import io.github.sweetzonzi.ballistics_framework.api.BFHitResolver;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 {@link Projectile#onHit(HitResult)} 的 HEAD 阶段。
 * <p>
 * 若命中的实体实现了 {@link BFHitResolver}，在执行原版命中处理之前
 * 先做精确验证：调用 {@link BFDamageApi#resolveHitTarget(HitResult, Vec3)}
 * 检查是否实际命中。返回 null 时取消原版流程，投射物继续飞行（AABB 假阳性不销毁）。
 * <p>
 * 与 {@code EntityHurtMixin} / {@code LivingEntityHurtMixin} 的互补关系：
 * <ul>
 *   <li>本 Mixin 在管线之外——在 onHit 执行前拦截，解决投射物生命周期问题</li>
 *   <li>EntityHurtMixin 在管线入口——在 hurt 执行前拦截，解决协议外伤害接管问题</li>
 * </ul>
 */
@Mixin(Projectile.class)
public class ProjectileHitResolverMixin {

    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void bf$resolveHitBeforeProcess(HitResult result, CallbackInfo ci) {
        if (!(result instanceof EntityHitResult ehr)) return;
        if (!(ehr.getEntity() instanceof BFHitResolver)) return;

        Projectile self = (Projectile) (Object) this;
        Vec3 velocity = self.getDeltaMovement();
        double speed = velocity.length();
        if (speed < 0.001) return;

        // 搜索距离：取两 tick 飞行距离，使搜索方向与距离合并在单一矢量中
        // 钳制在 [1.0, 8.0] 米
        double clampedSpeed = Math.clamp(speed, 0.5, 4.0);
        Vec3 delta = velocity.scale(clampedSpeed * 2.0 / speed);

        var resolved = BFDamageApi.resolveHitTarget(result, delta);
        if (resolved == null) {
            ci.cancel();
        }
    }
}
```

**与 Machine-Max 现有流程的交互**：

| 场景                             | 触发链                                               | Mixin 行为                               |
| ------------------------------ | ------------------------------------------------- | -------------------------------------- |
| 第三方投射物 → MMPartEntity AABB 假阳性 | PartHitHandler 先 cancel 了 ProjectileImpactEvent   | `onHit()` 不会被调用，Mixin 不触发 ✅            |
| 第三方投射物 → MMPartEntity AABB 真命中 | PartHitHandler 设置 IProjectileMixin 数据 → `onHit()` | Mixin 触发 → 找到 SubPart → 放行 ✅           |
| MM 投射物 → `manualProjectileHit` | `onProjectileTick` → 射线检测 → `onHit(HitResult)`    | Mixin 触发 → 找到 SubPart → 放行 ✅           |
| 普通 Entity（非 BFHitResolver）     | 原版 → `onHit()`                                    | `instanceof` = false → 直接 return，零开销 ✅ |

### Step 7：注册 Mixin

**文件**: `bf/resources/ballistics_framework.mixins.json`

在 `"mixins"` 数组中新增一项：

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "io.github.sweetzonzi.ballistics_framework.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": [
    "EntityHurtMixin",
    "LivingEntityHurtMixin",
    "ProjectileHitResolverMixin"
  ],
  "client": [],
  "injectors": {
    "defaultRequire": 1
  }
}
```

## 四、与现有代码的关系

### P0 已完成的防御侧自愈

`MMPartEntity.hurt()` 中的 `rayTestSelf()` 方法确保：即使攻击方不使用 `BFHitResolver`，伤害也能正确路由。这与 `BFHitResolver` + `ProjectileHitResolverMixin` 形成三层互补：

| 攻击方                      | 路径                                                                             | 效果                       |
| ------------------------ | ------------------------------------------------------------------------------ | ------------------------ |
| 框架内武器模组                  | `resolveHitTarget()` → 修正几何 → `BFDamageApi.hurt(SubPart)`                      | 精确命中 + 假阳性判断             |
| 任意投射物 → BFHitResolver 实体 | `ProjectileHitResolverMixin` 自动拦截 `onHit` → `resolveHitTarget` → null → cancel | **全自动**，投射物不被销毁          |
| 框架外武器模组                  | 直接 `entity.hurt()` → `rayTestSelf()` 降级                                        | 伤害正确路由（但假阳性已在 Mixin 层解决） |
| Machine-Max 投射物          | `onProjectileTick` + `IProjectileMixin`                                        | 两套系统都绕过，直接最优路径           |

**三层防御体系**：

```
上层（Mixin 自动化）:  ProjectileHitResolverMixin
  拦截 Projectile.onHit → resolveHitTarget → null → cancel
  → 解决投射物生命周期问题：打空了就该继续飞

中层（协议查询）:       BFDamageApi.resolveHitTarget()
  实体版本 / HitResult 版本 → 委托 BFHitResolver
  → 为框架内攻击者提供精确命中查询

下层（防御侧自愈）:      MMPartEntity.hurt() 中的 rayTestSelf()
  已在管线内的伤害 → 自行精确检测 → 路由到正确 SubPart
  → 解决伤害路由问题：打中了就该找对目标
```

### 不更改的部分

- `SubPart` 保持不变（已实现 `BFHurtTarget`，新增接口与它无关）
- `BFDamageContext` 保持不变（几何数据仍然通过现有字段传递）
- `BFDamageExtensions` 保持不变（`MMDamageExtensions.HIT_BOX` 继续通过其扩展 key 使用，现在经由 `BFHitResolveResult.extensions` 传递）

## 五、测试要点

### GameTest 建议场景

1. **代理实体直接命中**：投射物命中 MMPartEntity 的精确碰撞箱 → `resolveHitTarget` 返回有效结果 → SubPart 受伤
2. **AABB 假阳性**：投射物从 MMPartEntity 边缘擦过（AABB 相交但几何射线未命中） → Mixin 拦截 `onHit` → `resolveHitTarget` 返回 null → `ci.cancel()` → 投射物继续飞行
3. **第三方投射物假阳性**：框架外投射物的 AABB 误命中 MMPartEntity → Mixin 自动拦截 → cancel → 投射物不被销毁（**全自动化测试**）
4. **普通实体**：投射物命中普通 Entity（非 BFHitResolver） → Mixin 直接 return → 原版流程无干扰
5. **近战攻击代理实体**：玩家攻击 MMPartEntity → `rayTestSelf` 找到 SubPart → 伤害正确路由
6. **爆炸伤害代理实体**：TNT 爆炸波及 MMPartEntity → 现有爆炸路径保持工作
7. **方块命中**：投射物命中方块 → `EntityHitResult` 检查失败 → Mixin 直接 return → 原版正常处理

## 六、实施优先级

| 步骤                                        | 优先级 | 依赖           | 预计影响                                       |
| ----------------------------------------- | --- | ------------ | ------------------------------------------ |
| Step 1: BFHitResolver（含便利重载）              | P1  | 无            | 新增接口，零影响                                   |
| Step 2: BFHitResolveResult                | P1  | 无            | 新增 record，零影响                              |
| Step 3: BFDamageApi.resolveHitTarget（两重载） | P1  | Step 1, 2    | 新增方法，零影响                                   |
| Step 6: ProjectileHitResolverMixin        | P1  | Step 1, 2, 3 | 新增 Mixin，注入 Projectile 基类。需验证不与现有 Mixin 冲突 |
| Step 7: mixins.json 注册                    | P1  | Step 6       | 修改配置文件                                     |
| Step 4: MMPartEntity 实现 BFHitResolver     | P2  | Step 1, 2    | 新增接口实现，低风险                                 |
| Step 5: PartHitHandler 集成                 | P2  | Step 4       | 改动现有逻辑，需测试                                 |

**建议实施顺序**：Step 1 → Step 2 → Step 3 → Step 6 → Step 7 → 验证 GameTest → Step 4 → Step 5

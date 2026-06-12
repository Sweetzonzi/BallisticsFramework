# Ballistics Framework — 弹道框架

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.219-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![License](https://img.shields.io/badge/License-LGPL%203.0-blue)
[![Wiki](https://img.shields.io/badge/Wiki-GitHub%20Pages-blue?logo=github)](https://sweetzonzi.github.io/BallisticsFramework/)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Sweetzonzi/BallisticsFramework)

[English](README.en.md) | [Forge 1.20.1 版](https://github.com/Sweetzonzi/BallisticsFramework/tree/1.20.1-forge)

***

## 这是什么？

BallisticsFramework 是一个 NeoForge/Forge（1.21.1/1.20.1）lib 模组，为 Minecraft 模组生态提供两大标准化能力：

- **终点弹道伤害协议** — 枪械、载具、护甲模组共同依赖的穿甲判定与伤害协商协议
- **外弹道解算工具** — 弹道正解、瞄准反解、动目标提前量预测，双物理模型（MC 原版 & 二次阻力拟真）

核心定位是一个可供多个模组共同依赖的兼容层。协议解决的问题：

- 子弹命中装甲时，如何传递**穿深、入射角、命中部位**等高维信息？
- 护甲模组如何根据**命中面法线、速度矢量**做穿深修正和击穿判定？
- 多个模组的弹道系统如何共用一套伤害-装甲交互协议？

设计原则：**不替代原版，包裹原版**——协议不绕开 `Entity#hurt`，对未声明协议感知的实体完全走原版流程。

***

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/ballistics_framework-1.21.1-neoforge-1.0.0.alpha.7.jar`。

## 引入依赖

本库发布在 Maven 仓库，在你的 `build.gradle` 中添加：

```groovy
repositories {
    maven {
        url = "https://maven.sighs.cc/repository/maven-releases/"
    }
}

dependencies {
    // NeoForge 1.21.1（本分支）
    implementation "io.github.sweetzonzi:ballistics_framework-1.21.1-neoforge:1.0.0.alpha.7"
    // Forge 1.20.1（1.20.1-forge 分支）:
    // implementation "io.github.sweetzonzi:ballistics_framework-1.20.1-forge:1.0.0.alpha.7"
}
```

若使用 `build.gradle.kts`（Kotlin DSL）：

```kotlin
repositories {
    maven {
        url = uri("https://maven.sighs.cc/repository/maven-releases/")
    }
}

dependencies {
    // NeoForge 1.21.1（本分支）
    implementation("io.github.sweetzonzi:ballistics_framework-1.21.1-neoforge:1.0.0.alpha.7")
    // Forge 1.20.1（1.20.1-forge 分支）:
    // implementation("io.github.sweetzonzi:ballistics_framework-1.20.1-forge:1.0.0.alpha.7")
}
```

根据你的加载器选择对应的坐标，添加后同步 Gradle 即可获得全部 API。

***

## 快速开始

### 武器模组——发起协议伤害

```java
BFDamageContext ctx = BFDamageContext.builder()
    .source(source)                              // 原版 DamageSource
    .baseDamage(35f)                             // 标称伤害
    .hitVelocity(bullet.getDeltaMovement())      // 速度矢量（m/s）
    .hitPoint(hitResult.getLocation())           // 命中点
    .hitNormal(hitResult.getDirection())         // 命中面法线
    .penetration(120f)                           // 理论穿深（mm RHA）
    .build();

float dealt = BFDamageApi.hurt(target, ctx);
```

目标实现 `BFHurtTarget` 时自动走穿甲管线，否则以 `baseDamage` 直接调用原版 `hurt()`。

### 武器模组——带回调的协议伤害

需要接收命中结果（击穿/跳弹/破片等）时，实现 `BFDamageHandler` 接口，通过 `dealDamage()` 发起伤害：

```java
public class MyWeapon implements BFDamageHandler {

    void fire(Entity target) {
        BFDamageContext ctx = BFDamageContext.builder()
            .source(src).baseDamage(35f).penetration(120f)
            .build();
        float dealt = this.dealDamage(target, ctx);  // 自动注入自身为 handler
    }

    // 以下回调按需覆写，默认空操作

    @Override
    public void onPenetrated(BFHurtTarget target, BFDamageContext ctx) {
        spawnPenEffects(ctx.hitPoint());
    }

    @Override
    public void onBlocked(BFHurtTarget target, BFDamageContext ctx) {
        spawnSparkEffects(ctx.hitPoint());
    }

    @Override
    public void onRicochet(BFHurtTarget target, BFDamageContext ctx) {
        playRicochetSound(ctx.hitPoint());
    }

    @Override
    public void onOvermatch(BFHurtTarget target, BFDamageContext ctx) {
        // 超匹配（碾压）——弹体完整穿透，不碎裂
    }

    @Override
    public void onSpall(BFHurtTarget target, BFDamageContext ctx) {
        // 破片——弹体碎裂，产生二次杀伤
    }
}
```

超匹配（overmatch）和破片（spall）由 handler 的 `isOvermatch()` / `isSpall()` 默认方法自动判定：

- **超匹配**：击穿 且 穿深 > 装甲厚度 × 1.5 → 弹体完整穿透，无破片
- **破片**：未击穿（表面碎裂）或 击穿但非超匹配（穿透中碎裂）→ 产生破片
- **跳弹**：不产生超匹配也不产生破片

覆写这两个方法可自定义判定逻辑。

### 护甲模组——实现 BFHurtTarget

```java
public class MyTank extends LivingEntity implements BFHurtTarget {

    @Override
    public float getRHA(BFDamageContext ctx) {
        return 50f;  // 根据 ctx.hitPoint() 区分部位
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return super.hurt(source, amount);
    }

    @Override @Nullable
    public BFDamageContext createContextFromVanilla(DamageSource source, float amount) {
        return null;  // 返回 null 退回原版流程
    }
}
```

### 护甲模组——跳弹判定

覆写 `resolvePenetration()` 在入射角过大时返回 `RICOCHET`：

```java
@Override
public PenetrationResult resolvePenetration(BFDamageContext ctx) {
    Vec3 velocity = ctx.hitVelocity();
    Vec3 normal = ctx.hitNormal();
    float angle = (float) Math.toDegrees(Math.acos(
        Math.abs(velocity.dot(normal)) / (velocity.length() * normal.length())));
    if (angle > 70f) return PenetrationResult.RICOCHET;
    return super.resolvePenetration(ctx);
}
```

### 护甲模组——钝伤 / 特殊伤害

`calculateFinalDamage(ctx, result)` 接收 `PenetrationResult`，护甲侧可基于结果自由决定伤害：

```java
@Override
public float calculateFinalDamage(BFDamageContext ctx, PenetrationResult result) {
    return switch (result) {
        case RICOCHET -> ctx.baseDamage() * 0.1f;   // 跳弹仍有 10% 冲击伤害
        case BLOCKED -> ctx.baseDamage() * 0.05f;   // 未击穿 5% 钝伤
        case PENETRATED -> ctx.baseDamage();          // 击穿 100%
    };
}
```

***

## 弹道解算

框架内置外弹道解算器，覆盖"发射 → 命中"的完整飞行阶段。根据弹丸物理类型选择模型：

| 弹丸类型 | 模型 | 入参单位 |
|---------|------|:---:|
| 原版弹射物（箭、雪球、火球等）| `MinecraftTrajectory` | m/tick |
| 自定义二次阻力弹丸 | `RealisticTrajectory` | m/s |

产物位于 `api.trajectory` 子包，所有方法 `static`、线程安全。

### 正解 — 算弹丸轨迹

```java
// MC 原版箭矢正解
TrajectoryResult traj = MinecraftTrajectory.arrowTrajectory(
    arrowEntity.position(), arrowEntity.getDeltaMovement(), 100);

// 拟真模型正解 — 120mm 坦克炮
BallisticConfig config = BallisticConfig.fromExtensions(exts, 0.35f, 0.8f, 0.05f, 200);
TrajectoryResult traj = RealisticTrajectory.forwardSolve(
    shooterPos, new Vec3(0, 0, 1500), config, DensityFunction.MC_OVERWORLD);

// 正解结果直传终点弹道上下文
BFDamageContext hitCtx = BFDamageContext.builder()
    .source(src).baseDamage(500f)
    .hitPoint(traj.terminalPoint())         // 弹丸最终位置
    .hitVelocity(traj.terminalVelocity())   // 末速 m/s，直传
    .build();
```

### 反解 — AI 炮塔自动瞄准

```java
// MC 原版箭矢反解，一行调用
List<FiringSolution> solutions = MinecraftTrajectory.arrowFiringAngle(
    turretPos, targetPos, 6.0f, turretVelocity, 200);

if (!solutions.isEmpty()) {
    FiringSolution sol = solutions.get(0);       // 平射解
    arrowEntity.setDeltaMovement(                // 直接设为弹丸速度方向
        sol.direction().scale(6.0f).add(turretVelocity));
}
```

初速充足时返回两个解（平射 + 高抛），临界初速时返回一个，初速不足时返回空列表。

### 动目标提前量

```java
// 箭矢对移动玩家预测提前量
FiringSolution lead = MinecraftTrajectory.arrowWithLead(
    shooterPos, targetPos, targetVel, Vec3.ZERO,
    speed, shooterVel, 200);
```

解算器用 `distance/speed` 做首次粗略外推后再迭代微调，2~4 轮收敛。

***

## 高级用法

### 可覆写管线方法

`BFHurtTarget` 的默认实现基于离散的 `ArmorLevel` 等级体系，精密模组可逐步覆写以下方法回到精确数值模型：

| 方法                                  | 默认行为                     | 精密模组覆写为                     |
| ----------------------------------- | ------------------------ | --------------------------- |
| `getArmorLevel(ctx)`                | **必须实现**，返回离散等级          | 可委托给 `fromRha(getRHA(ctx))` |
| `getRHA(ctx)`                       | 取 `getArmorLevel` 的中位值   | 返回精确的 RHA 等效厚度              |
| `modifyPenetration(ctx)`            | 原样返回 `ctx.penetration()` | 间隙衰减、爆反拦截等                  |
| `isArmorPenetrated(ctx)`            | 离散等级比较                   | 精确 float 比较                 |
| `resolvePenetration(ctx)`           | 委托 `isArmorPenetrated`   | 添加跳弹判定                      |
| `calculateFinalDamage(ctx, result)` | 0 / 65% / 100%           | 钝伤、超匹配加成等                   |

### 护甲侧通过 handler 查询伤害来源

```java
@Override
public float getRHA(BFDamageContext ctx) {
    BFDamageHandler h = ctx.getHandler();
    if (h instanceof ChemicalWeapon) return eraEffectiveRha;  // 爆反生效
    return baseRha;
}
```

### 在 hurt() 内获取上下文

```java
@Override
public boolean hurt(DamageSource source, float amount) {
    BFDamageContext ctx = BFDamageApi.getContextFor(this);
    if (ctx != null) playHitSound(ctx.hitPoint());
    return super.hurt(source, amount);
}
```

`getContextFor(this)` 仅在管线内部（push 后、pop 前）返回非 null。

### 使用扩展字段（Extension）

协议自带的扩展 key：`FUSE_DELAY`（Float, s）、`CALIBER`（Float, m）、`MASS`（Float, kg）。

读操作总返回非 null（未设置时退回默认值）：

```java
// 设置口径和质量
ctx.extensions().set(BFDamageExtensions.CALIBER, 0.12f);   // 120mm
ctx.extensions().set(BFDamageExtensions.MASS, 22f);         // 22kg APFSDS

// 其他模组读取
float caliber = ctx.extensions().get(BFDamageExtensions.CALIBER);
```

注册自定义扩展 key：

```java
public static final BFDamageExtensionKey<HitBox> HIT_BOX =
    BFDamageExtensions.register(
        ResourceLocation.fromNamespaceAndPath("my_mod", "hit_box"),
        HitBox.class, () -> null);
```

### 全部使用国际单位制

外弹道解算器输出与终点弹道字段共用统一的 SI 单位：

| 字段 | 单位 | 说明 |
|------|------|------|
| `hitVelocity` | m/s | 命中速度矢量 |
| `penetration` | mm | 垂直 RHA 等效穿深 |
| `FUSE_DELAY` | s | 引信延迟 |
| `CALIBER` | m | 弹体口径 |
| `MASS` | kg | 弹体质量 |
| `TrajectoryResult.terminalVelocity()` | m/s | 外弹道末速（与 `hitVelocity` 一致，直传） |
| `RealisticTrajectory` 全部入参 | SI | m/s, kg, m, m/s² |

***

## 架构概要

```
                            外弹道（api.trajectory/）              终点弹道（api/）
                      ┌─────────────────────────┐      ┌──────────────────┐
                      │ 正解 forwardSolve        │ ───→ │ 穿甲判定           │
                      │ 反解 solveFiringAngle    │ ctx  │ 伤害计算           │
                      │ 提前量 solveWithLead     │      │ 回调触发           │
                      └─────────────────────────┘      └──────────────────┘
                          子弹飞行中                      命中瞬间

BFDamageApi.hurt(target, ctx)          ← 武器模组入口
    │
    ├── target instanceof BFHurtTarget
    │     ├── target.getRHA(ctx)               ← 护甲厚度
    │     ├── target.modifyPenetration(ctx)     ← 减效修正
    │     ├── target.resolvePenetration(ctx)    ← PENETRATED/BLOCKED/RICOCHET
    │     ├── target.calculateFinalDamage(ctx, result) ← 最终伤害
    │     ├── target.hurt(source, finalDmg)     ← 执行伤害
    │     └── if (handler != null) → triggerCallbacks  ← 回调
    │
    └── target instanceof LivingEntity
          └── living.hurt(source, baseDamage)   ← 直接原版
```

对外只暴露 `api/` 和 `api/trajectory/` 包，内部实现位于 `internal/` 包。

***

## 许可

GNU LGPL 3.0

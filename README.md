# Ballistics Framework — 弹道框架

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.219-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![License](https://img.shields.io/badge/License-LGPL%203.0-blue)
[![Wiki](https://img.shields.io/badge/Wiki-GitHub%20Pages-blue?logo=github)](https://sweetzonzi.github.io/BallisticsFramework/)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Sweetzonzi/BallisticsFramework)

[English](README.en.md)

***

## 这是什么？

BallisticsFramework 是一个 NeoForge（1.21.1）lib 模组，为 Minecraft 模组生态定义了一套 **终点弹道伤害协议层**。

核心定位是一个可供多个枪械、载具、护甲模组共同依赖的兼容协议。协议解决的问题：

- 子弹命中装甲时，如何传递**穿深、入射角、命中部位**等高维信息？
- 护甲模组如何根据**命中面法线、速度矢量**做穿深修正和击穿判定？
- 多个模组的弹道系统如何共用一套伤害-装甲交互协议？

设计原则：**不替代原版，包裹原版**——协议不绕开 `Entity#hurt`，对未声明协议感知的实体完全走原版流程。

***

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/ballistics_framework-1.21.1-1.0-SNAPSHOT.jar`。

依赖方式：flatDir 本地 jar 或源集依赖，参照 `neoforge.mods.toml` 添加 dependency 声明。

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

| 字段            | 单位  | 说明          |
| ------------- | --- | ----------- |
| `hitVelocity` | m/s | 命中速度矢量      |
| `penetration` | mm  | 垂直 RHA 等效穿深 |
| `FUSE_DELAY`  | s   | 引信延迟        |
| `CALIBER`     | m   | 弹体口径        |
| `MASS`        | kg  | 弹体质量        |

***

## 架构概要

```
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

对外只暴露 `api/` 包，内部实现位于 `internal/` 包。

***

## 许可

GNU LGPL 3.0

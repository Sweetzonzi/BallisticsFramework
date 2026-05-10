# TerminalBallistics — 终点弹道协议

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.219-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![License](https://img.shields.io/badge/License-LGPL%203.0-blue)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Sweetzonzi/TerminalBallistics)

[English](README.en.md)

***

## 这是什么？

TerminalBallistics 是一个 NeoForge（1.21.1）lib 模组，为 Minecraft 模组生态定义了一套 **终点弹道伤害协议层（Terminal Ballistics Damage Protocol Layer）**。

它的核心定位是一个可供多个枪械、载具、护甲模组共同依赖的兼容协议，而非一个独立运行的伤害大改模组。协议解决的典型问题：

- 子弹命中装甲时，如何传递**穿深、入射角、命中部位**等高维信息？
- 护甲模组如何根据**命中面法线、速度矢量**做斜穿修正和击穿判定？
- 多个模组的弹道系统如何共用一套伤害-装甲交互协议？

协议的设计原则是**不替代原版，而是包裹原版**——协议绝不绕开 `Entity#hurt`/`LivingEntity#hurt`，对于未声明协议感知的普通实体，伤害完全走原版流程。

## 当前状态

暂无 Maven 仓库发布。依赖方式为**本地 jar**。

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/terminal_ballistics-1.21.1-1.0-SNAPSHOT.jar`。

## 作为依赖使用

### 方式一：flatDir + 本地 jar

将 jar 放入你项目的 `libs/` 目录，在 `build.gradle` 中添加：

```groovy
repositories {
    flatDir {
        dirs 'libs'
    }
}

dependencies {
    implementation "io.github.sweetzonzi:terminal_ballistics:1.0-SNAPSHOT"
}
```

然后在 `neoforge.mods.toml` 中添加依赖声明：

```toml
[[dependencies."your_mod_id"]]
modId = "terminal_ballistics"
type = "required"
versionRange = "[1.0-SNAPSHOT,)"
ordering = "NONE"
side = "BOTH"
```

### 方式二：源集依赖（同一工作区）

```groovy
dependencies {
    implementation project(":TerminalBallistics")
}
```

## 快速开始

### 武器模组侧：发起一次协议伤害

```java
// 构造命中上下文
TBDamageContext ctx = TBDamageContext.builder()
    .source(source)                              // 原版 DamageSource
    .baseDamage(35f)                             // 标称伤害
    .hitVelocity(bullet.getDeltaMovement())      // 弹体速度矢量
    .hitPoint(hitResult.getLocation())           // 命中点
    .hitNormal(hitResult.getDirection())         // 命中面法线
    .penetration(120f)                           // 理论穿深（mm RHA）
    .build();

// 发起协议伤害
float dealt = TBDamageApi.hurt(target, ctx);
```

如果目标实现了 `TBHurtTarget`，将自动走穿甲判定管线；否则直接以 `baseDamage` 调用原版 `hurt()`。

### 护甲模组侧：实现 TBHurtTarget

```java
public class MyArmoredEntity extends LivingEntity implements TBHurtTarget {

    // 返回命中部位的装甲厚度
    @Override
    public float getRHA(TBDamageContext ctx) {
        // 根据 ctx.hitPoint() 判断命中部位
        if (isHeadShot(ctx.hitPoint())) return 30f;
        if (isChest(ctx.hitPoint())) return 50f;
        return 20f;
    }

    // 执行实际伤害
    @Override
    public boolean hurt(DamageSource source, float amount) {
        // 可自定义逻辑，也可委托原版
        return super.hurt(source, amount);
    }

    // 协议外伤害（原版攻击、爆炸等）转为协议上下文
    @Override
    public TBDamageContext createContextFromVanilla(DamageSource source, float amount) {
        // 返回 null 表示该伤害不走协议管线
        return null;
    }
}
```

### 覆写穿甲判定

`TBHurtTarget` 的所有关键方法都有可覆写的默认实现：

```java
@Override
public float modifyPenetration(TBDamageContext ctx) {
    // 默认斜穿修正：penetration / cos(θ)
    // 可覆写以支持间隙装甲、爆反拦截等
    return TBHurtTarget.super.modifyPenetration(ctx);
}

@Override
public boolean isArmorPenetrated(TBDamageContext ctx) {
    // 默认：modifiedPen > getRHA
    return TBHurtTarget.super.isArmorPenetrated(ctx);
}

@Override
public float calculateFinalDamage(TBDamageContext ctx) {
    // 默认：击穿返回 baseDamage，未击穿返回 0
    return TBHurtTarget.super.calculateFinalDamage(ctx);
}
```

### 使用扩展字段（侧信道通信）

```java
// 在 getRHA 覆写中标记跳弹
ctx.getExtensions().set(TBDamageExtensions.RICOCHET, true);

// 在伤害返回后，其它模组读取
boolean ricochet = ctx.getExtensions().get(TBDamageExtensions.RICOCHET);
```

协议自带四个标准扩展 key：

- `RICOCHET`（Boolean）— 跳弹标记
- `SPALL`（Boolean）— 破片标记
- `OVERMATCH`（Boolean）— 超匹配标记
- `FUSE_DELAY`（Integer）— 引信延迟

也可以在任意模组侧注册自己的扩展 key。

### 在 hurt() 内获取上下文

当需要在 {@link TBHurtTarget#hurt TBHurtTarget.hurt()} 内读取完整的命中上下文（如命中位置、速度、侧信道扩展）来做额外后效处理时，调用 {@link TBDamageApi#getContextFor TBDamageApi.getContextFor(this)}：

```java
@Override
public boolean hurt(DamageSource source, float amount) {
    TBDamageContext ctx = TBDamageApi.getContextFor(this);
    if (ctx != null) {
        // 根据命中位置播放不同音效
        playArmorHitSound(ctx.hitPoint());
        // 读取侧信道标记（由 getRHA 覆写设置）
        if (Boolean.TRUE.equals(
                ctx.extensions().get(TBDamageExtensions.RICOCHET))) {
            spawnRicochetParticles(ctx.hitPoint(), ctx.hitNormal());
        }
        // 根据穿深/护甲比决定破片数量
        if (ctx.penetration() > 100f) {
            spawnSpallParticles(ctx.hitPoint(), 5);
        }
    }
    return super.hurt(source, amount);
}
```

该方法仅在协议管线内部（即 push 之后、pop 之前）返回非 null 值，线程安全，不破坏现有逻辑。

## 架构概要

```
TBDamageApi.hurt(target, ctx)          ← 武器模组入口
    │
    ├── target instanceof TBHurtTarget
    │     ├── target.getRHA(ctx)               ← 护甲厚度
    │     ├── target.modifyPenetration(ctx)     ← 斜穿修正
    │     ├── target.isArmorPenetrated(ctx)     ← 击穿判断
    │     ├── target.calculateFinalDamage(ctx)  ← 最终伤害
    │     └── target.hurt(source, finalDamage)  ← 执行伤害
    │
    └── target instanceof LivingEntity
          └── living.hurt(source, baseDamage)   ← 直接原版
```

上下文通过 ThreadLocal 栈隐式传递。Mixin 注入 `Entity#hurt` 和 `LivingEntity#hurt` 以拦截协议外伤害。

对外只暴露 `api/` 包，内部实现位于 `internal/` 包。

## 许可

GNU LGPL 3.0

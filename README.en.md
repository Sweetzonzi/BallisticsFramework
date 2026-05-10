# Terminal Ballistics Protocol — A Ballistics Damage Protocol Layer

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.219-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![License](https://img.shields.io/badge/License-LGPL%203.0-blue)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Sweetzonzi/TerminalBallistics)

[中文版](README.md)

***

## What is This?

TerminalBallistics is a NeoForge (1.21.1) library mod that defines a **Terminal Ballistics Damage Protocol Layer** for the Minecraft modding ecosystem.

It serves as a compatibility protocol that multiple gun, vehicle, and armor mods can depend on together. Problems it solves:

- When a bullet hits armor, how do you pass high-dimensional info like **penetration, impact angle, and hit location**?
- How does an armor mod use **hit normals and velocity vectors** for penetration slope correction?
- How can multiple ballistic systems share a unified damage-armor interaction protocol?

The protocol's guiding principle is **wrap, don't replace** — it never bypasses `Entity#hurt`. For non-protocol-aware entities, damage runs entirely through vanilla.

***

## Building

```bash
./gradlew build
```

Output at `build/libs/terminal_ballistics-1.21.1-1.0-SNAPSHOT.jar`.

Dependency via flatDir local jar or source-set dependency; declare in `neoforge.mods.toml`.

***

## Quick Start

### Weapon Mod — Basic Protocol Damage

```java
TBDamageContext ctx = TBDamageContext.builder()
    .source(source)                              // vanilla DamageSource
    .baseDamage(35f)                             // nominal damage
    .hitVelocity(bullet.getDeltaMovement())      // velocity (m/s)
    .hitPoint(hitResult.getLocation())           // impact point
    .hitNormal(hitResult.getDirection())         // surface normal
    .penetration(120f)                           // penetration (mm RHA)
    .build();

float dealt = TBDamageApi.hurt(target, ctx);
```

If the target implements `TBHurtTarget`, the full penetration pipeline runs automatically. Otherwise, it falls back to vanilla `hurt()` with `baseDamage`.

### Weapon Mod — Protocol Damage with Callbacks

To receive hit results (penetration/ricochet/spall/overmatch), implement `TBDamageHandler` and use `dealDamage()`:

```java
public class MyWeapon implements TBDamageHandler {

    void fire(Entity target) {
        TBDamageContext ctx = TBDamageContext.builder()
            .source(src).baseDamage(35f).penetration(120f)
            .build();
        float dealt = this.dealDamage(target, ctx);  // auto-injects self as handler
    }

    // Override as needed; all default to no-op

    @Override
    public void onPenetrated(TBHurtTarget target, TBDamageContext ctx) {
        spawnPenEffects(ctx.hitPoint());
    }

    @Override
    public void onBlocked(TBHurtTarget target, TBDamageContext ctx) {
        spawnSparkEffects(ctx.hitPoint());
    }

    @Override
    public void onRicochet(TBHurtTarget target, TBDamageContext ctx) {
        playRicochetSound(ctx.hitPoint());
    }

    @Override
    public void onOvermatch(TBHurtTarget target, TBDamageContext ctx) {
        // Overmatch (full over-penetration) — projectile passes through intact
    }

    @Override
    public void onSpall(TBHurtTarget target, TBDamageContext ctx) {
        // Spall/fragmentation — projectile shatters
    }
}
```

Overmatch and spall are auto-determined by the handler's `isOvermatch()` / `isSpall()` defaults:
- **Overmatch**: penetrated AND penetration > armor × 1.5 → projectile passes intact, no spall
- **Spall**: blocked (surface shatter) OR penetrated but not overmatch (shatter during penetration) → spall
- **Ricochet**: no overmatch, no spall

Override these methods for custom logic.

### Armor Mod — Implementing TBHurtTarget

```java
public class MyTank extends LivingEntity implements TBHurtTarget {

    @Override
    public float getRHA(TBDamageContext ctx) {
        return 50f;  // differentiate hit regions via ctx.hitPoint()
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return super.hurt(source, amount);
    }

    @Override @Nullable
    public TBDamageContext createContextFromVanilla(DamageSource source, float amount) {
        return null;  // null = fall through to vanilla
    }
}
```

### Armor Mod — Ricochet Detection

Override `resolvePenetration()` to return `RICOCHET` when the impact angle is too shallow:

```java
@Override
public PenetrationResult resolvePenetration(TBDamageContext ctx) {
    Vec3 velocity = ctx.hitVelocity();
    Vec3 normal = ctx.hitNormal();
    float angle = (float) Math.toDegrees(Math.acos(
        Math.abs(velocity.dot(normal)) / (velocity.length() * normal.length())));
    if (angle > 70f) return PenetrationResult.RICOCHET;
    return super.resolvePenetration(ctx);
}
```

### Armor Mod — Blunt Damage / Custom Damage

`calculateFinalDamage(ctx, result)` receives the `PenetrationResult` to freely decide damage:

```java
@Override
public float calculateFinalDamage(TBDamageContext ctx, PenetrationResult result) {
    return switch (result) {
        case RICOCHET -> ctx.baseDamage() * 0.1f;   // 10% shock damage on ricochet
        case BLOCKED -> ctx.baseDamage() * 0.05f;   // 5% blunt trauma
        case PENETRATED -> ctx.baseDamage();          // 100% on penetration
    };
}
```

***

## Advanced Usage

### Overridable Pipeline Methods

`TBHurtTarget` defaults are based on the discrete `ArmorLevel` system. Precision mods can override step by step:

| Method | Default | Precision Override |
|--------|---------|-------------------|
| `getArmorLevel(ctx)` | **Must implement**, returns discrete level | Delegate to `fromRha(getRHA(ctx))` |
| `getRHA(ctx)` | median of `getArmorLevel` | Exact RHA thickness |
| `modifyPenetration(ctx)` | Returns `ctx.penetration()` verbatim | ERA intercept, spaced armor |
| `isArmorPenetrated(ctx)` | Discrete level comparison | Exact float comparison |
| `resolvePenetration(ctx)` | Delegates to `isArmorPenetrated` | Add ricochet logic |
| `calculateFinalDamage(ctx, result)` | 0 / 65% / 100% | Blunt damage, overmatch bonus |

### Querying Damage Source in Armor Logic

```java
@Override
public float getRHA(TBDamageContext ctx) {
    TBDamageHandler h = ctx.getHandler();
    if (h instanceof ChemicalWeapon) return eraEffectiveRha;  // ERA vs HEAT
    return baseRha;
}
```

### Retrieving Context Inside hurt()

```java
@Override
public boolean hurt(DamageSource source, float amount) {
    TBDamageContext ctx = TBDamageApi.getContextFor(this);
    if (ctx != null) playHitSound(ctx.hitPoint());
    return super.hurt(source, amount);
}
```

`getContextFor(this)` returns non-null only inside the pipeline (between push and pop).

### Using Extensions

Built-in extension keys: `FUSE_DELAY` (Float, s), `CALIBER` (Float, m), `MASS` (Float, kg).

Reads always return non-null (defaults on unset):

```java
ctx.extensions().set(TBDamageExtensions.CALIBER, 0.12f);   // 120mm
ctx.extensions().set(TBDamageExtensions.MASS, 22f);         // 22kg APFSDS

float caliber = ctx.extensions().get(TBDamageExtensions.CALIBER);
```

Register custom extension keys:

```java
public static final TBDamageExtensionKey<HitBox> HIT_BOX =
    TBDamageExtensions.register(
        ResourceLocation.fromNamespaceAndPath("my_mod", "hit_box"),
        HitBox.class, () -> null);
```

### All SI Units

| Field | Unit | Description |
|-------|------|-------------|
| `hitVelocity` | m/s | Impact velocity vector |
| `penetration` | mm | RHA equivalent penetration |
| `FUSE_DELAY` | s | Fuse delay |
| `CALIBER` | m | Projectile caliber |
| `MASS` | kg | Projectile mass |

***

## Architecture Overview

```
TBDamageApi.hurt(target, ctx)          ← weapon mod entry
    │
    ├── target instanceof TBHurtTarget
    │     ├── target.getRHA(ctx)               ← armor thickness
    │     ├── target.modifyPenetration(ctx)     ← modifier (ERA, slope)
    │     ├── target.resolvePenetration(ctx)    ← PENETRATED/BLOCKED/RICOCHET
    │     ├── target.calculateFinalDamage(ctx, result) ← final damage
    │     ├── target.hurt(source, finalDmg)     ← execute damage
    │     └── if (handler != null) → triggerCallbacks  ← callbacks
    │
    └── target instanceof LivingEntity
          └── living.hurt(source, baseDamage)   ← vanilla fallback
```

Only the `api/` package is exposed to consumers; internal implementations live in the `internal/` package.

***

## License

GNU LGPL 3.0

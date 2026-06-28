# Ballistics Framework — A Ballistics Damage Protocol Layer

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.219-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![License](https://img.shields.io/badge/License-LGPL%203.0-blue)
[![Wiki](https://img.shields.io/badge/Wiki-GitHub%20Pages-blue?logo=github)](https://sweetzonzi.github.io/BallisticsFramework/)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Sweetzonzi/BallisticsFramework)

[中文版](README.md)

***

## What is This?

BallisticsFramework is a NeoForge/Forge (1.21.1/1.20.1) library mod providing two standardized capabilities for the Minecraft modding ecosystem:

- **Terminal Ballistics Damage Protocol** — a unified penetration adjudication and damage negotiation layer for gun, vehicle, and armor mods
- **External Ballistics Solvers** — forward trajectory solving, firing angle inverse solving, and moving-target lead prediction with dual physics models (vanilla drag-multiplier & realistic quadratic drag)

It serves as a common compatibility layer that multiple mods can depend on together. Problems it solves:

- When a bullet hits armor, how do you pass high-dimensional info like **penetration, impact angle, and hit location**?
- How does an armor mod use **hit normals and velocity vectors** for penetration slope correction?
- How can multiple ballistic systems share a unified damage-armor interaction protocol?

The protocol's guiding principle is **wrap, don't replace** — it never bypasses `Entity#hurt`. For non-protocol-aware entities, damage runs entirely through vanilla.

***

## Building

```bash
./gradlew build
```

Output at `build/libs/ballistics_framework-1.21.1-neoforge-1.0.0.alpha.9.jar`.

Dependency via flatDir local jar or source-set dependency; declare in `neoforge.mods.toml`.

***

## Quick Start

### Weapon Mod — Basic Protocol Damage

```java
BFDamageContext ctx = BFDamageContext.builder()
    .source(source)                              // vanilla DamageSource
    .baseDamage(35f)                             // nominal damage
    .hitVelocity(bullet.getDeltaMovement())      // velocity (m/s)
    .hitPoint(hitResult.getLocation())           // impact point
    .hitNormal(hitResult.getDirection())         // surface normal
    .penetration(120f)                           // penetration (mm RHA)
    .build();

float dealt = BFDamageApi.hurt(target, ctx);
```

If the target implements `BFHurtTarget`, the full penetration pipeline runs automatically. Otherwise, it falls back to vanilla `hurt()` with `baseDamage`.

### Weapon Mod — Protocol Damage with Callbacks

To receive hit results (penetration/ricochet/spall/overmatch), implement `BFDamageHandler` and use `dealDamage()`. Callbacks fire in two phases — `before*` (visual feedback, pre-damage) and `on*` (post-effects, post-damage):

```java
public class MyWeapon implements BFDamageHandler {

    void fire(Entity target) {
        BFDamageContext ctx = BFDamageContext.builder()
            .source(src).baseDamage(35f).penetration(120f)
            .build();
        float dealt = this.dealDamage(target, ctx);  // auto-injects self as handler
    }

    // ===== Before-damage callbacks: visual feedback (before damage number) =====

    @Override
    public void beforePenetrated(BFHurtTarget target, BFDamageContext ctx) {
        spawnPenEffects(ctx.hitPoint());
    }

    @Override
    public void beforeBlocked(BFHurtTarget target, BFDamageContext ctx) {
        spawnSparkEffects(ctx.hitPoint());
    }

    @Override
    public void beforeRicochet(BFHurtTarget target, BFDamageContext ctx) {
        playRicochetSound(ctx.hitPoint());
    }

    // ===== After-damage callbacks: post-effects =====

    @Override
    public void onPenetrated(BFHurtTarget target, BFDamageContext ctx) {
        penetrationStats.recordHit(target, ctx);
    }

    @Override
    public void onSpall(BFHurtTarget target, BFDamageContext ctx) {
        spawnFragmentBullets(ctx.hitPoint(), ctx.hitNormal(), 4);
    }
}
```

Overmatch and spall are auto-determined by the handler's `isOvermatch()` / `isSpall()` defaults:
- **Overmatch**: penetrated AND penetration > armor × 1.5 → projectile passes intact, no spall
- **Spall**: blocked (surface shatter) OR penetrated but not overmatch (shatter during penetration) → spall
- **Ricochet**: no overmatch, no spall

Override these methods for custom logic.

### Armor Mod — Implementing BFHurtTarget

```java
public class MyTank extends LivingEntity implements BFHurtTarget {

    @Override
    public float getRHA(BFDamageContext ctx) {
        return 50f;  // differentiate hit regions via ctx.hitPoint()
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return super.hurt(source, amount);
    }

    @Override @Nullable
    public BFDamageContext createContextFromVanilla(DamageSource source, float amount) {
        return null;  // null = fall through to vanilla
    }
}
```

### Armor Mod — Ricochet Detection

Override `resolvePenetration()` to return `RICOCHET` when the impact angle is too shallow:

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

### Armor Mod — Blunt Damage / Custom Damage

`calculateFinalDamage(ctx, result)` receives the `PenetrationResult` to freely decide damage:

```java
@Override
public float calculateFinalDamage(BFDamageContext ctx, PenetrationResult result) {
    return switch (result) {
        case RICOCHET -> ctx.baseDamage() * 0.1f;   // 10% shock damage on ricochet
        case BLOCKED -> ctx.baseDamage() * 0.05f;   // 5% blunt trauma
        case PENETRATED -> ctx.baseDamage();          // 100% on penetration
    };
}
```

***

## Trajectory Solving

The framework includes external ballistics solvers covering the full flight phase from launch to impact. Choose the model based on projectile physics:

| Projectile Type | Solver | Input Units |
|-----------------|--------|:---:|
| Vanilla projectiles (arrows, snowballs, fireballs, etc.) | `MinecraftTrajectory` | m/tick |
| Custom quadratic-drag projectiles | `RealisticTrajectory` | m/s |

All classes in `api.trajectory` sub-package. Methods are `static` and thread-safe.

### Forward Solve — Trajectory Simulation

```java
// Vanilla arrow trajectory
TrajectoryResult traj = MinecraftTrajectory.arrowTrajectory(
    arrowEntity.position(), arrowEntity.getDeltaMovement(), 100);

// Realistic model — 120mm tank cannon APFSDS
BallisticConfig config = BallisticConfig.fromExtensions(exts, 0.35f, 0.8f, 0.05f, 200);
TrajectoryResult traj = RealisticTrajectory.forwardSolve(
    shooterPos, new Vec3(0, 0, 1500), config, DensityFunction.MC_OVERWORLD);

// Results feed directly into the terminal ballistics context
BFDamageContext hitCtx = BFDamageContext.builder()
    .source(src).baseDamage(500f)
    .hitPoint(traj.terminalPoint())         // impact point
    .hitVelocity(traj.terminalVelocity())   // terminal velocity (m/s), direct pass
    .build();
```

### Firing Angle — AI Turret Aiming

```java
// Vanilla arrow firing angle — one-liner
List<FiringSolution> solutions = MinecraftTrajectory.arrowFiringAngle(
    turretPos, targetPos, 6.0f, turretVelocity, 200);

if (!solutions.isEmpty()) {
    FiringSolution sol = solutions.get(0);       // low arc solution
    arrowEntity.setDeltaMovement(
        sol.direction().scale(6.0f).add(turretVelocity));
}
```

Returns two solutions (low arc + high arc) when velocity is sufficient; one at critical velocity; empty when insufficient.

### Moving Target Lead

```java
// Arrow lead prediction against a moving player
FiringSolution lead = MinecraftTrajectory.arrowWithLead(
    shooterPos, targetPos, targetVel, Vec3.ZERO,
    speed, shooterVel, 200);
```

The solver first extrapolates using `distance / speed`, then iterates to convergence — typically 2–4 rounds.

***

## Advanced Usage

### Overridable Pipeline Methods

`BFHurtTarget` defaults are based on the discrete `ArmorLevel` system. Precision mods can override step by step:

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
public float getRHA(BFDamageContext ctx) {
    BFDamageHandler h = ctx.getHandler();
    if (h instanceof ChemicalWeapon) return eraEffectiveRha;  // ERA vs HEAT
    return baseRha;
}
```

### Retrieving Context Inside hurt()

```java
@Override
public boolean hurt(DamageSource source, float amount) {
    BFDamageContext ctx = BFDamageApi.getContextFor(this);
    if (ctx != null) playHitSound(ctx.hitPoint());
    return super.hurt(source, amount);
}
```

`getContextFor(this)` returns non-null only inside the pipeline (between push and pop).

### Using Extensions

Built-in extension keys: `FUSE_DELAY` (Float, s), `CALIBER` (Float, m), `MASS` (Float, kg).

Reads always return non-null (defaults on unset):

```java
ctx.extensions().set(BFDamageExtensions.CALIBER, 0.12f);   // 120mm
ctx.extensions().set(BFDamageExtensions.MASS, 22f);         // 22kg APFSDS

float caliber = ctx.extensions().get(BFDamageExtensions.CALIBER);
```

Register custom extension keys:

```java
public static final BFDamageExtensionKey<HitBox> HIT_BOX =
    BFDamageExtensions.register(
        ResourceLocation.fromNamespaceAndPath("my_mod", "hit_box"),
        HitBox.class, () -> null);
```

### All SI Units

Trajectory solver outputs share SI units with terminal ballistics fields:

| Field | Unit | Description |
|-------|------|-------------|
| `hitVelocity` | m/s | Impact velocity vector |
| `penetration` | mm | RHA equivalent penetration |
| `FUSE_DELAY` | s | Fuse delay |
| `CALIBER` | m | Projectile caliber |
| `MASS` | kg | Projectile mass |
| `TrajectoryResult.terminalVelocity()` | m/s | Solver terminal velocity (matches `hitVelocity` — direct pass) |
| `RealisticTrajectory` all inputs | SI | m/s, kg, m, m/s² |

***

## Architecture Overview

```
                         External Ballistics (api.trajectory/)    Terminal Ballistics (api/)
                  ┌─────────────────────────┐      ┌──────────────────┐
                  │ forwardSolve             │ ───→ │ penetration check │
                  │ solveFiringAngle          │ ctx  │ damage calc       │
                  │ solveWithLead             │      │ callbacks         │
                  └─────────────────────────┘      └──────────────────┘
                       projectile in flight              moment of impact

BFDamageApi.hurt(target, ctx)          ← weapon mod entry
    │
    ├── target instanceof BFHurtTarget
    │     ├── target.getRHA(ctx)               ← armor thickness
    │     ├── target.modifyPenetration(ctx)     ← modifier (ERA, slope)
    │     ├── target.resolvePenetration(ctx)    ← PENETRATED/BLOCKED/RICOCHET
    │     ├── target.calculateFinalDamage(ctx, result) ← final damage
    │     ├── triggerBeforeCallbacks           ← before* callbacks (pre-damage)
    │     ├── target.hurt(source, finalDmg)     ← execute damage
    │     ├── target.afterHurt(ctx, result, finalDmg)
    │     └── triggerCallbacks                 ← on* callbacks (post-damage)
    │
    └── target instanceof LivingEntity
          └── living.hurt(source, baseDamage)   ← vanilla fallback
```

Only the `api/` and `api/trajectory/` packages are exposed to consumers; internal implementations live in the `internal/` package.

***

## License

GNU LGPL 3.0

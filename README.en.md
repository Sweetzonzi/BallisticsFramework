# TerminalBallistics — Terminal Ballistics Damage Protocol Layer

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.219-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![License](https://img.shields.io/badge/License-LGPL%203.0-blue)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Sweetzonzi/TerminalBallistics)

[中文版](README.md)

***

## What is This?

TerminalBallistics is a NeoForge (1.21.1) library mod that defines a **Terminal Ballistics Damage Protocol Layer** for the Minecraft modding ecosystem.

It is designed as a compatibility protocol that multiple gun, vehicle, and armor mods can depend on together, rather than a standalone damage overhaul mod. Typical problems it solves:

- When a bullet hits armor, how do you pass high-dimensional info like **penetration, impact angle, and hit location**?
- How does an armor mod use **hit normals and velocity vectors** for angled armor slope correction and penetration checks?
- How can multiple ballistic systems share a unified damage-armor interaction protocol?

The protocol's guiding principle is **wrap, don't replace** — it never bypasses `Entity#hurt`/`LivingEntity#hurt`. For entities that don't declare protocol awareness, damage runs entirely through vanilla.

## Current Status

No Maven release yet. Depend via **local jar**.

## Building

```bash
./gradlew build
```

Output is at `build/libs/terminal_ballistics-1.21.1-1.0-SNAPSHOT.jar`.

## Using as a Dependency

### Option 1: flatDir + Local Jar

Place the jar in your project's `libs/` directory and add to `build.gradle`:

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

Then declare the dependency in `neoforge.mods.toml`:

```toml
[[dependencies."your_mod_id"]]
modId = "terminal_ballistics"
type = "required"
versionRange = "[1.0-SNAPSHOT,)"
ordering = "NONE"
side = "BOTH"
```

### Option 2: Source Set Dependency (Same Workspace)

```groovy
dependencies {
    implementation project(":TerminalBallistics")
}
```

## Quick Start

### Weapon Mod Side: Firing a Protocol Damage

```java
// Build the hit context
TBDamageContext ctx = TBDamageContext.builder()
    .source(source)                              // vanilla DamageSource
    .baseDamage(35f)                             // nominal damage
    .hitVelocity(bullet.getDeltaMovement())      // projectile velocity vector
    .hitPoint(hitResult.getLocation())           // impact point
    .hitNormal(hitResult.getDirection())         // surface normal at impact
    .penetration(120f)                           // theoretical penetration (mm RHA)
    .build();

// Fire the protocol damage
float dealt = TBDamageApi.hurt(target, ctx);
```

If the target implements `TBHurtTarget`, the full armor penetration pipeline runs automatically. Otherwise, it falls back to vanilla `hurt()` with `baseDamage`.

### Armor Mod Side: Implementing TBHurtTarget

```java
public class MyArmoredEntity extends LivingEntity implements TBHurtTarget {

    // Return armor thickness for the hit location
    @Override
    public float getRHA(TBDamageContext ctx) {
        // Determine hit region based on ctx.hitPoint()
        if (isHeadShot(ctx.hitPoint())) return 30f;
        if (isChest(ctx.hitPoint())) return 50f;
        return 20f;
    }

    // Execute the actual damage
    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Custom logic, or delegate to vanilla
        return super.hurt(source, amount);
    }

    // Convert vanilla damage (melee, explosion, etc.) to protocol context
    @Override
    public TBDamageContext createContextFromVanilla(DamageSource source, float amount) {
        // Return null to skip the protocol pipeline for this damage type
        return null;
    }
}
```

### Overriding Armor Penetration

All key methods in `TBHurtTarget` have overridable default implementations:

```java
@Override
public float modifyPenetration(TBDamageContext ctx) {
    // Default: penetration / cos(θ) for slope correction
    // Override for spaced armor, ERA, composite armor, etc.
    return TBHurtTarget.super.modifyPenetration(ctx);
}

@Override
public boolean isArmorPenetrated(TBDamageContext ctx) {
    // Default: modifiedPen > getRHA
    return TBHurtTarget.super.isArmorPenetrated(ctx);
}

@Override
public float calculateFinalDamage(TBDamageContext ctx) {
    // Default: baseDamage if penetrated, 0 otherwise
    return TBHurtTarget.super.calculateFinalDamage(ctx);
}
```

### Using Extension Fields (Side-Channel Communication)

```java
// Mark ricochet in getRHA override
ctx.getExtensions().set(TBDamageExtensions.RICOCHET, true);

// Read it back after damage is dealt
boolean ricochet = ctx.getExtensions().get(TBDamageExtensions.RICOCHET);
```

The protocol ships with four standard extension keys:

- `RICOCHET` (Boolean) — ricochet flag
- `SPALL` (Boolean) — spall / fragmentation flag
- `OVERMATCH` (Boolean) — overmatch flag
- `FUSE_DELAY` (Integer) — fuse delay in ms, 0 = instant

You can also register your own extension keys on any mod's side.

### Retrieving Context Inside hurt()

When you need access to the full `TBDamageContext` (hit point, velocity, side-channel extensions) inside {@link TBHurtTarget#hurt TBHurtTarget.hurt()} for additional post-processing, call {@link TBDamageApi#getContextFor TBDamageApi.getContextFor(this)}:

```java
@Override
public boolean hurt(DamageSource source, float amount) {
    TBDamageContext ctx = TBDamageApi.getContextFor(this);
    if (ctx != null) {
        // Play different hit sounds based on location
        playArmorHitSound(ctx.hitPoint());
        // Read side-channel flags (set by getRHA override)
        if (Boolean.TRUE.equals(
                ctx.extensions().get(TBDamageExtensions.RICOCHET))) {
            spawnRicochetParticles(ctx.hitPoint(), ctx.hitNormal());
        }
        // Adjust spall count based on penetration ratio
        if (ctx.penetration() > 100f) {
            spawnSpallParticles(ctx.hitPoint(), 5);
        }
    }
    return super.hurt(source, amount);
}
```

This method returns a non-null value only inside the protocol pipeline (between push and pop). It is thread-safe and does not break existing logic.

## Architecture Overview

```
TBDamageApi.hurt(target, ctx)          ← weapon mod entry point
    │
    ├── target instanceof TBHurtTarget
    │     ├── target.getRHA(ctx)               ← armor thickness
    │     ├── target.modifyPenetration(ctx)     ← slope correction
    │     ├── target.isArmorPenetrated(ctx)     ← penetration check
    │     ├── target.calculateFinalDamage(ctx)  ← final damage calc
    │     └── target.hurt(source, finalDamage)  ← execute damage
    │
    └── target instanceof LivingEntity
          └── living.hurt(source, baseDamage)   ← vanilla fallback
```

Context is implicitly propagated through a ThreadLocal stack. Mixins inject `Entity#hurt` and `LivingEntity#hurt` to intercept non-protocol damage.

Only the `api/` package is exposed to consumers; internal implementations live in the `internal/` package.

## License

GNU LGPL 3.0

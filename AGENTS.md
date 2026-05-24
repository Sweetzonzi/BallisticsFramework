# BallisticsFramework — AGENTS.md

## Quick start

```bash
# Build
./gradlew build
# Output: build/libs/ballistics_framework-1.21.1-<version>.jar

# Run all 8 GameTests (no GUI needed)
./gradlew runGameTestServer
# Exit code 0 = all pass; check output for "All N tests passed"
```

## Project facts

- **Minecraft 1.21.1**, **NeoForge 21.1.219**, **Java 21**
- **mod_id**: `ballistics_framework`, package: `io.github.sweetzonzi.ballistics_framework`
- Current version: `1.0.0.alpha.5` (from `gradle.properties`)
- License: LGPL 3.0
- Wiki (MkDocs) at `docs/`, CI deploys to GitHub Pages

## Architecture

```
api/
  ├── (existing 12 terminal ballistics classes, zero changes)
  └── trajectory/              ← external ballistics sub-package (new)
        ├── TrajectorySample.java
        ├── TrajectoryResult.java
        ├── FiringSolution.java
        ├── BallisticConfig.java
        ├── DensityFunction.java
        ├── MinecraftTrajectory.java
        └── RealisticTrajectory.java
internal/  ← private impl — never reference from external mods
mixin/     ← Mixin injection classes
example/   ← example content (dev-only, gated by config)
```

Key entrypoints: `api/BFDamageApi.hurt(Object target, BFDamageContext ctx)` (terminal ballistics), `api/trajectory/MinecraftTrajectory` and `api/trajectory/RealisticTrajectory` (external ballistics).

Pipeline branches in `BFDamageApi.hurt()`:
1. **BFHurtTarget** → full pipeline via target's methods
2. **BFHurtTarget + BFArmorMaterial armor** → armor layer first, then entity body (double pipeline)
3. **LivingEntity w/ BFArmorMaterial armor** → adapter wraps entity
4. **Plain Entity** → vanilla `entity.hurt()` fallback

Mixin interceptors (`EntityHurtMixin`, `LivingEntityHurtMixin`) catch non-protocol damage on protocol-aware targets, redirecting through `BFHurtInterceptor`.

## Testing quirks

- GameTests live in `example/gametest/BallisticsGameTest.java` (8 scenarios)
- `@PrefixGameTestTemplate(false)` is required on the test class
- Template arena (5x3x5 stone bricks) is built **programmatically** in `@BeforeBatch`, NOT loaded from a `.nbt` file
- Use `CallbackRecorder` (inner class) for callback verification, not log scraping
- Floating-point comparisons use epsilon `0.01f`
- Test assertions throw `GameTestAssertException` (NOT JUnit assertions)
- Each test must call `helper.succeed()` explicitly

## Important conventions

- `BFDamageExtensions.init()` must be called before any API use (done in mod constructor)
- All values in SI units: penetration in **mm** RHA, velocity in **m/s**, caliber in **m**, mass in **kg**
- Trajectory solver input units: **MinecraftTrajectory** uses m/tick (matching `Entity.getDeltaMovement()`), output auto-converts to m/s (×20). **RealisticTrajectory** uses full SI (m/s for velocity, kg for mass, m for dimensions).
- Both solvers auto-detect degenerate paths (no-gravity → linear motion, no-drag → analytic parabola) — users call the same method regardless.
- `BFDamageContext` is a `record` — use `Builder` or `withHandler()`/`childContext()` for modifications
- `BFDamageApi.hurt()` return value may NOT equal actual HP loss (vanilla armor applies secondary reduction)
- Weapon mods use `BFDamageHandler.dealDamage(target, ctx)` to auto-inject handler
- Context stack uses identity (`==`) comparison for re-entry guard
- Pipeline call order: `getRHA` → `modifyPenetration` → `resolvePenetration` → `calculateFinalDamage` → `hurt`
- For composite targets (branch 0), penetration callback triggers based on entity body result, not armor layer

## CI / Release

- **Release**: push tag `v*` → builds with JDK 21, attaches jar to GitHub Release (prerelease)
- **Pages**: push to `1.21.1-neoforge` branch with `docs/**` changes → builds MkDocs
- Release workflow reads `gradle.properties` for mod metadata; also supports `workflow_dispatch` with version override

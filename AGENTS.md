# BallisticsFramework — AGENTS.md

## Quick start

```bash
# Build
./gradlew build
# Output: build/libs/<mod_id>-<minecraft_version>-<loader>-<version>.jar
#   (pattern from build.gradle archivesName; loader is "neoforge" or "forge")

# Run all 8 GameTests (no GUI needed)
./gradlew runGameTestServer
# Exit code 0 = all pass; check output for "All N tests passed"
```

## Project facts

- **This repository carries one branch per loader**: `1.21.1-neoforge` (NeoForge, Java 21) and `1.20.1-forge` (Forge, Java 17). Platform details are branch-specific and authoritative in the checked-out branch's own config — read `gradle.properties` (`minecraft_version`, `neo_version` or `forge_version`, `mod_version`) and `build.gradle` (`archivesName`, `java.toolchain.languageVersion`). The branch name doubles as the release artifact's `-<loader>` suffix.
- **mod_id**: `ballistics_framework`, package: `io.github.sweetzonzi.ballistics_framework` (identical on both branches)
- Current version: `1.0.0.alpha.11` (from `gradle.properties`)
- License: LGPL 3.0
- Wiki (MkDocs) at `docs/`; GitHub Pages deploys only from `1.21.1-neoforge` (see CI / Release)

## Architecture

```
api/
  ├── (existing 12 terminal ballistics classes, zero changes)
  └── trajectory/              ← external ballistics sub-package
        ├── TrajectorySample.java
        ├── TrajectoryResult.java
        ├── FiringSolution.java
        ├── BallisticConfig.java
        ├── DensityFunction.java
        ├── MinecraftTrajectory.java
        └── RealisticTrajectory.java
internal/  ← private impl — never reference from external mods
mixin/     ← Mixin injection classes
example/   ← example content (dev-only; enabled when !FMLLoader.isProduction())
```

Key entrypoints: `api/BFDamageApi.hurt(Object target, BFDamageContext ctx)` (terminal ballistics entry), `api/BFDamageApi.deliverTo` (terminal ballistics carrier delivery), `api/trajectory/MinecraftTrajectory` and `api/trajectory/RealisticTrajectory` (external ballistics).

Pipeline branches in `BFDamageApi.hurt()` (编号与源码注释、`docs/wiki/4-协议内幕/4.1-穿甲判定管线.md` 一致，判定顺序即编号顺序):
0. **BFHurtTarget + BFArmorMaterial armor** → armor layer first (with armor-layer callbacks), then entity body (with body-layer callbacks) — double pipeline with dual callback rounds
1. **BFHurtTarget** → full pipeline via target's methods
2. **LivingEntity w/ BFArmorMaterial armor** → adapter wraps entity
3. **Plain Entity** → vanilla `entity.hurt()` fallback

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
- Handler callbacks fire in two phases per penetration layer: `before*` callbacks (pre-`hurt()`) and `on*` callbacks (post-`hurt()`)
- For composite targets (branch 0), callbacks fire for BOTH armor layer and entity body layer — two rounds of callbacks

## CI / Release

- **Release**: push tag `v*` → the workflow picks the JDK from the loader key present in `gradle.properties` (`forge_version` → 17, `neo_version` → 21), attaches the jar to a GitHub Release (prerelease)
- **Pages**: push to `1.21.1-neoforge` branch with `docs/**` changes → builds MkDocs
- Release workflow reads `gradle.properties` for mod metadata; also supports `workflow_dispatch` with version override

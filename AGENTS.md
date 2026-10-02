# BallisticsFramework — AGENTS.md

## Quick start

```bash
# Build
./gradlew build
# Output: build/libs/<mod_id>-<minecraft_version>-<loader>-<version>.jar
#   (pattern from build.gradle archivesName; loader is "neoforge" or "forge")

# Run all 24 GameTests (no GUI needed)
./gradlew runGameTestServer
# Exit code 0 = all pass; check output for "All N required tests passed"
```

## Project facts

- **This repository carries one branch per loader**: `1.21.1-neoforge` (NeoForge, Java 21) and `1.20.1-forge` (Forge, Java 17). Platform details are branch-specific and authoritative in the checked-out branch's own config — read `gradle.properties` (`minecraft_version`, `neo_version` or `forge_version`, `mod_version`) and `build.gradle` (`archivesName`, `java.toolchain.languageVersion`). The branch name doubles as the release artifact's `-<loader>` suffix.
- **mod_id**: `ballistics_framework`, package: `io.github.sweetzonzi.ballistics_framework` (identical on both branches)
- Current version: `1.0.0.alpha.13` (from `gradle.properties`)
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
1.5. **Pure BFHitResolver** (implements `BFHitResolver` but not `BFHurtTarget`) → `resolveHit(ctx.hitPoint(), BFHitResolver.searchDelta(ctx.hitVelocity()))`, then the damage is forwarded to the resolved actual target through branch 1 using a context rebuilt by `BFDamageApi.contextForResolvedTarget` (corrected hit point/normal applied, resolver extensions merged). Returns `0f` when the resolve reports a miss; contract in `docs/BFDamageApi-hurt解析器转发计划.md`
2. **LivingEntity w/ BFArmorMaterial armor** → adapter wraps entity
3. **Plain Entity** → vanilla `entity.hurt()` fallback (which re-enters the interceptor, so it may still be routed by interceptor cases 2/3/4)
4. **Neither BFHurtTarget nor Entity** → logs an error and returns `0f` (no branch can receive the damage)

Both forwarding paths — branch 1.5 here and case 3 of `BFHurtInterceptor` — rebuild the forwarded context through `BFDamageApi.contextForResolvedTarget`, so the resolved `correctedHitPoint` / `correctedHitNormal` reach the actual target (armor-side `mapHitToSlot` maps slots from `ctx.hitPoint()`, and the incoming point is the proxy AABB intersection).

Mixin interceptors (`EntityHurtMixin`, `LivingEntityHurtMixin`) catch non-protocol damage on protocol-aware targets, redirecting through `BFHurtInterceptor`.

## Testing quirks

- GameTests live in `example/gametest/BallisticsGameTest.java` (24 scenarios: 8 penetration-pipeline + 11 carrier-delivery + 5 resolver-forwarding)
- `@PrefixGameTestTemplate(false)` is required on the test class on both loaders (the default prefixes the template name with the class name)
- Template arena (5x3x5 stone bricks): on `1.21.1-neoforge` it is built programmatically in `@BeforeBatch`; on `1.20.1-forge` it is the data-pack structure `data/ballistics_framework/structures/empty_arena.nbt` (note the `structures/` directory, which 1.20.1 requires)
- Use `CallbackRecorder` (inner class) for callback verification, not log scraping; the delivery scenarios use `DeliveryRecorder`, which counts `before*` / `on*` calls and forces `isOvermatch` / `isSpall` to false so armor-item call counts stay exact
- Carrier-delivery scenarios need the dev-only fixtures `example/entity/ExampleProxyEntity.java` (host / resolver, no `BFHurtTarget`), `example/entity/ExampleCarrierEntity.java` (carrier that is also a `BFHurtTarget`, with body-layer call counters and probes) and `example/item/ExampleDeliveryArmorItem.java` (15mm RHA armor whose three-piece and `afterHurt` calls are counted; call `ExampleDeliveryArmorItem.resetCounters()` before asserting on them)
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
- Carrier delivery (`api/BFDamageApi.deliverTo`): pushes the carrier, runs only the carrier's `BFArmorMaterial` layer (skippable via `ignoreBFArmor`), never re-runs the body layer and never re-routes through `BFHitResolver`. The plan `docs/BFDamageApi-deliverTo投递计划.md` owns the exact contract
- `BFDamageApi.hurt()` does route: a target implementing `BFHitResolver` without `BFHurtTarget` resolves through `resolveHit` and the damage is forwarded to the resolved actual target. The plan `docs/BFDamageApi-hurt解析器转发计划.md` owns the contract and the `hurt` / `deliverTo` split

## Documentation

Applies to `docs/**` (including the `mkdocs.yml` nav) and to code comments / Javadoc.

- **Every reference must be locatable.** For this repository's source, write `path#symbol` with a symbol that literally appears in the file — a method name, a field name, a nested type, a distinctive marker comment. Examples: `api/BFDamageApi.java#hurt`, `internal/BFContextStack.java#hasContextFor`, `internal/BFArmorAdapter.java#resolveBestSlot`. **Do not anchor to line numbers on their own**: they drift silently, break nothing loudly, and end up pointing at unrelated code. A line number is acceptable only when the exact historical revision is the point — then pin it as `path:line @ <commit>`, which `git show <commit>:<path>` reproduces. For work that is planned but not yet implemented, cite the owning document's own `§N` instead, and switch it to `path#symbol` once the symbol lands. For a sibling repository, prefix the repository name — `Machine-Max` + `common/mech/vehicle/SubPart.java#settleAccumulatedDamage`. For an external library (Minecraft, NeoForge, Forge), use the fully qualified class or method name; its sources are not in this repository, so a line number there is unverifiable and must not be used.
- **Cross-references are required, not forbidden.** A document does not have to restate everything it depends on; it must give a coordinate the reader can follow.
- **But every fact has exactly one home.** Definitions, thresholds and decision rationales live in one document; everywhere else references them. If a second document restates a rule it must cite the owner — never fork a local copy, because a forked copy drifts and two documents then disagree about the truth. Wiki pages summarise; the design plans and the code own the exact contract. When the same rule appears in several places, the fix is to pick an owner and point the rest at it.
- **Never describe the current state relative to a previous state.** Phrases such as "no longer uses X", "still works", "changed to Y", "(was Z)" assume the reader knows an older version. The test: would this sentence be unambiguous and verifiable to someone who has only ever seen the current file — no git history, no earlier revision, no conversation? If not, state the current fact absolutely, put the baseline in the same sentence, or move the comparison into a revision-history section at the end.
- **When behaviour changes, sweep every document that describes it.** Search the symbol name and the affected `§` references across `docs/**` and this file before reporting done, and keep the `mkdocs.yml` nav in step with added, renamed or removed pages.

## CI / Release

- **Release**: push tag `v*` → the workflow picks the JDK from the loader key present in `gradle.properties` (`forge_version` → 17, `neo_version` → 21), attaches the jar to a GitHub Release (prerelease)
- **Pages**: push to `1.21.1-neoforge` branch with `docs/**` changes → builds MkDocs
- Release workflow reads `gradle.properties` for mod metadata; also supports `workflow_dispatch` with version override

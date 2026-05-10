# A.2 常见问题

## 基础概念

### Q: TerminalBallistics 是什么？它是独立模组吗？

它是 NeoForge 的 lib 库模组。单独安装不会对游戏产生任何可见变化——它只为依赖它的枪械、载具、护甲模组提供一套标准化的弹道伤害协议。所有公开 API 集中在 `api` 包中。

### Q: 我的模组如何依赖它？

在 `build.gradle` 中声明对 jar 的 `implementation` 依赖，然后在 `neoforge.mods.toml` 中添加：

```toml
[[dependencies."你的modid"]]
    modId = "terminal_ballistics"
    type = "required"
    versionRange = "[1.0,)"
```

详见 [1.2 安装与依赖配置](../1-快速上手/1.2-安装与依赖配置.md)。

### Q: TerminalBallistics 和原版护甲系统（Armor/ArmorMaterial）冲突吗？

不冲突。协议设计原则是"包裹原版，不替代原版"。对于实现了 `TBHurtTarget` 的实体，协议伤害走完整穿甲管线后再进入原版 `hurt`——如果 `hurt` 内调用了 `super.hurt()`，原版护甲还会再削减一次。如果你想完全避开原版护甲，可以不调用 `super.hurt` 而直接操作 health。

***

## 上下文构造

### Q: build() 时报 NullPointerException，哪里错了？

`source` 是 Builder 唯一必须设置的字段。如果未调用 `.source(damageSource)`，`build()` 会抛出异常。其他字段都有默认值——只设 `source` 也能得到一个合法的上下文。

### Q: getDeltaMovement() 的值不对，速度似乎太小了？

`getDeltaMovement()` 的单位是 blocks/tick，而不是 m/s。协议要求 `hitVelocity` 的单位是 m/s。Minecraft 每秒运行 20 tick，你需要乘以 20：

```java
Vec3 hitVelocity = projectileEntity.getDeltaMovement().scale(20.0);
```

### Q: 穿深（penetration）应该填什么值？

填**已经经过角度修正的有效穿深**，单位 mm RHA。角度修正（`垂直穿深 / cosθ`）应由武器侧在构造上下文前完成，协议不默认修正。填 0 表示该伤害没有穿甲能力；填 `Float.MAX_VALUE` 表示绝对穿透。

### Q: extensions 需要手动创建吗？

不需要。如果 Builder 未调用 `.extensions(exts)`，`build()` 会自动创建一个空的 `TBDamageExtensions` 实例。`ctx.extensions()` 永远不为 null。

***

## 装甲判定

### Q: 我让实体实现了 TBHurtTarget，但子弹打它仍然绕过了装甲？

检查以下几点：

1. `getArmorLevel(ctx)` 是否被正确覆写？是否漏了 `@Override`？
2. 是否误将 `createContextFromVanilla` 返回了 `null` 导致原版伤害绕过了管线？
3. 武器侧是否通过 `TBDamageApi.hurt()` 发起了伤害？如果武器侧没有依赖本 lib，伤害会走原版路径，不会触发穿甲管线。

### Q: 默认判定下，穿深刚好等于装甲厚度时算击穿吗？

算。简易模式下等级比较使用 `>=`（`canDefeat`），等于算击穿。此时伤害打六五折（`baseDamage × 65%`）。如果需要严格大于判定，覆写 `resolvePenetration` 使用 `modifiedPenetration > getRHA` 即可。

### Q: getRHA 和 modifyPenetration 有什么区别？什么时候该覆写哪个？

`getRHA` 描述**装甲自身的防护能力**——这块钢板有多少毫米等效厚度。与弹丸无关。

`modifyPenetration` 描述**弹丸在穿透此装甲时的能力衰减**——ERA 拦截了 150mm、间隙偏转损失了 20mm。与弹丸有关。

覆写 `getRHA` 当你的装甲需要精确的毫米级厚度而非离散等级。覆写 `modifyPenetration` 当你的装甲有主动防御或特殊衰减机制。两者职责独立，不要在 `modifyPenetration` 中重复 `getRHA` 的计算。

### Q: 默认没有跳弹判定，我该怎么加？

覆写 `resolvePenetration`。在方法内先根据入射角判断是否跳弹（通常角度 > 70° 判定为跳弹），再做穿深比较。示例见 [3.4 最终伤害计算](../3-护甲侧开发/3.4-最终伤害计算.md)。

***

## 扩展机制

### Q: 如何在武器侧和护甲侧之间传递自定义数据？

使用扩展机制。在武器侧通过 `exts.set(key, value)` 写入，护甲侧通过 `ctx.extensions().get(key)` 读取。Key 通过 `TBDamageExtensions.register()` 注册并保存为 `public static final` 常量。详见 [2.5 侧信道扩展](../2-武器侧开发/2.5-侧信道扩展.md)。

### Q: 注册扩展 key 时报异常 "duplicate key"？

同一个 `ResourceLocation` 只能注册一次。检查是否有其他模组使用了相同的 id。建议 key 的命名空间使用自己的 mod id 以避免冲突。

### Q: 未设置扩展值时 get() 返回什么？

返回 key 注册时指定的默认值（`defaultValueFactory`）。`get()` 永不返回 null。因此护甲侧读取扩展时不需要判空。

### Q: 能在回调（onPenetrated 等）中读取扩展值吗？

可以。回调触发时上下文栈尚未出栈，你可以在回调方法内通过 `ctx.extensions().get(key)` 正常读取。回调的参数也直接包含了 `ctx`，无需调用 `getContextFor`。

***

## 协议外伤害兼容

### Q: 为什么僵尸打我的装甲实体还能造成伤害？

因为你在 `createContextFromVanilla` 中返回了 `null`——原版攻击不走穿甲管线。如果想让僵尸攻击也被装甲减免，需要在该方法中构造一个低信息量上下文并返回。详见 [3.5 协议外伤害兼容](../3-护甲侧开发/3.5-协议外伤害兼容.md)。

### Q: 构造低信息量上下文后，环境伤害（岩浆、溺水）也被装甲挡住了，不正常？

在 `createContextFromVanilla` 中对 `DamageSource` 类型做筛选——对岩浆、溺水、虚空、魔法等不适合走穿甲管线的伤害类型返回 `null`，只对物理攻击和爆炸返回上下文。

***

## 回调

### Q: 我设置了 handler 但没有收到回调？

检查以下几点：

1. 目标是否实现了 `TBHurtTarget`？普通实体走原版 `entity.hurt()`，不会触发回调。
2. 是否使用了 `dealDamage(target, ctx)` 或 `ctx.withHandler(handler)`？直接用 `TBDamageApi.hurt(target, ctx)` 时，如果上下文中没有注入 handler，回调不会触发。
3. 穿甲结果对应的回调是否被覆写？例如 `onBlocked` 被调用但你没有覆写它。

### Q: 一次命中最少触发几个回调？

最少一个（主结果回调），最多两个（主结果 + onOvermatch 或 onSpall）。跳弹（RICOCHET）只触发 `onRicochet`，不触发超匹配和破片。

### Q: 回调中能知道实际造成了多少伤害吗？

`TBDamageApi.hurt()` 的返回值就是实际造成的伤害量。在回调中你也可以通过 `ctx.getHandler() instanceof MyHandler` 等方式间接获取——但最直接的方式是保存 `hurt()` 的返回值。

***

## 线程与并发

### Q: 协议的 ThreadLocal 上下文栈是什么意思？我需要关心吗？

不需要。`TBDamageApi.hurt()` 内部自动完成 ThreadLocal 栈的压入（push）和弹出（pop），你只需调用 `hurt()` 即可。`getContextFor()` 和 `hasContextFor()` 都是通过这个栈工作的，但你不必直接操作它。

### Q: 能在非主线程调用 TBDamageApi.hurt() 吗？

协议本身不限制线程。但如果 `target.hurt()` 内部访问了 Minecraft 非线程安全的世界状态，可能会出问题。建议始终在主线程发起伤害。

***

## Mixin 兼容性

### Q: TerminalBallistics 的 Mixin 会和其他模组冲突吗？

协议仅注入 `Entity#hurt` 和 `LivingEntity#hurt` 的 HEAD 阶段（`cancellable=true`）。如果目标不是 `TBHurtTarget`，注入点直接放行，不会影响其他模组。如果多个 mod 同时注入同一方法的同一阶段，Mixin 的注入顺序由依赖关系决定——通常不会冲突，但如果遇到问题，可以在 issue tracker 中报告。

### Q: 我的护甲模组也想注入 Entity#hurt，会冲突吗？

如果你的注入点和 TerminalBallistics 注入同一个方法，且都是 HEAD 阶段，两个注入都会执行（Mixin 允许多个注入共存）。需要注意不要让你的注入逻辑和 `createContextFromVanilla` 产生循环——如果 Mixin 拦截到协议管线内部的 `hurt` 调用，协议的重入守卫（`hasContextFor`）应能阻止循环。

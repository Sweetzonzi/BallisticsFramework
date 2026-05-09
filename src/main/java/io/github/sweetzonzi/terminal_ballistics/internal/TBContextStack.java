package io.github.sweetzonzi.terminal_ballistics.internal;

import io.github.sweetzonzi.terminal_ballistics.api.TBDamageContext;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ThreadLocal 上下文栈（内部实现）。
 * <p>
 * 管理 {@code ThreadLocal<Deque<StackEntry>>} 的压栈/出栈/查询。
 * 栈元素为 {@code (target, context)} 对，用于精确判断"当前上下文的所属实体"，
 * 从而区分同一调用链内的重入（同一目标）与副作用产生的新伤害（不同目标，如荆棘反伤）。
 * <p>
 * 仅被 {@code TBDamageApi} 和 mixin 类调用，不暴露给外部 mod。
 */
public final class TBContextStack {

    public static final TBContextStack INSTANCE = new TBContextStack();

    private final ThreadLocal<Deque<Entry>> stack = ThreadLocal.withInitial(ArrayDeque::new);

    private TBContextStack() {}

    private record Entry(Object target, TBDamageContext ctx) {}

    /**
     * 压栈。
     *
     * @param target 当前伤害的目标实体
     * @param ctx    当前伤害的上下文
     */
    public void push(Object target, TBDamageContext ctx) {
        stack.get().push(new Entry(target, ctx));
    }

    /**
     * 出栈。
     *
     * @throws java.util.NoSuchElementException 如果栈为空（表示调用链不匹配的 bug）
     */
    public void pop() {
        stack.get().pop();
    }

    /**
     * 检查栈顶上下文的所属者是否为给定的目标。
     * <p>
     * 用于 mixin 中的重入守卫：如果当前栈顶的 target 与调用 {@code hurt()} 的实体相同，
     * 说明是在协议管线内部调用，不应再次拦截。
     *
     * @param target 要检查的目标
     * @return true 如果栈非空且栈顶 target 与参数相同
     */
    public boolean hasContextFor(Object target) {
        Deque<Entry> deque = stack.get();
        return !deque.isEmpty() && deque.peek().target() == target;
    }
}

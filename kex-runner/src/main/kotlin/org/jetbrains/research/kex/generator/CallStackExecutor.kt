package org.jetbrains.research.kex.generator

import org.jetbrains.research.kex.ExecutionContext
import org.jetbrains.research.kex.trace.`object`.TraceCollectorProxy
import org.jetbrains.research.kex.util.getConstructor
import org.jetbrains.research.kex.util.getMethod
import org.jetbrains.research.kex.util.loadClass
import java.lang.reflect.Array

class CallStackExecutor(val ctx: ExecutionContext) {
    private val cache = mutableMapOf<CallStack, Any?>()

    fun execute(callStack: CallStack): Any? {
        if (callStack in cache) return cache[callStack]

        TraceCollectorProxy.initializeEmptyCollector(ctx.cm)

        var current: Any? = null
        for (call in callStack) {
            current = when (call) {
                is PrimaryValue<*> -> call.value
                is DefaultConstructorCall -> {
                    val reflection = ctx.loader.loadClass(call.klass)
                    val defaultConstructor = reflection.getDeclaredConstructor()
                    val instance = defaultConstructor.newInstance()
                    cache[callStack] = instance
                    instance
                }
                is ConstructorCall -> {
                    val reflection = ctx.loader.loadClass(call.klass)
                    val constructor = reflection.getConstructor(call.constructor, ctx.loader)
                    constructor.isAccessible = true
                    val args = call.args.map { execute(it) }.toTypedArray()
                    val instance = constructor.newInstance(*args)
                    cache[callStack] = instance
                    instance
                }
                is ExternalConstructorCall -> {
                    val reflection = ctx.loader.loadClass(call.constructor.`class`)
                    val javaMethod = reflection.getMethod(call.constructor, ctx.loader)
                    javaMethod.isAccessible = true
                    val args = call.args.map { execute(it) }.toTypedArray()
                    val instance = javaMethod.invoke(null, *args)
                    cache[callStack] = instance
                    instance
                }
                is MethodCall -> {
                    val reflection = ctx.loader.loadClass(call.method.`class`)
                    val javaMethod = reflection.getMethod(call.method, ctx.loader)
                    javaMethod.isAccessible = true
                    val args = call.args.map { execute(it) }.toTypedArray()
                    javaMethod.invoke(current, *args)
                    current
                }
                is NewArray -> {
                    val length = execute(call.length)!! as Int
                    val elementReflection = ctx.loader.loadClass(call.klass)
                    Array.newInstance(elementReflection, length)
                }
                is ArrayWrite -> {
                    val index = execute(call.index)!! as Int
                    val value = execute(call.value)
                    Array.set(current, index, value)
                    current
                }
                is FieldSetter -> {
                    val field = call.field
                    val reflection = ctx.loader.loadClass(field.`class`)
                    val fieldReflection = reflection.getField(field.name)
                    val value = execute(call.value)
                    when {
                        field.isStatic -> fieldReflection.set(null, value)
                        else -> fieldReflection.set(current, value)
                    }
                    current
                }
                is UnknownCall -> {
                    val reflection = ctx.loader.loadClass(call.klass)
                    ctx.random.nextOrNull(reflection)
                }
                else -> null
            }
        }

        return current
    }
}
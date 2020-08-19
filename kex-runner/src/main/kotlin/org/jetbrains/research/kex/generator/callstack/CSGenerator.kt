package org.jetbrains.research.kex.generator.callstack

import com.abdullin.kthelper.collection.queueOf
import com.abdullin.kthelper.logging.log
import org.jetbrains.research.kex.asm.util.Visibility
import org.jetbrains.research.kex.asm.util.visibility
import org.jetbrains.research.kex.config.kexConfig
import org.jetbrains.research.kex.generator.descriptor.ArrayDescriptor
import org.jetbrains.research.kex.generator.descriptor.Descriptor
import org.jetbrains.research.kex.generator.descriptor.ObjectDescriptor
import org.jetbrains.research.kex.generator.externalConstructors
import org.jetbrains.research.kex.generator.hasSetter
import org.jetbrains.research.kex.generator.setter
import org.jetbrains.research.kex.ktype.KexArray
import org.jetbrains.research.kex.ktype.KexType
import org.jetbrains.research.kex.ktype.type
import org.jetbrains.research.kfg.ir.Method
import java.util.*

private val visibilityLevel by lazy { kexConfig.getEnumValue("apiGeneration", "visibility", true, Visibility.PUBLIC) }
private val maxStackSize by lazy { kexConfig.getIntValue("apiGeneration", "maxStackSize", 5) }

interface CSGenerator {
    val context: GeneratorContext

    fun supports(type: KexType): Boolean
    fun generate(descriptor: Descriptor, depth: Int = 0): CallStack
}

class AnyGenerator(private val fallback: CSGenerator) : CSGenerator {
    override val context get() = fallback.context

    override fun supports(type: KexType) = true

    override fun generate(descriptor: Descriptor, depth: Int): CallStack = with(context) {
        descriptor as? ObjectDescriptor ?: throw IllegalArgumentException()

        val name = "${descriptor.term}"
        val callStack = CallStack(name)
        descriptor.cache(callStack)
        callStack.generateObject(descriptor, depth)
        return callStack
    }

    private fun CallStack.generateObject(descriptor: ObjectDescriptor, generationDepth: Int) = with(context) {
        val original = descriptor.deepCopy()

        descriptor.concretize(cm)
        descriptor.reduce()

        log.debug("Generating $descriptor")

        val klass = descriptor.klass.kfgClass(types)
        if ((klass.accessibleConstructors + klass.externalConstructors).isEmpty()) {
            this@generateObject += UnknownCall(klass.type, original)
            return
        }

        val setters = descriptor.generateSetters(generationDepth)
        val queue = queueOf(GeneratorContext.ExecutionStack(descriptor, setters, 0))
        while (queue.isNotEmpty()) {
            val (desc, stack, depth) = queue.poll()
            val current = descriptor.accept(desc)
            if (depth > maxStackSize) continue

            for (method in klass.accessibleConstructors) {
                val (thisDesc, args) = method.executeAsConstructor(current) ?: continue

                if (thisDesc.isFinal(current)) {
                    log.debug("Found constructor $method for $descriptor, generating arguments $args")
                    val constructorCall = when {
                        method.argTypes.isEmpty() -> DefaultConstructorCall(klass)
                        else -> {
                            val generatedArgs = args.map { fallback.generate(it, generationDepth + 1) }
                            ConstructorCall(klass, method, generatedArgs)
                        }
                    }
                    this@generateObject.stack += (stack + constructorCall).reversed()
                    return
                }
            }

            for (method in klass.externalConstructors) {
                val (_, args) = method.executeAsExternalConstructor(current) ?: continue

                val generatedArgs = args.map { fallback.generate(it, generationDepth + 1) }
                val constructorCall = ExternalConstructorCall(method, generatedArgs)
                this@generateObject.stack += (stack + constructorCall).reversed()
                return
            }

            for (method in klass.accessibleMethods) {
                val result = method.executeAsSetter(current) ?: continue
                acceptExecutionResult(result, current, depth, generationDepth, stack, method, queue)
            }

            for (method in klass.accessibleMethods) {
                val result = method.executeAsMethod(current) ?: continue
                acceptExecutionResult(result, current, depth, generationDepth, stack, method, queue)
            }
        }

        this@generateObject += UnknownCall(klass.type, original)
    }

    private fun ObjectDescriptor.generateSetters(generationDepth: Int): List<ApiCall> = with(context) {
        val calls = mutableListOf<ApiCall>()
        val kfgKlass = klass.kfgClass(types)
        for ((field, value) in fields.toMap()) {
            val kfgField = kfgKlass.getField(field.first, field.second.getKfgType(types))

            if (visibilityLevel <= kfgField.visibility) {
                log.debug("Directly setting field $field value")
                calls += FieldSetter(kfgField, fallback.generate(value, generationDepth + 1))
                fields.remove(field)
                reduce()

            } else if (kfgField.hasSetter && visibilityLevel <= kfgField.setter.visibility) {
                log.info("Using setter for $field")

                val (result, args) = kfgField.setter.executeAsSetter(this@generateSetters) ?: continue
                if (result != null && (result[field] == null || result[field] == field.second.defaultDescriptor)) {
                    val remapping = { mutableMapOf<Descriptor, Descriptor>(result to this@generateSetters) }
                    val generatedArgs = args.map { fallback.generate(it.deepCopy(remapping()), generationDepth + 1) }
                    calls += MethodCall(kfgField.setter, generatedArgs)
                    accept(result)
                    reduce()
                    log.info("Used setter for field $field, new desc: $this")
                }
            }
        }
        return calls
    }

    private fun acceptExecutionResult(res: GeneratorContext.ExecutionResult,
                                      current: ObjectDescriptor,
                                      oldDepth: Int,
                                      generationDepth: Int,
                                      stack: List<ApiCall>, method: Method,
                                      queue: Queue<GeneratorContext.ExecutionStack>) {
        val (result, args) = res
        if (result != null) {
            val remapping = { mutableMapOf<Descriptor, Descriptor>(result to current) }
            val generatedArgs = args.map { fallback.generate(it.deepCopy(remapping()), generationDepth + 1) }
            val newStack = stack + MethodCall(method, generatedArgs)
            val newDesc = result.merge(current)
            queue += GeneratorContext.ExecutionStack(newDesc, newStack, oldDepth + 1)
        }
    }
}

class ArrayGenerator(private val fallback: CSGenerator) : CSGenerator {
    override val context get() = fallback.context

    override fun supports(type: KexType) = type is KexArray

    override fun generate(descriptor: Descriptor, depth: Int): CallStack = with(context) {
        descriptor as? ArrayDescriptor ?: throw IllegalArgumentException()

        val name = "${descriptor.term}"
        val callStack = CallStack(name)
        descriptor.cache(callStack)

        val elementType = descriptor.elementType.getKfgType(types)
        val lengthCall = PrimaryValue(descriptor.length).wrap("${name}Length")
        val array = NewArray(elementType, lengthCall)
        callStack += array

        descriptor.elements.forEach { (index, value) ->
            val indexCall = PrimaryValue(index).wrap("${name}Index")
            val arrayWrite = ArrayWrite(indexCall, fallback.generate(value, depth + 1))
            callStack += arrayWrite
        }

        callStack
    }
}
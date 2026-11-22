/**
 * MaidLang 虚拟机 (VM)
 *
 * 执行由编译器生成的字节码。
 * 采用基于栈的执行模型，支持常量加载、变量存取、算术运算、控制流和函数调用。
 */

import kotlin.collections.ArrayDeque
import MaidValue
import Opcode
import BytecodeChunk
import OpType
import AstNode
import Scope

private val EMPTY_LOCALS = emptyArray<MaidValue>()

/**
 * 调用帧，表示一个函数的执行上下文。
 */
private class Frame(
    val chunk: BytecodeChunk,
    val start: Int,
    val end: Int,
    val stackStart: Int,
    var ip: Int = 0,
    val localCount: Int = 0
) {
    val locals: Array<MaidValue> =
        if (localCount == 0) EMPTY_LOCALS else Array(localCount) { MaidValue.NullVal }

    fun readOpcode(): Opcode = Opcode.fromValue(readInt())

    fun readInt(): Int {
        check(ip in start until end) { "Instruction operand is outside the current frame" }
        return chunk.code[ip++]
    }

    fun jumpTo(offset: Int) {
        check(offset in start..end) { "Jump target $offset is outside the current frame [$start, $end]" }
        ip = offset
    }

    fun readLocalSlot(): Int = readInt().also {
        check(it in 0 until localCount) { "Invalid local variable slot: $it" }
    }
}

/**
 * MaidLang 虚拟机。
 */
class VM(
    val nativeRegistry: NativeRegistry = NativeRegistry(),
    val maxCallDepth: Int = 1024,
    val maxStackSize: Int = 65536,
    val maxInstructions: Long = Long.MAX_VALUE
) {
    init {
        require(maxCallDepth > 0) { "maxCallDepth must be positive" }
        require(maxStackSize > 0) { "maxStackSize must be positive" }
        require(maxInstructions > 0) { "maxInstructions must be positive" }
    }

    /** 宿主显式定义的全局变量表（名称 -> 值）。 */
    private val globals = mutableMapOf<String, MaidValue>()

    /** 单次执行期间的按槽位全局变量表（执行结束自动清空，避免跨次运行泄漏）。 */
    private val globalSlots = mutableMapOf<Int, MaidValue>()
    private var executing = false
    
    /** 操作数栈。 */
    private val stack = ArrayDeque<MaidValue>()
    
    /** 调用栈。 */
    private val callStack = ArrayDeque<Frame>()
    
    /** 定义全局变量。 */
    fun defineGlobal(name: String, value: MaidValue) {
        globals[name] = value
    }

    /** Registers a host function referenced by a compiled external fun declaration. */
    fun registerNative(name: String, func: NativeFunction) {
        nativeRegistry.register(name, func)
    }
    
    /**
     * 执行一个字节码块（顶级代码或函数）。
     */
    fun interpret(chunk: BytecodeChunk): MaidValue {
        return try {
            execute(chunk)
        } catch (e: Exception) {
            println("VM runtime error: ${e.message}")
            e.printStackTrace()
            MaidValue.NullVal
        }
    }

    /** Executes bytecode and reports errors to the host as exceptions. */
    fun execute(chunk: BytecodeChunk): MaidValue {
        check(!executing) { "The same VM cannot run another program during a native call" }
        executing = true
        try {
            return runChunk(chunk)
        } finally {
            stack.clear()
            callStack.clear()
            globalSlots.clear()
            executing = false
        }
    }

    private fun runChunk(chunk: BytecodeChunk): MaidValue {
        val mainEnd = chunk.functions.firstOrNull()?.entry ?: chunk.code.size
        check(mainEnd in 0..chunk.code.size) { "Invalid main chunk boundary: $mainEnd" }
        callStack.addLast(Frame(chunk, start = 0, end = mainEnd, stackStart = 0))
        var instructions = 0L
        while (callStack.isNotEmpty()) {
            val frame = callStack.last()
            if (frame.ip == frame.end) finishFrame(frame)
            else {
                check(instructions < maxInstructions) { "Instruction limit exceeded (max: $maxInstructions)" }
                instructions++
                executeInstruction(frame)
            }
        }
        return stack.lastOrNull() ?: MaidValue.NullVal
    }

    private fun push(value: MaidValue) {
        check(stack.size < maxStackSize) { "Operand stack overflow (max size: $maxStackSize)" }
        stack.addLast(value)
    }

    private fun pop(frame: Frame): MaidValue {
        check(stack.size > frame.stackStart) { "Operand stack underflow" }
        return stack.removeLast()
    }

    private fun finishFrame(frame: Frame) {
        val result = if (stack.size > frame.stackStart) pop(frame) else MaidValue.NullVal
        while (stack.size > frame.stackStart) pop(frame)
        callStack.removeLast()
        push(result)
    }

    /**
     * 执行当前帧的一条指令。
     */
    private fun executeInstruction(frame: Frame) {
        val opcode = frame.readOpcode()
        when (opcode) {
            // 常量加载
            Opcode.LOAD_INT, Opcode.LOAD_FLOAT, Opcode.LOAD_STRING, Opcode.LOAD_CHAR -> {
                val constIndex = frame.readInt()
                check(constIndex in 0 until frame.chunk.constants.size()) { "Invalid constant index: $constIndex" }
                val value = frame.chunk.constants.getConstant(constIndex)
                push(value)
            }
            Opcode.LOAD_NULL -> {
                push(MaidValue.NullVal)
            }
            Opcode.LOAD_TRUE -> {
                push(MaidValue.IntVal(1))
            }
            Opcode.LOAD_FALSE -> {
                push(MaidValue.IntVal(0))
            }
            
            // 变量存取
            Opcode.LOAD_VAR -> {
                val varIndex = frame.readInt()
                check(varIndex >= 0) { "Invalid global variable slot: $varIndex" }
                val value = globalSlots[varIndex]
                    ?: (if (globals.isEmpty()) null else globals["var$varIndex"])
                    ?: MaidValue.NullVal
                push(value)
            }
            Opcode.STORE_VAR -> {
                val varIndex = frame.readInt()
                check(varIndex >= 0) { "Invalid global variable slot: $varIndex" }
                val value = pop(frame)
                globalSlots[varIndex] = value
            }
            
            Opcode.LOAD_LOCAL -> {
                val slot = frame.readLocalSlot()
                push(frame.locals[slot])
            }
            Opcode.STORE_LOCAL -> frame.locals[frame.readLocalSlot()] = pop(frame)
            Opcode.POP -> pop(frame)

            // 二元运算
            Opcode.ADD -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.ADD, a, b)
                push(result)
            }
            Opcode.SUB -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.SUB, a, b)
                push(result)
            }
            Opcode.MUL -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.MUL, a, b)
                push(result)
            }
            Opcode.DIV -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.DIV, a, b)
                push(result)
            }
            Opcode.MOD -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.MOD, a, b)
                push(result)
            }
            Opcode.GREATER -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.GREATER, a, b)
                push(result)
            }
            Opcode.LESS -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.LESS, a, b)
                push(result)
            }
            Opcode.GREATER_EQUAL -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.GREATER_EQUAL, a, b)
                push(result)
            }
            Opcode.LESS_EQUAL -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.LESS_EQUAL, a, b)
                push(result)
            }
            Opcode.EQUAL -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.EQUAL, a, b)
                push(result)
            }
            Opcode.NOT_EQUAL -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.NOT_EQUAL, a, b)
                push(result)
            }
            Opcode.LOGICAL_AND -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.LOGICAL_AND, a, b)
                push(result)
            }
            Opcode.LOGICAL_OR -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.LOGICAL_OR, a, b)
                push(result)
            }
            Opcode.BIT_AND -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.BIT_AND, a, b)
                push(result)
            }
            Opcode.BIT_OR -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.BIT_OR, a, b)
                push(result)
            }
            Opcode.BIT_XOR -> {
                val b = pop(frame)
                val a = pop(frame)
                val result = applyBinaryOp(OpType.BIT_XOR, a, b)
                push(result)
            }
            
            // 一元运算
            Opcode.NEGATE -> {
                val value = pop(frame)
                val result = applyUnaryOp(OpType.SUB, value, null, null) // 使用 SUB 作为负号（需特殊处理）
                push(result)
            }
            Opcode.NOT -> {
                val value = pop(frame)
                val result = applyUnaryOp(OpType.NOT, value, null, null)
                push(result)
            }
            
            // 控制流
            Opcode.JUMP -> {
                val offset = frame.readInt()
                frame.jumpTo(offset)
            }
            Opcode.JUMP_IF_TRUE -> {
                val offset = frame.readInt()
                val condition = pop(frame)
                if (condition.asInt() != 0) {
                    frame.jumpTo(offset)
                }
            }
            Opcode.JUMP_IF_FALSE -> {
                val offset = frame.readInt()
                val condition = pop(frame)
                if (condition.asInt() == 0) {
                    frame.jumpTo(offset)
                }
            }
            
            Opcode.CALL -> callFunction(frame)
            Opcode.CALL_NATIVE -> callNative(frame)
            Opcode.RETURN -> finishFrame(frame)

            // 其他指令暂不实现
            else -> {
                throw IllegalStateException("Unimplemented opcode: $opcode")
            }
        }
    }

    private fun callFunction(caller: Frame) {
        val index = caller.readInt()
        val function = caller.chunk.functions.getOrNull(index) ?: error("Invalid function index: $index")
        check(callStack.size < maxCallDepth) { "Call stack overflow (max depth: $maxCallDepth)" }
        check(function.arity >= 0 && function.localCount in function.arity..MAX_FRAME_LOCALS) {
            "Invalid function frame size for function #$index"
        }
        check(stack.size - caller.stackStart >= function.arity) { "Not enough arguments for function #$index" }
        val stackStart = stack.size - function.arity
        val end = caller.chunk.functions.getOrNull(index + 1)?.entry ?: caller.chunk.code.size
        check(function.entry in 0..end && end <= caller.chunk.code.size) { "Invalid function entry for function #$index" }
        val frame = Frame(
            caller.chunk,
            start = function.entry,
            end = end,
            stackStart = stackStart,
            ip = function.entry,
            localCount = function.localCount
        )
        for (slot in function.arity - 1 downTo 0) frame.locals[slot] = pop(caller)
        callStack.addLast(frame)
    }

    private fun callNative(frame: Frame) {
        val (name, signature) = frame.chunk.nativeCall(frame.readInt(), frame.readInt())
        val function = nativeRegistry[name] ?: error("External function '$name' is not registered in this VM")
        val count = signature.parameters.size
        check(stack.size - frame.stackStart >= count) { "Not enough arguments for external function '$name'" }
        val start = stack.size - count
        val args = List(count) { index ->
            val value = stack[start + index]
            val type = signature.parameters[index]
            require(type.accepts(value)) { "External function '$name' argument ${index + 1} must be ${type.typeName}" }
            value
        }
        repeat(count) { pop(frame) }
        val result = function(args)
        require(signature.result.accepts(result)) { "External function '$name' must return ${signature.result.typeName}" }
        push(result)
    }
    
    /**
     * 应用二元运算（与解释器共享同一套运算和比较语义）。
     */
    private fun applyBinaryOp(op: OpType, left: MaidValue, right: MaidValue): MaidValue =
        evalBinaryOp(op, left, right)
    
    /**
     * 应用一元运算。
     */
    private fun applyUnaryOp(op: OpType, value: MaidValue, argNode: AstNode?, scope: Scope?): MaidValue {
        return when (op) {
            OpType.NOT -> MaidValue.IntVal(if (value.asInt() == 0) 1 else 0)
            OpType.SUB -> if (value is MaidValue.FloatVal) MaidValue.FloatVal(-value.value) else MaidValue.IntVal(-value.asInt())
            else -> MaidValue.NullVal
        }
    }
}

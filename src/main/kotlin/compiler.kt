/**
 * MaidLang 编译器
 *
 * 将抽象语法树（AST）编译为虚拟机字节码。
 */

import MaidValue
import AstNode
import OpType
import Opcode
import BytecodeChunk

/**
 * 编译上下文，记录编译过程中的状态。
 */
class CompilationContext {
    internal val externalFunctions = mutableMapOf<String, NativeSignature>()
    internal val functions = mutableMapOf<String, AstNode.FuncDefNode>()
    internal val functionIndices = mutableMapOf<String, Int>()
    private val localScopes = mutableListOf<MutableMap<String, Int>>()
    internal var localCount = 0
        private set

    internal fun beginFunction(parameters: List<String>) {
        localCount = 0
        enterScope()
        for (name in parameters) declareLocal(name)
    }

    internal fun enterScope() {
        localScopes.add(mutableMapOf())
    }

    internal fun exitScope() {
        localScopes.removeLast()
    }

    internal fun declareLocal(name: String): Int? {
        val scope = localScopes.lastOrNull() ?: return null
        require(name !in scope) { "Local variable '$name' is already declared in this scope" }
        val slot = localCount++
        scope[name] = slot
        return slot
    }

    internal fun findLocal(name: String): Int? {
        for (index in localScopes.indices.reversed()) {
            localScopes[index][name]?.let { return it }
        }
        return null
    }

    internal val inFunction get() = localScopes.isNotEmpty()
    /** 当前正在编译的字节码块。 */
    val chunk = BytecodeChunk()
    
    /** 全局变量名到常量池索引的映射（临时方案）。 */
    private val globalVarIndices = mutableMapOf<String, Int>()
    private var nextVarIndex = 0
    
    /** 获取或分配一个全局变量的索引。 */
    fun getOrAllocVarIndex(name: String): Int {
        return globalVarIndices.getOrPut(name) { nextVarIndex++ }
    }
    
    /** 常量池快捷方法。 */
    fun addConstant(value: MaidValue): Int = chunk.constants.addConstant(value)
}

/**
 * 编译器主类。
 */
class Compiler {
    /** 编译一个 AST 节点，返回生成的字节码块。 */
    fun compile(node: AstNode): BytecodeChunk {
        val context = CompilationContext()
        val declarations = if (node is AstNode.CodeBlock) node.codes else listOf(node)
        for (function in declarations.filterIsInstance<AstNode.FuncDefNode>()) {
            require(function.name !in context.functions) { "Function '${function.name}' is already declared" }
            context.functionIndices[function.name] = context.functions.size
            context.functions[function.name] = function
        }
        for (declaration in declarations.filterIsInstance<AstNode.ExternalFuncDecl>()) {
            require(declaration.name !in context.externalFunctions && declaration.name !in context.functions) {
                "External function '${declaration.name}' is already declared"
            }
            context.externalFunctions[declaration.name] = NativeSignature(
                declaration.paramTypes.map(NativeType::fromType),
                NativeType.fromType(declaration.returnType)
            )
        }
        compileNode(node, context, topLevel = true)
        for (function in context.functions.values) {
            val entry = context.chunk.code.size
            context.beginFunction(function.args)
            compileNode(function.body, context)
            context.chunk.emit(Opcode.RETURN)
            context.chunk.functions.add(CompiledFunction(entry, function.args.size, context.localCount))
            context.exitScope()
        }
        return context.chunk
    }
    
    /** 递归编译节点。 */
    private fun compileNode(
        node: AstNode,
        context: CompilationContext,
        topLevel: Boolean = false,
        wantValue: Boolean = true
    ) {
        when (node) {
            is AstNode.IntNode -> {
                val constIndex = context.addConstant(MaidValue.IntVal(node.value))
                context.chunk.emit(Opcode.LOAD_INT, constIndex)
            }
            is AstNode.FloatNode -> {
                val constIndex = context.addConstant(MaidValue.FloatVal(node.value))
                context.chunk.emit(Opcode.LOAD_FLOAT, constIndex)
            }
            is AstNode.StringNode -> {
                val constIndex = context.addConstant(MaidValue.StringVal(node.value))
                context.chunk.emit(Opcode.LOAD_STRING, constIndex)
            }
            is AstNode.CharNode -> {
                val constIndex = context.addConstant(MaidValue.CharVal(node.value))
                context.chunk.emit(Opcode.LOAD_CHAR, constIndex)
            }
            is AstNode.Variable -> {
                when (node.name) {
                    "true" -> context.chunk.emit(Opcode.LOAD_TRUE)
                    "false" -> context.chunk.emit(Opcode.LOAD_FALSE)
                    else -> {
                        val slot = context.findLocal(node.name)
                        if (slot == null) context.chunk.emit(Opcode.LOAD_VAR, context.getOrAllocVarIndex(node.name))
                        else context.chunk.emit(Opcode.LOAD_LOCAL, slot)
                    }
                }
            }
            is AstNode.BinaryOp -> {
                // 先编译左操作数，再编译右操作数
                compileNode(node.arg1, context)
                compileNode(node.arg2, context)
                // 根据操作符生成对应的字节码
                val opcode = when (node.op) {
                    OpType.ADD -> Opcode.ADD
                    OpType.SUB -> Opcode.SUB
                    OpType.MUL -> Opcode.MUL
                    OpType.DIV -> Opcode.DIV
                    OpType.MOD -> Opcode.MOD
                    OpType.GREATER -> Opcode.GREATER
                    OpType.LESS -> Opcode.LESS
                    OpType.GREATER_EQUAL -> Opcode.GREATER_EQUAL
                    OpType.LESS_EQUAL -> Opcode.LESS_EQUAL
                    OpType.EQUAL -> Opcode.EQUAL
                    OpType.NOT_EQUAL -> Opcode.NOT_EQUAL
                    OpType.LOGICAL_AND -> Opcode.LOGICAL_AND
                    OpType.LOGICAL_OR -> Opcode.LOGICAL_OR
                    OpType.BIT_AND -> Opcode.BIT_AND
                    OpType.BIT_OR -> Opcode.BIT_OR
                    OpType.BIT_XOR -> Opcode.BIT_XOR
                    else -> throw IllegalArgumentException("Unsupported binary operator: ${node.op}")
                }
                context.chunk.emit(opcode)
            }
            is AstNode.Unary -> {
                compileNode(node.arg, context)
                val opcode = when (node.op) {
                    OpType.NOT -> Opcode.NOT
                    OpType.SUB -> Opcode.NEGATE  // 负号
                    else -> throw IllegalArgumentException("Unsupported unary operator: ${node.op}")
                }
                context.chunk.emit(opcode)
            }
            is AstNode.Assign -> {
                // 赋值：先编译右值，然后存储到左值变量
                // 左值目前只支持 Variable 节点（暂不考虑 ProxyVar）
                if (node.left is AstNode.Variable) {
                    require(node.left.name != "true" && node.left.name != "false") { "Cannot assign to a boolean literal" }
                    compileNode(node.right, context)
                    val slot = if (node.declaration) context.declareLocal(node.left.name) else context.findLocal(node.left.name)
                    if (slot == null) {
                        val varIndex = context.getOrAllocVarIndex(node.left.name)
                        context.chunk.emit(Opcode.STORE_VAR, varIndex)
                        if (wantValue && !node.declaration) context.chunk.emit(Opcode.LOAD_VAR, varIndex)
                    } else {
                        context.chunk.emit(Opcode.STORE_LOCAL, slot)
                        if (wantValue && !node.declaration) context.chunk.emit(Opcode.LOAD_LOCAL, slot)
                    }
                } else {
                    throw IllegalStateException("Unsupported assignment target: ${node.left}")
                }
            }
            is AstNode.CodeBlock -> {
                val localScope = context.inFunction
                if (localScope) context.enterScope()
                val lastStatement = node.codes.indexOfLast { it !is AstNode.ExternalFuncDecl && it !is AstNode.FuncDefNode }
                for ((index, stmt) in node.codes.withIndex()) {
                    val declaration = stmt is AstNode.ExternalFuncDecl || stmt is AstNode.FuncDefNode
                    compileNode(stmt, context, topLevel && declaration, wantValue && index == lastStatement)
                }
                if (localScope) context.exitScope()
            }
            is AstNode.If -> {
                // 条件跳转：先编译条件表达式
                compileNode(node.condition, context)
                // 生成条件跳转指令，跳过 then 分支
                val jumpIfFalsePos = context.chunk.code.size
                context.chunk.emit(Opcode.JUMP_IF_FALSE, 0) // 偏移量占位
                // 编译 then 分支
                compileNode(node.thenBranch, context, wantValue = wantValue)
                // 如果有 else 分支，需要跳过 else 分支
                val jumpPos = context.chunk.code.size
                context.chunk.emit(Opcode.JUMP, 0) // 占位
                // 修正 JUMP_IF_FALSE 的偏移量，跳转到 else 分支或之后
                val elseStart = context.chunk.code.size
                context.chunk.code[jumpIfFalsePos + 1] = elseStart
                // 编译 else 分支（如果有）
                if (node.elseBranch != null) {
                    compileNode(node.elseBranch, context, wantValue = wantValue)
                }
                // 修正 JUMP 的偏移量，跳转到代码结尾
                val afterElse = context.chunk.code.size
                context.chunk.code[jumpPos + 1] = afterElse
            }
            is AstNode.While -> {
                val loopStart = context.chunk.code.size
                // 编译条件
                compileNode(node.condition, context)
                // 条件为假时跳出循环
                val jumpIfFalsePos = context.chunk.code.size
                context.chunk.emit(Opcode.JUMP_IF_FALSE, 0) // 占位
                // 编译循环体
                compileNode(node.body, context, wantValue = false)
                // 循环体结束后跳回条件检查
                context.chunk.emit(Opcode.JUMP, loopStart)
                // 修正跳出偏移量
                val afterLoop = context.chunk.code.size
                context.chunk.code[jumpIfFalsePos + 1] = afterLoop
            }
            is AstNode.For -> {
                val localScope = context.inFunction
                if (localScope) context.enterScope()
                node.initializer?.let { compileNode(it, context, wantValue = false) }

                val loopStart = context.chunk.code.size
                // 编译条件（条件为 null 时视为 true，但编译模式下需生成 LOAD_TRUE）
                if (node.condition != null) {
                    compileNode(node.condition, context)
                } else {
                    context.chunk.emit(Opcode.LOAD_TRUE)
                }
                // 条件为假时跳出循环
                val jumpIfFalsePos = context.chunk.code.size
                context.chunk.emit(Opcode.JUMP_IF_FALSE, 0) // 占位
                // 编译循环体
                compileNode(node.body, context, wantValue = false)
                // 编译增量子句
                node.increment?.let { compileNode(it, context, wantValue = false) }
                // 循环体结束后跳回条件检查
                context.chunk.emit(Opcode.JUMP, loopStart)
                // 修正跳出偏移量
                val afterLoop = context.chunk.code.size
                context.chunk.code[jumpIfFalsePos + 1] = afterLoop
                if (localScope) context.exitScope()
            }
            is AstNode.FuncDefNode -> {
                require(topLevel) { "Function definitions must be at the top level in compiled mode" }
            }
            is AstNode.FuncCall -> {
                val function = context.functions[node.funName]
                val signature = context.externalFunctions[node.funName]
                val arity = function?.args?.size ?: signature?.parameters?.size
                    ?: error("Function '${node.funName}' is not defined or declared in compiled mode")
                require(node.args.size == arity) {
                    "Function '${node.funName}' expects $arity arguments, got ${node.args.size}"
                }
                for (arg in node.args) compileNode(arg, context)
                if (function != null) {
                    context.chunk.emit(Opcode.CALL, context.functionIndices.getValue(node.funName))
                } else {
                    val nameIndex = context.addConstant(MaidValue.StringVal(node.funName))
                    val signatureIndex = context.addConstant(MaidValue.StringVal(checkNotNull(signature).encode()))
                    context.chunk.emit(Opcode.CALL_NATIVE, nameIndex, signatureIndex)
                }
            }
            is AstNode.Return -> {
                // 返回值：编译返回值表达式，然后发出 RETURN 指令
                if (node.value != null) {
                    compileNode(node.value, context)
                } else {
                    context.chunk.emit(Opcode.LOAD_NULL)
                }
                context.chunk.emit(Opcode.RETURN)
            }
            is AstNode.ExternalFuncDecl -> {
                require(topLevel) { "External function declarations must be at the top level in compiled mode" }
            }
            is AstNode.ProxyVar -> {
                // 数组/代理访问：暂不实现
                throw IllegalStateException("ProxyVar compilation not implemented")
            }
        }
        if (!wantValue && producesValue(node)) context.chunk.emit(Opcode.POP)
    }

    private fun producesValue(node: AstNode): Boolean = when (node) {
        is AstNode.IntNode, is AstNode.FloatNode, is AstNode.CharNode, is AstNode.StringNode,
        is AstNode.Variable, is AstNode.Unary, is AstNode.BinaryOp, is AstNode.FuncCall -> true
        else -> false
    }
}

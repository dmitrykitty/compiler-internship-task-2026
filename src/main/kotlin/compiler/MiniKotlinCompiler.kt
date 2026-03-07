package org.example.compiler

import MiniKotlinBaseVisitor


/**
 * Compiler from a MiniKotlin subset to Java in continuation-passing style (CPS).
 *
 * Main compilation rules:
 * - every non-main function is translated to a Java method with an extra
 *   `Continuation<T>` parameter,
 * - function calls inside expressions are lowered into nested continuations,
 * - mutable local variables are represented as single-element arrays so they can
 *   be safely captured and updated inside Java lambdas,
 * - `&&` and `||` preserve Kotlin short-circuit semantics,
 * - `==` and `!=` are compiled using `java.util.Objects.equals(...)` to better
 *   match Kotlin equality semantics,
 * - function calls inside `while` conditions are supported by lowering the loop
 *   to a recursive `Runnable`.
 */
class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {
    /**
     * Shared counter used to generate unique temporary names for CPS arguments
     * (`arg0`, `arg1`, ...) and synthetic loop variables (`loop0`, `loop1`, ...).
     */
    private var tempCounter = 0

    /**
     * Stack of local scopes.
     *
     * Each scope stores source-level variable names declared in the corresponding
     * block. A variable declared in a local scope is compiled as a boxed mutable
     * cell (`Type[]`) and must later be referenced through `[0]`.
     */
    private val localScopes = mutableListOf<MutableSet<String>>()

    /**
     * Compiles the whole MiniKotlin program into a single Java class.
     *
     * The class name is sanitized so it does not collide with Java keywords.
     */
    fun compile(
        program: MiniKotlinParser.ProgramContext,
        className: String = "MiniProgram"
    ): String {
        val safeClassName = sanitizeJavaIdentifier(className)

        val functions = program.functionDeclaration()
            .joinToString("\n\n") { indent(compileFunction(it).trimEnd()) }

        return buildString {
            appendLine("public class $safeClassName {")
            if (functions.isNotBlank()) {
                appendLine()
                appendLine(functions)
                appendLine()
            }
            appendLine("}")
        }
    }

    /**
     * Compiles a single function declaration.
     *
     * - `main` is emitted as a standard Java entry point,
     * - every other function gets an extra continuation parameter,
     * - `Unit` functions that may naturally fall through get an implicit
     *   `__continuation.accept(null); return;` appended at the end.
     */
    private fun compileFunction(ctx: MiniKotlinParser.FunctionDeclarationContext): String {
        resetFunctionState()

        val originalFunctionName = ctx.IDENTIFIER().text
        val kotlinReturnType = ctx.type().text
        val isMain = originalFunctionName == "main"

        val header = buildFunctionHeader(ctx, originalFunctionName, kotlinReturnType)
        val body = compileBlock(ctx.block(), isMain, kotlinReturnType)

        val finalBody = if (shouldEmitImplicitUnitReturn(ctx, isMain, kotlinReturnType)) {
            joinCode(body, "__continuation.accept(null);\nreturn;")
        } else {
            body
        }

        return wrapInBraces(header, finalBody)
    }

    /**
     * Compiles statements in order while threading through the code that should
     * run afterwards (`fallthroughCode`).
     *
     * This method is the core of statement sequencing in CPS form:
     * - it detects special cases that need CPS lowering,
     * - it appends the "rest of the computation" after the current statement,
     * - it stops when it reaches a terminal statement.
     */
    private fun compileStatementsSequentially(
        statements: List<MiniKotlinParser.StatementContext>,
        index: Int,
        isMain: Boolean,
        kotlinReturnType: String,
        fallthroughCode: String = ""
    ): String {
        if (index >= statements.size) return fallthroughCode

        val current = statements[index]
        fun rest(): String =
            compileStatementsSequentially(statements, index + 1, isMain, kotlinReturnType, fallthroughCode)

        compileCpsVariableDeclaration(current, ::rest)?.let { return it }
        compileCpsVariableAssignment(current, ::rest)?.let { return it }
        compileCpsCallStatement(current, ::rest)?.let { return it }
        compileCpsWhileStatement(current, ::rest, isMain, kotlinReturnType)?.let { return it }

        val currentCode = compileStatement(current, isMain, kotlinReturnType).trimEnd()
        if (currentCode.isBlank()) return rest()
        if (isTerminal(current)) return currentCode

        return joinCode(currentCode, rest())
    }

    /**
     * Handles a variable declaration whose initializer contains a function call.
     *
     * Such a declaration cannot be emitted directly, because the initializer value
     * becomes available only through a continuation. The declaration is therefore
     * delayed until the expression has been CPS-lowered.
     */
    private fun compileCpsVariableDeclaration(
        statement: MiniKotlinParser.StatementContext,
        rest: () -> String
    ): String? {
        val declaration = statement.variableDeclaration() ?: return null
        val expr = declaration.expression()

        if (!hasFunctionCall(expr)) return null

        val originalName = declaration.IDENTIFIER().text
        val safeName = sanitizeJavaIdentifier(originalName)
        val type = toJavaType(declaration.type().text)

        return compileExpressionCps(expr) { value ->
            declareLocal(originalName)
            joinCode(
                "final $type[] $safeName = new $type[]{$value};",
                rest()
            )
        }
    }

    /**
     * Handles an assignment whose right-hand side contains a function call.
     *
     * The assignment itself is postponed until the CPS-transformed expression
     * produces its value.
     */
    private fun compileCpsVariableAssignment(
        statement: MiniKotlinParser.StatementContext,
        rest: () -> String
    ): String? {
        val assignment = statement.variableAssignment() ?: return null
        val expr = assignment.expression()

        if (!hasFunctionCall(expr)) return null

        val name = assignment.IDENTIFIER().text

        return compileExpressionCps(expr) { value ->
            joinCode(
                "${resolveReference(name)} = $value;",
                rest()
            )
        }
    }

    /**
     * Handles `while` loops whose condition contains a function call.
     *
     * A normal Java `while (...)` cannot directly express a CPS condition, so the
     * loop is lowered into a recursive `Runnable`. Each iteration:
     * - evaluates the CPS condition,
     * - executes the body when true,
     * - jumps back to the synthetic loop runner,
     * - or continues with the code after the loop when false.
     */
    private fun compileCpsWhileStatement(
        statement: MiniKotlinParser.StatementContext,
        rest: () -> String,
        isMain: Boolean,
        kotlinReturnType: String
    ): String? {
        val whileStatement = statement.whileStatement() ?: return null
        val condition = whileStatement.expression()

        if (!hasFunctionCall(condition)) return null

        val loopName = nextLoopName()
        val loopBackCode = "$loopName[0].run();"

        val bodyCode = compileBlock(
            whileStatement.block(),
            isMain,
            kotlinReturnType,
            loopBackCode
        )

        val conditionCode = compileExpressionCps(condition) { conditionValue ->
            buildIf(
                condition = conditionValue,
                thenBody = bodyCode,
                elseBody = rest()
            )
        }

        return buildRecursiveLoop(loopName, conditionCode)
    }

    /**
     * Handles expression statements that are plain function calls.
     *
     * Example:
     * `foo(x)` becomes `foo(x, (arg0) -> { ...rest... });`
     */
    private fun compileCpsCallStatement(
        statement: MiniKotlinParser.StatementContext,
        rest: () -> String
    ): String? {
        val expr = statement.expression() as? MiniKotlinParser.FunctionCallExprContext ?: return null

        val originalFunctionName = expr.IDENTIFIER().text
        val safeFunctionName = sanitizeJavaIdentifier(originalFunctionName)
        val arguments = expr.argumentList()?.expression() ?: emptyList()

        return compileArgumentsCps(arguments) { compiledArgs ->
            val tempName = nextTempName()

            if (originalFunctionName == "println") {
                val value = compiledArgs.singleOrNull()
                    ?: error("println expects exactly one argument")

                buildContinuationCall("Prelude.println", listOf(value), tempName, rest())
            } else {
                buildContinuationCall(safeFunctionName, compiledArgs, tempName, rest())
            }
        }
    }

    /**
     * Compiles a block in its own nested scope.
     *
     * `fallthroughCode` represents code that should run when the block finishes
     * normally, for example the continuation of a CPS loop body.
     */
    private fun compileBlock(
        ctx: MiniKotlinParser.BlockContext,
        isMain: Boolean,
        kotlinReturnType: String,
        fallthroughCode: String = ""
    ): String = withScope {
        compileStatementsSequentially(ctx.statement(), 0, isMain, kotlinReturnType, fallthroughCode)
            .trimEnd()
    }

    /**
     * Compiles a single statement in the direct, non-sequential sense.
     *
     * Sequencing and CPS-aware control flow are handled by
     * [compileStatementsSequentially].
     */
    private fun compileStatement(
        ctx: MiniKotlinParser.StatementContext,
        isMain: Boolean,
        kotlinReturnType: String
    ): String {
        return when {
            ctx.variableDeclaration() != null ->
                compileVariableDeclaration(ctx.variableDeclaration())

            ctx.variableAssignment() != null ->
                compileVariableAssignment(ctx.variableAssignment())

            ctx.ifStatement() != null ->
                compileIfStatement(ctx.ifStatement(), isMain, kotlinReturnType)

            ctx.whileStatement() != null ->
                compileWhileStatement(ctx.whileStatement(), isMain, kotlinReturnType)

            ctx.returnStatement() != null ->
                compileReturnStatement(ctx.returnStatement(), isMain)

            ctx.expression() != null ->
                compileExpressionStatement(ctx.expression())

            else -> ""
        }
    }

    /**
     * Compiles an `if` statement.
     *
     * When the condition contains a function call, the condition is first lowered
     * through CPS; otherwise it is emitted as a normal Java conditional.
     */
    private fun compileIfStatement(
        ctx: MiniKotlinParser.IfStatementContext,
        isMain: Boolean,
        kotlinReturnType: String
    ): String {
        val condition = ctx.expression()
        val thenBody = compileBlock(ctx.block(0), isMain, kotlinReturnType)
        val elseBody = if (ctx.block().size > 1) {
            compileBlock(ctx.block(1), isMain, kotlinReturnType)
        } else {
            null
        }

        return if (hasFunctionCall(condition)) {
            compileExpressionCps(condition) { conditionValue ->
                buildIf(conditionValue, thenBody, elseBody)
            }
        } else {
            buildIf(visit(condition), thenBody, elseBody)
        }
    }

    /**
     * Compiles a `while` loop whose condition has no function calls.
     *
     * The CPS-aware `while` case is handled separately by [compileCpsWhileStatement].
     */
    private fun compileWhileStatement(
        ctx: MiniKotlinParser.WhileStatementContext,
        isMain: Boolean,
        kotlinReturnType: String
    ): String {
        val condition = ctx.expression()
        val body = compileBlock(ctx.block(), isMain, kotlinReturnType)

        return wrapInBraces("while (${visit(condition)})", body)
    }

    /**
     * Compiles a return statement.
     *
     * For non-main functions, returning means invoking the continuation and then
     * immediately exiting the Java method.
     */
    private fun compileReturnStatement(
        ctx: MiniKotlinParser.ReturnStatementContext,
        isMain: Boolean
    ): String {
        val expr = ctx.expression()

        if (isMain) {
            return if (expr != null) "return ${visit(expr)};" else "return;"
        }

        if (expr == null) {
            return "__continuation.accept(null);\nreturn;"
        }

        return compileExpressionCps(expr) { value ->
            "__continuation.accept($value);\nreturn;"
        }
    }

    /**
     * Compiles a variable assignment in the non-CPS case.
     *
     * The right-hand side is emitted directly because it contains no function calls.
     * The left-hand side is resolved through [resolveReference], so local mutable
     * variables become `name[0]` while parameters remain plain identifiers.
     */
    private fun compileVariableAssignment(
        ctx: MiniKotlinParser.VariableAssignmentContext
    ): String {
        val name = ctx.IDENTIFIER().text
        val value = visit(ctx.expression())
        return "${resolveReference(name)} = $value;"
    }

    /**
     * Compiles an expression used as a standalone statement.
     *
     * Only function-call expressions produce meaningful Java statements here.
     * Pure expressions such as `1 + 2` are ignored because evaluating them has no
     * effect in the generated program.
     */
    private fun compileExpressionStatement(
        expr: MiniKotlinParser.ExpressionContext
    ): String {
        return if (expr is MiniKotlinParser.FunctionCallExprContext) {
            "${visit(expr)};"
        } else {
            ""
        }
    }

    /**
     * Compiles a variable declaration in the non-CPS case.
     *
     * Local variables are boxed into single-element arrays so they can later be
     * mutated from inside Java lambdas.
     */
    private fun compileVariableDeclaration(
        ctx: MiniKotlinParser.VariableDeclarationContext
    ): String {
        val originalName = ctx.IDENTIFIER().text
        val safeName = sanitizeJavaIdentifier(originalName)
        val type = toJavaType(ctx.type().text)
        val value = visit(ctx.expression())

        declareLocal(originalName)
        return "final $type[] $safeName = new $type[]{$value};"
    }


    /**
     * Sequentially CPS-compiles call arguments from left to right.
     *
     * This preserves evaluation order before the enclosing function call is emitted.
     */
    private fun compileArgumentsCps(
        arguments: List<MiniKotlinParser.ExpressionContext>,
        onComplete: (List<String>) -> String
    ): String {
        fun loop(index: Int, acc: List<String>): String {
            if (index >= arguments.size) return onComplete(acc)

            return compileExpressionCps(arguments[index]) { value ->
                loop(index + 1, acc + value)
            }
        }

        return loop(0, emptyList())
    }

    /**
     * Lowers an expression into CPS only when needed.
     *
     * Expressions without function calls are emitted directly.
     * Expressions containing calls are recursively transformed so that all needed
     * intermediate values become available through continuations.
     */
    private fun compileExpressionCps(
        expr: MiniKotlinParser.ExpressionContext,
        onComplete: (String) -> String
    ): String {
        if (!hasFunctionCall(expr)) {
            return onComplete(visit(expr))
        }

        return when (expr) {
            is MiniKotlinParser.FunctionCallExprContext ->
                compileFunctionCallExpressionCps(expr, onComplete)

            is MiniKotlinParser.NotExprContext ->
                compileExpressionCps(expr.expression()) { value ->
                    onComplete("(!($value))")
                }

            is MiniKotlinParser.AndExprContext ->
                compileLazyAnd(expr, onComplete)

            is MiniKotlinParser.OrExprContext ->
                compileLazyOr(expr, onComplete)

            else -> {
                val binary = extractBinaryOperation(expr)
                if (binary != null) {
                    compileBinaryExpressionCps(binary, onComplete)
                } else {
                    onComplete(visit(expr))
                }
            }
        }
    }

    /**
     * CPS-compiles a direct function call expression.
     *
     * The produced temporary continuation argument name is then passed to
     * [onComplete] as the value of the whole expression.
     */
    private fun compileFunctionCallExpressionCps(
        expr: MiniKotlinParser.FunctionCallExprContext,
        onComplete: (String) -> String
    ): String {
        val originalFunctionName = expr.IDENTIFIER().text
        val safeFunctionName = sanitizeJavaIdentifier(originalFunctionName)
        val arguments = expr.argumentList()?.expression() ?: emptyList()

        return compileArgumentsCps(arguments) { compiledArgs ->
            val tempName = nextTempName()
            buildContinuationCall(
                safeFunctionName,
                compiledArgs,
                tempName,
                onComplete(tempName)
            )
        }
    }

    /**
     * CPS-compiles a binary expression.
     *
     * Equality operators are emitted specially to preserve Kotlin-like equality
     * semantics through `Objects.equals(...)`.
     */
    private fun compileBinaryExpressionCps(
        operation: BinaryOperation,
        onComplete: (String) -> String
    ): String {
        return compileExpressionCps(operation.left) { leftValue ->
            compileExpressionCps(operation.right) { rightValue ->
                val rendered = when (operation.operator) {
                    "==" -> renderEquality(leftValue, rightValue, isEqual = true)
                    "!=" -> renderEquality(leftValue, rightValue, isEqual = false)
                    else -> "($leftValue ${operation.operator} $rightValue)"
                }
                onComplete(rendered)
            }
        }
    }

    /**
     * Preserves Kotlin short-circuit semantics for `&&`.
     *
     * The right-hand side is compiled only when the left-hand side evaluates to true.
     */
    private fun compileLazyAnd(
        expr: MiniKotlinParser.AndExprContext,
        onComplete: (String) -> String
    ): String {
        val left = expr.expression(0)
        val right = expr.expression(1)

        return compileExpressionCps(left) { leftValue ->
            buildIf(
                condition = "!($leftValue)",
                thenBody = onComplete("false"),
                elseBody = compileExpressionCps(right) { rightValue ->
                    onComplete(rightValue)
                }
            )
        }
    }

    /**
     * Preserves Kotlin short-circuit semantics for `||`.
     *
     * The right-hand side is compiled only when the left-hand side evaluates to false.
     */
    private fun compileLazyOr(
        expr: MiniKotlinParser.OrExprContext,
        onComplete: (String) -> String
    ): String {
        val left = expr.expression(0)
        val right = expr.expression(1)

        return compileExpressionCps(left) { leftValue ->
            buildIf(
                condition = leftValue,
                thenBody = onComplete("true"),
                elseBody = compileExpressionCps(right) { rightValue ->
                    onComplete(rightValue)
                }
            )
        }
    }

    //=======================HELPERS============================

    private fun resetFunctionState() {
        tempCounter = 0
        localScopes.clear()
    }

    private fun buildFunctionHeader(
        ctx: MiniKotlinParser.FunctionDeclarationContext,
        functionName: String,
        kotlinReturnType: String
    ): String {
        if (functionName == "main") {
            return "public static void main(String[] args)"
        }

        val safeFunctionName = sanitizeJavaIdentifier(functionName)

        val params = ctx.parameterList()
            ?.parameter()
            ?.map { param ->
                val name = sanitizeJavaIdentifier(param.IDENTIFIER().text)
                val type = toJavaType(param.type().text)
                "$type $name"
            }
            ?: emptyList()

        val allParams = params + continuationParameter(kotlinReturnType)
        return "public static void $safeFunctionName(${allParams.joinToString(", ")})"
    }

    private fun buildIf(
        condition: String,
        thenBody: String,
        elseBody: String?
    ): String {
        return buildString {
            appendLine("if ($condition) {")
            if (thenBody.isNotBlank()) appendLine(indent(thenBody))
            appendLine("}")
            if (elseBody != null) {
                appendLine("else {")
                if (elseBody.isNotBlank()) appendLine(indent(elseBody))
                appendLine("}")
            }
        }.trimEnd()
    }

    private fun buildContinuationCall(
        functionName: String,
        arguments: List<String>,
        continuationArg: String,
        body: String
    ): String {
        return buildString {
            appendLine("$functionName(${arguments.joinToString(", ")}, ($continuationArg) -> {")
            if (body.isNotBlank()) appendLine(indent(body))
            appendLine("});")
        }.trimEnd()
    }

    private fun nextLoopName(): String = "loop${tempCounter++}"

    private fun buildRecursiveLoop(loopName: String, body: String): String {
        return buildString {
            appendLine("final Runnable[] $loopName = new Runnable[1];")
            appendLine("$loopName[0] = () -> {")
            if (body.isNotBlank()) appendLine(indent(body))
            appendLine("};")
            appendLine("$loopName[0].run();")
        }.trimEnd()
    }

    private fun wrapInBraces(header: String, body: String): String {
        return buildString {
            appendLine("$header {")
            if (body.isNotBlank()) appendLine(indent(body))
            appendLine("}")
        }.trimEnd()
    }

    private fun joinCode(vararg parts: String): String =
        parts.filter { it.isNotBlank() }.joinToString("\n")

    private fun nextTempName(): String = "arg${tempCounter++}"

    private fun withScope(block: () -> String): String {
        localScopes.add(mutableSetOf())
        return try {
            block()
        } finally {
            localScopes.removeAt(localScopes.lastIndex)
        }
    }

    private fun declareLocal(name: String) {
        if (localScopes.isEmpty()) {
            localScopes.add(mutableSetOf())
        }
        localScopes.last().add(name)
    }

    private fun isLocal(name: String): Boolean =
        localScopes.asReversed().any { name in it }

    private fun resolveReference(name: String): String {
        val safeName = sanitizeJavaIdentifier(name)
        return if (isLocal(name)) "$safeName[0]" else safeName
    }

    private fun continuationParameter(kotlinType: String): String =
        "Continuation<${toJavaType(kotlinType)}> __continuation"

    private fun isTerminal(ctx: MiniKotlinParser.StatementContext): Boolean =
        !statementCanCompleteNormally(ctx)

    private fun toJavaType(type: String): String {
        return when (type) {
            "Int" -> "Integer"
            "String" -> "String"
            "Boolean" -> "Boolean"
            "Unit" -> "Void"
            else -> type
        }
    }

    private data class BinaryOperation(
        val left: MiniKotlinParser.ExpressionContext,
        val operator: String,
        val right: MiniKotlinParser.ExpressionContext
    )

    private fun extractBinaryOperation(
        expr: MiniKotlinParser.ExpressionContext
    ): BinaryOperation? {
        return when (expr) {
            is MiniKotlinParser.AddSubExprContext ->
                BinaryOperation(expr.expression(0), expr.getChild(1).text, expr.expression(1))

            is MiniKotlinParser.MulDivExprContext ->
                BinaryOperation(expr.expression(0), expr.getChild(1).text, expr.expression(1))

            is MiniKotlinParser.ComparisonExprContext ->
                BinaryOperation(expr.expression(0), expr.getChild(1).text, expr.expression(1))

            is MiniKotlinParser.EqualityExprContext ->
                BinaryOperation(expr.expression(0), expr.getChild(1).text, expr.expression(1))

            else -> null
        }
    }

    private fun hasFunctionCall(expr: MiniKotlinParser.ExpressionContext): Boolean =
        when (expr) {
            is MiniKotlinParser.FunctionCallExprContext -> true

            is MiniKotlinParser.PrimaryExprContext -> {
                val primary = expr.primary()
                if (primary is MiniKotlinParser.ParenExprContext) {
                    hasFunctionCall(primary.expression())
                } else {
                    false
                }
            }

            is MiniKotlinParser.NotExprContext ->
                hasFunctionCall(expr.expression())

            is MiniKotlinParser.AddSubExprContext ->
                hasFunctionCall(expr.expression(0)) || hasFunctionCall(expr.expression(1))

            is MiniKotlinParser.MulDivExprContext ->
                hasFunctionCall(expr.expression(0)) || hasFunctionCall(expr.expression(1))

            is MiniKotlinParser.ComparisonExprContext ->
                hasFunctionCall(expr.expression(0)) || hasFunctionCall(expr.expression(1))

            is MiniKotlinParser.EqualityExprContext ->
                hasFunctionCall(expr.expression(0)) || hasFunctionCall(expr.expression(1))

            is MiniKotlinParser.AndExprContext ->
                hasFunctionCall(expr.expression(0)) || hasFunctionCall(expr.expression(1))

            is MiniKotlinParser.OrExprContext ->
                hasFunctionCall(expr.expression(0)) || hasFunctionCall(expr.expression(1))

            else -> false
        }

    private fun shouldEmitImplicitUnitReturn(
        ctx: MiniKotlinParser.FunctionDeclarationContext,
        isMain: Boolean,
        kotlinReturnType: String
    ): Boolean {
        if (isMain || kotlinReturnType != "Unit") return false
        return blockCanCompleteNormally(ctx.block())
    }

    private fun blockCanCompleteNormally(ctx: MiniKotlinParser.BlockContext): Boolean {
        val statements = ctx.statement()
        if (statements.isEmpty()) return true

        for (statement in statements) {
            if (!statementCanCompleteNormally(statement)) {
                return false
            }
        }
        return true
    }

    private fun statementCanCompleteNormally(ctx: MiniKotlinParser.StatementContext): Boolean {
        if (ctx.returnStatement() != null) return false

        val ifStatement = ctx.ifStatement()
        if (ifStatement != null) {
            if (ifStatement.block().size < 2) return true

            val thenCanComplete = blockCanCompleteNormally(ifStatement.block(0))
            val elseCanComplete = blockCanCompleteNormally(ifStatement.block(1))
            return thenCanComplete || elseCanComplete
        }

        return true
    }


    private fun indent(code: String): String {
        val padding = "    ".repeat(1)
        return code.lines().joinToString("\n") { line ->
            if (line.isBlank()) line else padding + line
        }
    }

    private fun renderBinary(
        left: MiniKotlinParser.ExpressionContext,
        operator: String,
        right: MiniKotlinParser.ExpressionContext
    ): String = "(${visit(left)} $operator ${visit(right)})"

    private fun renderEquality(left: String, right: String, isEqual: Boolean): String {
        val equalsCall = "java.util.Objects.equals($left, $right)"
        return if (isEqual) equalsCall else "(!$equalsCall)"
    }

    private val javaKeywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while"
    )

    private fun sanitizeJavaIdentifier(name: String): String =
        if (name in javaKeywords) "${name}_" else name

    //=======================TRIVIAL EXPRESSIONS============================
    override fun visitFunctionCallExpr(ctx: MiniKotlinParser.FunctionCallExprContext): String {
        val originalFunctionName = ctx.IDENTIFIER().text
        val safeFunctionName = sanitizeJavaIdentifier(originalFunctionName)
        val arguments = ctx.argumentList()?.expression()?.map { visit(it) } ?: emptyList()
        return "$safeFunctionName(${arguments.joinToString(", ")})"
    }

    override fun visitParenExpr(ctx: MiniKotlinParser.ParenExprContext): String =
        "(${visit(ctx.expression())})"

    override fun visitComparisonExpr(ctx: MiniKotlinParser.ComparisonExprContext): String {
        val operator = when {
            ctx.LE() != null -> "<="
            ctx.LT() != null -> "<"
            ctx.GE() != null -> ">="
            ctx.GT() != null -> ">"
            else -> error("Unknown comparison operator")
        }
        return renderBinary(ctx.expression(0), operator, ctx.expression(1))
    }

    override fun visitAndExpr(ctx: MiniKotlinParser.AndExprContext): String =
        renderBinary(ctx.expression(0), "&&", ctx.expression(1))

    override fun visitOrExpr(ctx: MiniKotlinParser.OrExprContext): String =
        renderBinary(ctx.expression(0), "||", ctx.expression(1))

    override fun visitNotExpr(ctx: MiniKotlinParser.NotExprContext): String =
        "(!(${visit(ctx.expression())}))"

    override fun visitEqualityExpr(ctx: MiniKotlinParser.EqualityExprContext): String {
        val left = visit(ctx.expression(0))
        val right = visit(ctx.expression(1))
        val isEqual = ctx.EQ() != null
        return renderEquality(left, right, isEqual)
    }

    override fun visitMulDivExpr(ctx: MiniKotlinParser.MulDivExprContext): String {
        val operator = when {
            ctx.MULT() != null -> "*"
            ctx.DIV() != null -> "/"
            ctx.MOD() != null -> "%"
            else -> error("Unknown operator")
        }
        return renderBinary(ctx.expression(0), operator, ctx.expression(1))
    }

    override fun visitAddSubExpr(ctx: MiniKotlinParser.AddSubExprContext): String {
        val operator = if (ctx.PLUS() != null) "+" else "-"
        return renderBinary(ctx.expression(0), operator, ctx.expression(1))
    }

    override fun visitIntLiteral(ctx: MiniKotlinParser.IntLiteralContext): String =
        ctx.INTEGER_LITERAL().text

    override fun visitStringLiteral(ctx: MiniKotlinParser.StringLiteralContext): String =
        ctx.STRING_LITERAL().text

    override fun visitBoolLiteral(ctx: MiniKotlinParser.BoolLiteralContext): String =
        ctx.BOOLEAN_LITERAL().text

    override fun visitIdentifierExpr(ctx: MiniKotlinParser.IdentifierExprContext): String =
        resolveReference(ctx.IDENTIFIER().text)
}
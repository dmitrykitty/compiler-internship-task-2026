package org.example.compiler

import MiniKotlinBaseVisitor


class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {
    private var tempCounter = 0
    private val localScopes = mutableListOf<MutableSet<String>>()

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

    private fun compileBlock(
        ctx: MiniKotlinParser.BlockContext,
        isMain: Boolean,
        kotlinReturnType: String,
        fallthroughCode: String = ""
    ): String = withScope {
        compileStatementsSequentially(ctx.statement(), 0, isMain, kotlinReturnType, fallthroughCode)
            .trimEnd()
    }

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

    private fun compileWhileStatement(
        ctx: MiniKotlinParser.WhileStatementContext,
        isMain: Boolean,
        kotlinReturnType: String
    ): String {
        val condition = ctx.expression()
        val body = compileBlock(ctx.block(), isMain, kotlinReturnType)

        return wrapInBraces("while (${visit(condition)})", body)
    }

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

    private fun compileVariableAssignment(
        ctx: MiniKotlinParser.VariableAssignmentContext
    ): String {
        val name = ctx.IDENTIFIER().text
        val value = visit(ctx.expression())
        return "${resolveReference(name)} = $value;"
    }

    private fun compileExpressionStatement(
        expr: MiniKotlinParser.ExpressionContext
    ): String {
        return if (expr is MiniKotlinParser.FunctionCallExprContext) {
            "${visit(expr)};"
        } else {
            "// ignored pure expression statement"
        }
    }

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
package org.example.compiler

import MiniKotlinBaseVisitor


class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {
    private var tempCounter = 0
    private val localScopes = mutableListOf<MutableSet<String>>()

    fun compile(
        program: MiniKotlinParser.ProgramContext,
        className: String = "MiniProgram"
    ): String {
        val functions = program.functionDeclaration()
            .joinToString("\n\n") { indent(compileFunction(it).trimEnd()) }

        return buildString {
            appendLine("public class $className {")
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

        val functionName = ctx.IDENTIFIER().text
        val kotlinReturnType = ctx.type().text
        val isMain = functionName == "main"

        val header = buildFunctionHeader(ctx, functionName, kotlinReturnType)
        val body = compileBlock(ctx.block(), isMain, kotlinReturnType)

        return wrapInBraces(header, body)
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

        val params = ctx.parameterList()
            ?.parameter()
            ?.map { param ->
                val name = param.IDENTIFIER().text
                val type = toJavaType(param.type().text)
                "$type $name"
            }
            ?: emptyList()

        val allParams = params + continuationParameter(kotlinReturnType)
        return "public static void $functionName(${allParams.joinToString(", ")})"
    }


    private fun compileStatementsSequentially(
        statements: List<MiniKotlinParser.StatementContext>,
        index: Int,
        isMain: Boolean,
        kotlinReturnType: String
    ): String {
        if (index >= statements.size) return ""

        val current = statements[index]
        fun rest(): String =
            compileStatementsSequentially(statements, index + 1, isMain, kotlinReturnType)

        compileCpsVariableDeclaration(current, ::rest)?.let { return it }
        compileCpsVariableAssignment(current, ::rest)?.let { return it }
        compileCpsCallStatement(current, ::rest)?.let { return it }

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

        val name = declaration.IDENTIFIER().text
        val type = toJavaType(declaration.type().text)

        return compileExpressionCps(expr) { value ->
            declareLocal(name)
            joinCode(
                "final $type[] $name = new $type[]{$value};",
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

    private fun compileCpsCallStatement(
        statement: MiniKotlinParser.StatementContext,
        rest: () -> String
    ): String? {
        val expr = statement.expression() as? MiniKotlinParser.FunctionCallExprContext ?: return null

        val functionName = expr.IDENTIFIER().text
        val arguments = expr.argumentList()?.expression() ?: emptyList()

        return compileArgumentsCps(arguments) { compiledArgs ->
            val tempName = nextTempName()

            if (functionName == "println") {
                val value = compiledArgs.singleOrNull()
                    ?: error("println expects exactly one argument")

                buildContinuationCall("Prelude.println", listOf(value), tempName, rest())
            } else {
                buildContinuationCall(functionName, compiledArgs, tempName, rest())
            }
        }
    }

    private fun compileBlock(
        ctx: MiniKotlinParser.BlockContext,
        isMain: Boolean,
        kotlinReturnType: String
    ): String = withScope {
        compileStatementsSequentially(ctx.statement(), 0, isMain, kotlinReturnType).trimEnd()
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

        if (hasFunctionCall(condition)) {
            error("Function calls inside while conditions are not supported yet.")
        }

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
        val name = ctx.IDENTIFIER().text
        val type = toJavaType(ctx.type().text)
        val value = visit(ctx.expression())

        declareLocal(name)
        return "final $type[] $name = new $type[]{$value};"
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
        val functionName = expr.IDENTIFIER().text
        val arguments = expr.argumentList()?.expression() ?: emptyList()

        return compileArgumentsCps(arguments) { compiledArgs ->
            val tempName = nextTempName()
            buildContinuationCall(
                functionName,
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
                onComplete("($leftValue ${operation.operator} $rightValue)")
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

    private fun resolveReference(name: String): String =
        if (isLocal(name)) "$name[0]" else name

    private fun continuationParameter(kotlinType: String): String =
        "Continuation<${toJavaType(kotlinType)}> __continuation"

    private fun isTerminal(ctx: MiniKotlinParser.StatementContext): Boolean =
        ctx.returnStatement() != null

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

    private fun renderBinary(
        left: MiniKotlinParser.ExpressionContext,
        operator: String,
        right: MiniKotlinParser.ExpressionContext
    ): String = "(${visit(left)} $operator ${visit(right)})"

    override fun visitFunctionCallExpr(ctx: MiniKotlinParser.FunctionCallExprContext): String {
        val functionName = ctx.IDENTIFIER().text
        val arguments = ctx.argumentList()?.expression()?.map { visit(it) } ?: emptyList()
        return "$functionName(${arguments.joinToString(", ")})"
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
        val operator = if (ctx.EQ() != null) "==" else "!="
        return renderBinary(ctx.expression(0), operator, ctx.expression(1))
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

    private fun indent(code: String): String {
        val padding = "    ".repeat(1)
        return code.lines().joinToString("\n") { line ->
            if (line.isBlank()) line else padding + line
        }
    }
}
package org.example.compiler

import MiniKotlinBaseVisitor


class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {
    private var counter = 0
    private fun nextArg() = "arg${counter++}"

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        val functions = program.functionDeclaration()
            .joinToString("\n\n") { indent(genFunction(it).trimEnd(), 1) }

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

    private fun genFunction(ctx: MiniKotlinParser.FunctionDeclarationContext): String {
        val name = ctx.IDENTIFIER().text
        val kotlinReturnType = ctx.type().text
        //val javaReturnType = mapType(kotlinReturnType)

        val params = ctx.parameterList()?.parameter()?.map { p ->
            val id = p.IDENTIFIER().text
            val t = mapType(p.type().text)
            "$t $id"
        } ?: emptyList()

        val header = if (name == "main") {
            "public static void main(String[] args)"
        } else {
            val allParams = params + continuationParam(kotlinReturnType)
            "public static void $name(${allParams.joinToString(", ")})"
        }
        val isMain = (name == "main")
        val body = genBlock(ctx.block(), isMain, kotlinReturnType)

        return buildString {
            appendLine("$header {")
            if (body.isNotBlank()) appendLine(indent(body, 1))
            appendLine("}")
        }
    }

    private fun genStatements(
        st: List<MiniKotlinParser.StatementContext>,
        i: Int,
        isMain: Boolean,
        kotlinReturnType: String
    ): String {
        if (i >= st.size) return ""

        val curSt = st[i]

        // 0) Pomocniczo: wygeneruj resztę
        fun rest(): String = genStatements(st, i + 1, isMain, kotlinReturnType)

        // 1) var x: T = expr  (jeśli expr wymaga CPS)
        curSt.variableDeclaration()?.let { vd ->
            val name = vd.IDENTIFIER().text
            val type = mapType(vd.type().text)
            val expr = vd.expression()

            if (containsFunctionCall(expr)) {
                val restCode = rest()
                return emitExprCps(expr) { value ->
                    buildString {
                        appendLine("$type $name = $value;")
                        if (restCode.isNotBlank()) append(restCode)
                    }.trimEnd()
                }
            }
            // jeśli nie ma call — spadnie do normalnego statement poniżej
        }

        // 2) expression-statement: function call
        val expr = curSt.expression()
        if (expr is MiniKotlinParser.FunctionCallExprContext) {
            val name = expr.IDENTIFIER().text
            val args = expr.argumentList()?.expression()?.map { visit(it) } ?: emptyList()
            val tmp = nextArg()
            val restCode = rest()

            // 2a) println -> Prelude.println(msg, cont)
            if (name == "println") {
                val value = args.singleOrNull() ?: error("println expects 1 argument")
                return buildString {
                    appendLine("Prelude.println($value, ($tmp) -> {")
                    if (restCode.isNotBlank()) appendLine(indent(restCode, 1))
                    appendLine("});")
                }.trimEnd()
            }

            // 2b) zwykły call-statement -> name(args..., cont)
            return buildString {
                appendLine("$name(${args.joinToString(", ")}, ($tmp) -> {")
                if (restCode.isNotBlank()) appendLine(indent(restCode, 1))
                appendLine("});")
            }.trimEnd()
        }

        // 3) Normalny statement (if/while/assign/return/var bez call)
        val cur = genStatement(curSt, isMain, kotlinReturnType).trimEnd()
        val restCode = rest()

        return when {
            cur.isBlank() -> restCode
            restCode.isBlank() -> cur
            else -> cur + "\n" + restCode
        }
    }

    private fun emitExprCps(expr: MiniKotlinParser.ExpressionContext, k: (String) -> String): String {
        if (!containsFunctionCall(expr)) return k(visit(expr))

        if (expr is MiniKotlinParser.FunctionCallExprContext) {
            val name = expr.IDENTIFIER().text
            val args = expr.argumentList()?.expression()?.map { visit(it) } ?: emptyList()
            val tmp = nextArg()

            val body = k(tmp)
            return buildString {
                appendLine("$name(${args.joinToString(", ")}, ($tmp) -> {")
                if (body.isNotBlank()) appendLine(indent(body, 1))
                appendLine("});")
            }.trimEnd()
        }

        if(isBinaryByShape(expr)){
            val left = expr.getChild(0) as MiniKotlinParser.ExpressionContext
            val op = expr.getChild(1).text
            val right = expr.getChild(2) as MiniKotlinParser.ExpressionContext

            return emitExprCps(left) { lv ->
                emitExprCps(right) { rv ->
                    k("($lv $op $rv)")
                }
            }
        }

        if (expr is MiniKotlinParser.NotExprContext) {
            return emitExprCps(expr.expression()) { v ->
                k("(!($v))")
            }
        }
        // fallback
        return k(visit(expr))
    }

    private fun genBlock(
        ctx: MiniKotlinParser.BlockContext,
        isMain: Boolean,
        kotlinReturnType: String
    ): String {
        val st = ctx.statement()
        return genStatements(st, 0, isMain, kotlinReturnType).trimEnd()
    }

    private fun genStatement(
        ctx: MiniKotlinParser.StatementContext,
        isMain: Boolean,
        kotlinReturnType: String
    ): String {

        return when {

            ctx.variableDeclaration() != null ->
                genVariableDeclaration(ctx.variableDeclaration())

            ctx.variableAssignment() != null ->
                genVariableAssignment(ctx.variableAssignment())

            ctx.ifStatement() != null ->
                genIf(ctx.ifStatement(), isMain, kotlinReturnType)

            ctx.whileStatement() != null ->
                genWhile(ctx.whileStatement(), isMain, kotlinReturnType)

            ctx.returnStatement() != null ->
                genReturn(ctx.returnStatement(), isMain, kotlinReturnType)

            ctx.expression() != null ->
                genExpressionStatement(ctx.expression())

            else -> ""
        }
    }

    private fun genIf(
        ctx: MiniKotlinParser.IfStatementContext,
        isMain: Boolean,
        kotlinReturnType: String
    ): String {
        val cond = visit(ctx.expression())
        val thenBlock = genBlock(ctx.block(0), isMain, kotlinReturnType)
        val elseBlock = if (ctx.block().size > 1) genBlock(ctx.block(1), isMain, kotlinReturnType) else null

        return buildString {
            appendLine("if ($cond) {")
            if (thenBlock.isNotBlank()) appendLine(indent(thenBlock, 1))
            appendLine("}")
            if (elseBlock != null) {
                appendLine("else {")
                if (elseBlock.isNotBlank()) appendLine(indent(elseBlock, 1))
                appendLine("}")
            }
        }.trimEnd()
    }

    private fun genWhile(
        ctx: MiniKotlinParser.WhileStatementContext,
        isMain: Boolean,
        kotlinReturnType: String
    ): String {
        val cond = visit(ctx.expression())
        val body = genBlock(ctx.block(), isMain, kotlinReturnType)

        return buildString {
            appendLine("while ($cond) {")
            if (body.isNotBlank()) appendLine(indent(body, 1))
            appendLine("}")
        }.trimEnd()
    }

    private fun genReturn(
        ctx: MiniKotlinParser.ReturnStatementContext,
        isMain: Boolean,
        kotlinReturnType: String
    ): String {
        val expr = ctx.expression()

        if (isMain) return if (expr != null) "return ${visit(expr)};" else "return;"

        if (expr == null) return "__continuation.accept(null);\nreturn;"

        // CPS: policz expr (nawet z callami) i dopiero wtedy accept + return
        return emitExprCps(expr) { value ->
            "__continuation.accept($value);\nreturn;"
        }
    }

    private fun genVariableAssignment(ctx: MiniKotlinParser.VariableAssignmentContext): String {
        val name = ctx.IDENTIFIER().text
        val expr = visit(ctx.expression())
        return "$name = $expr;"
    }

    private fun genExpressionStatement(expr: MiniKotlinParser.ExpressionContext): String {
        return "${visit(expr)};"
    }

    private fun genVariableDeclaration(ctx: MiniKotlinParser.VariableDeclarationContext): String {

        val name = ctx.IDENTIFIER().text
        val type = mapType(ctx.type().text)
        val expr = visit(ctx.expression())

        return "$type $name = $expr;"
    }

    override fun visitFunctionCallExpr(ctx: MiniKotlinParser.FunctionCallExprContext): String {
        val name = ctx.IDENTIFIER().text
        val args = ctx.argumentList()?.expression()?.map { visit(it) } ?: emptyList()

        return "$name(${args.joinToString(", ")})"
    }

    //( expression )
    override fun visitParenExpr(ctx: MiniKotlinParser.ParenExprContext): String {
        val inner = visit(ctx.expression())
        return "($inner)"
    }

    // <= >= < >
    override fun visitComparisonExpr(ctx: MiniKotlinParser.ComparisonExprContext): String {
        val left = visit(ctx.expression(0))
        val right = visit(ctx.expression(1))
        val operator = when {
            ctx.LE() != null -> "<="
            ctx.LT() != null -> "<"
            ctx.GE() != null -> ">="
            ctx.GT() != null -> ">"
            else -> error("Unknown comparison operator")
        }

        return "($left $operator $right)"
    }

    //TODO: LAZY OR AND AND operators after if statement and function call

    override fun visitAndExpr(ctx: MiniKotlinParser.AndExprContext): String {
        val left = visit(ctx.expression(0))
        val right = visit(ctx.expression(1))
        return "($left && $right)"
    }

    override fun visitOrExpr(ctx: MiniKotlinParser.OrExprContext): String {
        val left = visit(ctx.expression(0))
        val right = visit(ctx.expression(1))
        return "($left || $right)"
    }

    // !smth
    override fun visitNotExpr(ctx: MiniKotlinParser.NotExprContext): String {
        val inner = visit(ctx.expression())
        return "(!($inner))"
    }

    //== != operators
    override fun visitEqualityExpr(ctx: MiniKotlinParser.EqualityExprContext): String {
        val left = visit(ctx.expression(0))
        val right = visit(ctx.expression(1))
        val operator = if (ctx.EQ() == null) "!=" else "=="

        return "($left $operator $right)"
    }

    //* \ % operators
    override fun visitMulDivExpr(ctx: MiniKotlinParser.MulDivExprContext): String {
        val left = visit(ctx.expression(0))
        val right = visit(ctx.expression(1))
        val operator = when {
            ctx.MULT() != null -> "*"
            ctx.DIV() != null -> "/"
            ctx.MOD() != null -> "%"
            else -> error("Unknown operator")
        }
        return "($left $operator $right)"
    }

    //+- operators
    override fun visitAddSubExpr(ctx: MiniKotlinParser.AddSubExprContext): String {
        val left = visit(ctx.expression(0))
        val right = visit(ctx.expression(1))
        val operator = if (ctx.PLUS() == null) "-" else "+"

        return "($left $operator $right)"
    }

    override fun visitIntLiteral(ctx: MiniKotlinParser.IntLiteralContext): String {
        return ctx.INTEGER_LITERAL().text
    }

    override fun visitStringLiteral(ctx: MiniKotlinParser.StringLiteralContext): String {
        return ctx.STRING_LITERAL().text
    }

    override fun visitBoolLiteral(ctx: MiniKotlinParser.BoolLiteralContext): String {
        return ctx.BOOLEAN_LITERAL().text
    }

    override fun visitIdentifierExpr(ctx: MiniKotlinParser.IdentifierExprContext): String {
        return ctx.IDENTIFIER().text
    }

    private fun containsFunctionCall(expr: MiniKotlinParser.ExpressionContext): Boolean = when (expr) {
        is MiniKotlinParser.FunctionCallExprContext -> true
        is MiniKotlinParser.PrimaryExprContext -> {
            val p = expr.primary()
            if (p is MiniKotlinParser.ParenExprContext) containsFunctionCall(p.expression()) else false
        }
        is MiniKotlinParser.NotExprContext -> containsFunctionCall(expr.expression())
        is MiniKotlinParser.AddSubExprContext -> containsFunctionCall(expr.expression(0)) ||
                containsFunctionCall(expr.expression(1))
        is MiniKotlinParser.MulDivExprContext -> containsFunctionCall(expr.expression(0)) ||
                containsFunctionCall(expr.expression(1))
        is MiniKotlinParser.ComparisonExprContext -> containsFunctionCall(expr.expression(0)) ||
                containsFunctionCall(expr.expression(1))
        is MiniKotlinParser.EqualityExprContext -> containsFunctionCall(expr.expression(0)) ||
                containsFunctionCall(expr.expression(1))
        is MiniKotlinParser.AndExprContext -> containsFunctionCall(expr.expression(0)) ||
                containsFunctionCall(expr.expression(1))
        is MiniKotlinParser.OrExprContext -> containsFunctionCall(expr.expression(0)) ||
                containsFunctionCall(expr.expression(1))
        else -> false
    }

    private fun continuationParam(kotlinType: String): String {
        return "Continuation<${mapType(kotlinType)}> __continuation"
    }

    private fun isFunctionCall(expr: MiniKotlinParser.ExpressionContext): Boolean {
        return expr is MiniKotlinParser.FunctionCallExprContext
    }

    private fun isBinaryByShape(expr: MiniKotlinParser.ExpressionContext): Boolean {
        if (expr.childCount != 3) return false
        val mid = expr.getChild(1)
        return mid !is MiniKotlinParser.ExpressionContext
    }



    private fun mapType(type: String): String {
        return when (type) {
            "Int" -> "Integer"
            "String" -> "String"
            "Boolean" -> "Boolean"
            "Unit" -> "Void"
            else -> type
        }
    }

    private fun indent(code: String, level: Int = 1): String {
        val pad = "    ".repeat(level)
        return code
            .lines()
            .joinToString("\n") { line ->
                if (line.isBlank()) line else pad + line
            }
    }
}
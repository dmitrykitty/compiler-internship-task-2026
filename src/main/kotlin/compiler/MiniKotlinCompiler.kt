package org.example.compiler

import MiniKotlinBaseVisitor


class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {
    private var counter = 0
    private fun nextArg() = "arg${counter++}"

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        val sb = StringBuilder()

        sb.appendLine("public class $className {")

        for (fn in program.functionDeclaration()) {
            sb.appendLine(genFunction(fn))
        }

        sb.appendLine("}")

        return sb.toString()
    }

    private fun genFunction(ctx: MiniKotlinParser.FunctionDeclarationContext): String {
        val name = ctx.IDENTIFIER().text

        val retType = mapType(ctx.type().text) // uwaga: dla "void" to Java void
        val params = ctx.parameterList()?.parameter()?.map { p ->
            val id = p.IDENTIFIER().text
            val t = mapType(p.type().text)
            "$t $id"
        } ?: emptyList()

        val header = if (name == "main") {
            "public static void main(String[] args)"
        } else {
            // dla non-main: normalna funkcja (jeszcze nie CPS)
            "public static $retType $name(${params.joinToString(", ")})"
        }

        val body = genBlock(ctx.block())

        return """
        $header {
        $body
        }
    """.trimIndent()
    }

    private fun genBlock(ctx: MiniKotlinParser.BlockContext): String {
        val sb = StringBuilder()

        for (st in ctx.statement()) {
            sb.appendLine(genStatement(st))
        }

        return sb.toString()
    }

    private fun genStatement(ctx: MiniKotlinParser.StatementContext): String {

        return when {

            ctx.variableDeclaration() != null ->
                genVariableDeclaration(ctx.variableDeclaration())

            ctx.variableAssignment() != null ->
                genVariableAssignment(ctx.variableAssignment())

            ctx.ifStatement() != null ->
                genIf(ctx.ifStatement())

            ctx.whileStatement() != null ->
                genWhile(ctx.whileStatement())

            ctx.returnStatement() != null ->
                genReturn(ctx.returnStatement())

            ctx.expression() != null ->
                genExpressionStatement(ctx.expression())

            else -> ""
        }
    }

    private fun genIf(ctx: MiniKotlinParser.IfStatementContext): String {

        val cond = visit(ctx.expression())
        val thenBlock = genBlock(ctx.block(0))

        val elsePart =
            if (ctx.block().size > 1)
                "else {\n${genBlock(ctx.block(1))}\n}"
            else
                ""

        return """
        if ($cond) {
        $thenBlock
        }
        $elsePart
    """.trimIndent()
    }

    private fun genWhile(ctx: MiniKotlinParser.WhileStatementContext): String {
        val cond = visit(ctx.expression())
        val body = genBlock(ctx.block())

        return """
        while ($cond) {
        $body
        }
    """.trimIndent()
    }

    private fun genReturn(ctx: MiniKotlinParser.ReturnStatementContext): String {
        val expr = ctx.expression()
        return if (expr != null) {
            "return ${visit(expr)};"
        } else {
            "return;"
        }
    }

    private fun genVariableAssignment(ctx: MiniKotlinParser.VariableAssignmentContext): String {
        val name = ctx.IDENTIFIER().text
        val expr = visit(ctx.expression())
        return "$name = $expr;"
    }

    private fun genExpressionStatement(expr: MiniKotlinParser.ExpressionContext): String {
        val code = visit(expr)
        return "$code;"
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

        return if (name == "println") {
            "System.out.println(${args.joinToString(", ")})"
        } else {
            "$name(${args.joinToString(", ")})"
        }
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

    private fun mapType(type: String): String {
        return when (type) {
            "Int" -> "Integer"
            "String" -> "String"
            "Boolean" -> "Boolean"
            "Unit" -> "void"
            else -> type
        }
    }
}
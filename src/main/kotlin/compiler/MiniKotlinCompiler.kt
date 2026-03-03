package compiler
import MiniKotlinBaseVisitor



class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {
    private var counter = 0
    private fun nextArg() = "arg${counter++}"

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        return """
            public class $className {
                public static void main(String[] args) {
                  return;
                }
            }
        """.trimIndent()
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

    // !smth
    override fun visitNotExpr(ctx: MiniKotlinParser.NotExprContext): String {
        val inner = visit(ctx.expression())
        return "(!($inner))"
    }

    //== != operators
    override fun visitEqualityExpr(ctx: MiniKotlinParser.EqualityExprContext): String {
        val left = visit(ctx.expression(0))
        val right = visit(ctx.expression(1))
        val operator = if(ctx.EQ() == null) "!=" else "=="

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
        val operator = if(ctx.PLUS() == null) "-" else "+"

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
}
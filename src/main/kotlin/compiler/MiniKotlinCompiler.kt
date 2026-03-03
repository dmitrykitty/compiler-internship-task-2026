package compiler
import MiniKotlinBaseVisitor



class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {
    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        return """
            public class $className {
                public static void main(String[] args) {
                  return;
                }
            }
        """.trimIndent()
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
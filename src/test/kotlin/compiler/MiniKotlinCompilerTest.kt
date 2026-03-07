package org.example.compiler

import MiniKotlinLexer
import MiniKotlinParser
import internal.CompilationResult
import internal.ExecutionResult
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MiniKotlinCompilerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun parseString(source: String): MiniKotlinParser.ProgramContext {
        val input = CharStreams.fromString(source)
        val lexer = MiniKotlinLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = MiniKotlinParser(tokens)
        return parser.program()
    }

    private fun parseFile(path: Path): MiniKotlinParser.ProgramContext {
        val input = CharStreams.fromPath(path)
        val lexer = MiniKotlinLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = MiniKotlinParser(tokens)
        return parser.program()
    }

    private fun resolveStdlibPath(): Path? {
        val devPath = Paths.get("build", "stdlib")
        if (devPath.toFile().exists()) {
            val stdlibJar = devPath.toFile().listFiles()
                ?.firstOrNull { it.name.startsWith("stdlib") && it.name.endsWith(".jar") }
            if (stdlibJar != null) return stdlibJar.toPath()
        }
        return null
    }

    @Test
    fun `factorial prints 120 and 15`() {
        val source = """
            fun factorial(n: Int): Int {
                if (n <= 1) {
                    return 1
                } else {
                    return n * factorial(n - 1)
                }
            }

            fun main(): Unit {
                var result: Int = factorial(5)
                println(result)

                var a: Int = 10 + 5
                var b: Boolean = a > 10
                println(a)
            }
        """.trimIndent()

        assertProgramOutput(source, "120", "15")
    }

    @Test
    fun `inc assignment updates variable`() {
        val source = """
            fun inc(x: Int): Int {
                return x + 1
            }

            fun main(): Unit {
                var x: Int = 1
                x = inc(x)
                println(x)
            }
        """.trimIndent()

        assertProgramOutput(source, "2")
    }

    @Test
    fun `fibonacci prints 8 for 6`() {
        val source = """
            fun fib(n: Int): Int {
                if (n <= 1) {
                    return n
                } else {
                    return fib(n - 1) + fib(n - 2)
                }
            }

            fun main(): Unit {
                println(fib(6))
            }
        """.trimIndent()

        assertProgramOutput(source, "8")
    }

    @Test
    fun `lazy and does not evaluate right side when left is false`() {
        val source = """
            fun boom(x: Int): Boolean {
                println(x)
                return x > 100
            }

            fun main(): Unit {
                println(false && boom(101))
            }
        """.trimIndent()

        assertProgramOutput(source, "false")
    }

    @Test
    fun `if with function call in condition works`() {
        val source = """
            fun isBig(x: Int): Boolean {
                return x > 10
            }

            fun main(): Unit {
                if (isBig(15)) {
                    println(1)
                } else {
                    println(0)
                }
            }
        """.trimIndent()

        assertProgramOutput(source, "1")
    }

    @Test
    fun `while loop updates mutable variable`() {
        val source = """
            fun main(): Unit {
                var x: Int = 0
                while (x < 3) {
                    println(x)
                    x = x + 1
                }
            }
        """.trimIndent()

        assertProgramOutput(source, "0", "1", "2")
    }

    @Test
    fun `nested function calls work`() {
        val source = """
        fun inc(x: Int): Int {
            return x + 1
        }

        fun twice(x: Int): Int {
            return x * 2
        }

        fun main(): Unit {
            println(twice(inc(3)))
        }
    """.trimIndent()

        assertProgramOutput(source, "8")
    }

    @Test
    fun `mixed expression with function calls works`() {
        val source = """
            fun inc(x: Int): Int {
                return x + 1
            }

        val compiler = MiniKotlinCompiler()
        val javaCode = compiler.compile(program)

        val javaFile = tempDir.resolve("MiniProgram.java")
        Files.writeString(javaFile, javaCode)

        val javaCompiler = JavaRuntimeCompiler()
        val stdlibPath = resolveStdlibPath()
        val (compilationResult, executionResult) = javaCompiler.compileAndExecute(javaFile, stdlibPath)

        assertIs<CompilationResult.Success>(compilationResult)
        assertIs<ExecutionResult.Success>(executionResult)

        val output = executionResult.stdout
        assertTrue(output.contains("120"), "Expected output to contain factorial result 120, but got: $output")
        assertTrue(output.contains("15"), "Expected output to contain arithmetic result 15, but got: $output")
    }
}

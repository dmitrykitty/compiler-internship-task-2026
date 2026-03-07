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
import kotlin.test.assertEquals
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

    private fun resolveStdlibPath(): Path? {
        val devPath = Paths.get("build", "stdlib")
        if (devPath.toFile().exists()) {
            val stdlibJar = devPath.toFile().listFiles()
                ?.firstOrNull { it.name.startsWith("stdlib") && it.name.endsWith(".jar") }
            if (stdlibJar != null) return stdlibJar.toPath()
        }
        return null
    }

    private fun compileAndRun(source: String): String {
        val program = parseString(source)

        val compiler = MiniKotlinCompiler()
        val javaCode = compiler.compile(program)
        println("=== Source Code ===")
        println(source)
        println("=== End Source Code ===")

        println("=====================================================")

        println("=== Generated Java Code ===")
        println(javaCode)
        println("=== End Generated Java Code ===")

        val javaFile = tempDir.resolve("MiniProgram.java")
        Files.writeString(javaFile, javaCode)

        val javaCompiler = JavaRuntimeCompiler()
        val stdlibPath = resolveStdlibPath()
        val (compilationResult, executionResult) = javaCompiler.compileAndExecute(javaFile, stdlibPath)

        assertIs<CompilationResult.Success>(compilationResult)
        assertIs<ExecutionResult.Success>(executionResult)

        return executionResult.stdout.trim()
    }

    private fun assertProgramOutput(source: String, vararg expectedLines: String) {
        val output = compileAndRun(source)
        val actualLines = output
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        assertEquals(expectedLines.toList(), actualLines)
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

            fun main(): Unit {
                println(1 + inc(2) * 3)
            }
        """.trimIndent()

        assertProgramOutput(source, "10")
    }

    @Test
    fun `return with function call works`() {
        val source = """
        fun inc(x: Int): Int {
            return x + 1
        }

        fun test(x: Int): Int {
            return inc(x)
        }

        fun main(): Unit {
            println(test(4))
        }
    """.trimIndent()

        assertProgramOutput(source, "5")
    }

    @Test
    fun `generated java contains continuation parameter for normal function`() {
        val source = """
            fun inc(x: Int): Int {
                return x + 1
            }

            fun main(): Unit {
                println(inc(1))
            }
        """.trimIndent()

        val program = parseString(source)
        val javaCode = MiniKotlinCompiler().compile(program)

        assertTrue(javaCode.contains("public static void inc(Integer x, Continuation<Integer> __continuation)"))
    }

    @Test
    fun `generated java wraps mutable local variables in arrays`() {
        val source = """
            fun main(): Unit {
                var x: Int = 1
                x = x + 1
                println(x)
            }
        """.trimIndent()

        val program = parseString(source)
        val javaCode = MiniKotlinCompiler().compile(program)

        assertTrue(javaCode.contains("final Integer[] x = new Integer[]{1};"))
        assertTrue(javaCode.contains("x[0] = (x[0] + 1);"))
    }

    @Test
    fun `string equality follows Kotlin semantics`() {
        val source = """
        fun main(): Unit {
            var a: String = "a"
            var b: String = "b"
            println((a + b) == "ab")
            println((a + b) != "ab")
        }
    """.trimIndent()

        assertProgramOutput(source, "true", "false")
    }

    @Test
    fun `unit function without explicit return resumes continuation`() {
        val source = """
        fun log(x: Int): Unit {
            println(x)
        }

        fun main(): Unit {
            log(7)
            println(9)
        }
    """.trimIndent()

        assertProgramOutput(source, "7", "9")
    }

    @Test
    fun `empty unit function resumes continuation`() {
        val source = """
        fun noop(x: Int): Unit {
        }

        fun main(): Unit {
            noop(1)
            println(2)
        }
    """.trimIndent()

        assertProgramOutput(source, "2")
    }

    @Test
    fun `while with function call in condition works`() {
        val source = """
        fun check(x: Int): Boolean {
            return x < 3
        }

        fun main(): Unit {
            var x: Int = 0
            while (check(x)) {
                println(x)
                x = x + 1
            }
            println(99)
        }
    """.trimIndent()

        assertProgramOutput(source, "0", "1", "2", "99")
    }

    @Test
    fun `while condition with function call is re-evaluated every iteration`() {
        val source = """
        fun check(x: Int): Boolean {
            println(x)
            return x < 2
        }

        fun main(): Unit {
            var x: Int = 0
            while (check(x)) {
                x = x + 1
            }
            println(7)
        }
    """.trimIndent()

        assertProgramOutput(source, "0", "1", "2", "7")
    }

    @Test
    fun `java keyword function name is sanitized`() {
        val source = """
        fun double(x: Int): Int {
            return x * 2
        }

        fun main(): Unit {
            println(double(4))
        }
    """.trimIndent()

        assertProgramOutput(source, "8")
    }

    @Test
    fun `java keyword variable name is sanitized`() {
        val source = """
        fun id(double: Int): Int {
            return double
        }

        fun main(): Unit {
            var class: Int = id(7)
            println(class)
        }
    """.trimIndent()

        assertProgramOutput(source, "7")
    }
}

package org.example.compiler

import internal.CompilationError
import internal.CompilationResult
import internal.ExecutionResult
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

class JavaRuntimeCompiler {

    fun compile(sourceFile: Path, stdlibPath: Path? = null): CompilationResult {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: return CompilationResult.Failure(
                listOf(CompilationError(0, 0, "Java compiler not available. Ensure JDK is installed."))
            )

        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val fileManager = compiler.getStandardFileManager(diagnostics, null, null)

        val sourceContent = Files.readString(sourceFile)
        val className = extractClassName(sourceContent, sourceFile.fileName.toString())

        val outputDir = Files.createTempDirectory("compiled_classes")

        val options = mutableListOf("-d", outputDir.toString())
        if (stdlibPath != null && Files.exists(stdlibPath)) {
            options.addAll(listOf("-classpath", stdlibPath.toString()))
        }

        val compilationUnits = fileManager.getJavaFileObjects(sourceFile.toFile())

        val task = compiler.getTask(
            null,
            fileManager,
            diagnostics,
            options,
            null,
            compilationUnits
        )

        val success = task.call()
        fileManager.close()

        if (!success) {
            val errors = diagnostics.diagnostics
                .filter { it.kind == Diagnostic.Kind.ERROR }
                .map { diagnostic ->
                    CompilationError(
                        line = diagnostic.lineNumber,
                        column = diagnostic.columnNumber,
                        message = diagnostic.getMessage(null)
                    )
                }
            return CompilationResult.Failure(errors)
        }

        val urls = mutableListOf(outputDir.toUri().toURL())
        if (stdlibPath != null && Files.exists(stdlibPath)) {
            urls.add(stdlibPath.toUri().toURL())
        }

        val classLoader = URLClassLoader(urls.toTypedArray(), this.javaClass.classLoader)
        val compiledClass = classLoader.loadClass(className)

        return CompilationResult.Success(
            className = className,
            classLoader = classLoader,
            compiledClass = compiledClass
        )
    }

    fun execute(compilationResult: CompilationResult.Success, args: Array<String> = emptyArray()): ExecutionResult {
        val oldOut = System.out
        val oldErr = System.err

        val outCapture = ByteArrayOutputStream()
        val errCapture = ByteArrayOutputStream()

        return try {
            System.setOut(PrintStream(outCapture))
            System.setErr(PrintStream(errCapture))

            val mainMethod = compilationResult.compiledClass.getMethod("main", Array<String>::class.java)
            mainMethod.invoke(null, args)

            ExecutionResult.Success(
                stdout = outCapture.toString(),
                stderr = errCapture.toString()
            )
        } catch (e: NoSuchMethodException) {
            ExecutionResult.Failure(
                error = "No main method found in class ${compilationResult.className}",
                exception = e
            )
        } catch (e: Exception) {
            val cause = e.cause ?: e
            ExecutionResult.Failure(
                error = cause.message ?: "Unknown execution error",
                exception = cause
            )
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
    }

    fun compileAndExecute(
        sourceFile: Path,
        stdlibPath: Path? = null,
        args: Array<String> = emptyArray()
    ): Pair<CompilationResult, ExecutionResult?> {
        val compilationResult = compile(sourceFile, stdlibPath)

        return when (compilationResult) {
            is CompilationResult.Success -> {
                val executionResult = execute(compilationResult, args)
                compilationResult to executionResult
            }
            is CompilationResult.Failure -> {
                compilationResult to null
            }
        }
    }

    private fun extractClassName(sourceContent: String, fileName: String): String {
        val packageRegex = Regex("""package\s+([\w.]+)\s*;""")
        val classRegex = Regex("""public\s+class\s+(\w+)""")

        val packageMatch = packageRegex.find(sourceContent)
        val classMatch = classRegex.find(sourceContent)

        val simpleClassName = classMatch?.groupValues?.get(1)
            ?: fileName.removeSuffix(".java")

        return if (packageMatch != null) {
            "${packageMatch.groupValues[1]}.$simpleClassName"
        } else {
            simpleClassName
        }
    }
}

package internal

sealed class CompilationResult {
    data class Success(
        val className: String,
        val classLoader: ClassLoader,
        val compiledClass: Class<*>
    ) : CompilationResult()

    data class Failure(
        val errors: List<CompilationError>
    ) : CompilationResult()
}

data class CompilationError(
    val line: Long,
    val column: Long,
    val message: String
)

sealed class ExecutionResult {
    data class Success(
        val stdout: String,
        val stderr: String
    ) : ExecutionResult()

    data class Failure(
        val error: String,
        val exception: Throwable
    ) : ExecutionResult()
}

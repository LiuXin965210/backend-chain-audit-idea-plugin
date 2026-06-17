package com.liuxin.backendchain.silent

sealed class SilentTask {
    abstract val outputs: SilentTaskOutputs

    data class Http(
        val input: String,
        override val outputs: SilentTaskOutputs
    ) : SilentTask()

    data class Mq(
        val input: String,
        override val outputs: SilentTaskOutputs
    ) : SilentTask()

    data class BatchHttp(
        val inputs: List<String>,
        override val outputs: SilentTaskOutputs
    ) : SilentTask()
}

data class SilentTaskOutputs(
    val markdown: String? = null,
    val csv: String? = null,
    val mermaid: String? = null
) {
    fun isEmpty(): Boolean = markdown.isNullOrBlank() && csv.isNullOrBlank() && mermaid.isNullOrBlank()
}

data class SilentTaskFile(val tasks: List<SilentTask>)


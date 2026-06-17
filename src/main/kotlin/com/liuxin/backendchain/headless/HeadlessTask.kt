package com.liuxin.backendchain.headless

sealed class HeadlessTask {
    abstract val outputs: HeadlessTaskOutputs

    data class Http(
        val input: String,
        override val outputs: HeadlessTaskOutputs
    ) : HeadlessTask()

    data class Mq(
        val input: String,
        override val outputs: HeadlessTaskOutputs
    ) : HeadlessTask()

    data class BatchHttp(
        val inputs: List<String>,
        override val outputs: HeadlessTaskOutputs
    ) : HeadlessTask()
}

data class HeadlessTaskOutputs(
    val markdown: String? = null,
    val csv: String? = null,
    val mermaid: String? = null
) {
    fun isEmpty(): Boolean = markdown.isNullOrBlank() && csv.isNullOrBlank() && mermaid.isNullOrBlank()
}

data class HeadlessTaskFile(val tasks: List<HeadlessTask>)


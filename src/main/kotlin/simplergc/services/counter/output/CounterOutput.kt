package simplergc.services.counter.output

import simplergc.services.SimpleOutput

/**
 * Outputs the result of cell counting.
 */
abstract class CounterOutput : SimpleOutput() {

    companion object {
        const val PLUGIN_NAME = "RGC Counter"
        const val PLUGIN_VERSION = "0.0.1"
    }

    abstract fun addCountForFile(count: Int, file: String)
}

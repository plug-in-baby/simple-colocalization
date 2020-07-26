package simplergc.services.colocalizer.output

import simplergc.commands.RGCTransduction.TransductionResult
import simplergc.services.BaseRow
import simplergc.services.BooleanField
import simplergc.services.CellColocalizationService
import simplergc.services.DoubleField
import simplergc.services.IntField
import simplergc.services.Output
import simplergc.services.Parameters
import simplergc.services.StringField
import simplergc.services.Table

data class DocumentationRow(val key: String, val description: String) : BaseRow {
    override fun toList() = listOf(StringField(key), StringField(description))
}

data class ParametersRow(
    val fileName: String,
    val morphologyChannel: Int,
    val excludeAxonsFromMorphologyChannel: Boolean,
    val transductionChannel: Int,
    val excludeAxonsFromTransductionChannel: Boolean,
    val cellDiameterText: String,
    val localThresholdRadius: Int,
    val gaussianBlurSigma: Double
) : BaseRow {
    override fun toList() = listOf(
        StringField(fileName),
        StringField(ColocalizationOutput.PLUGIN_NAME),
        StringField(Output.PLUGIN_VERSION),
        IntField(morphologyChannel),
        BooleanField(excludeAxonsFromMorphologyChannel),
        IntField(transductionChannel),
        BooleanField(excludeAxonsFromTransductionChannel),
        StringField(cellDiameterText),
        IntField(localThresholdRadius),
        DoubleField(gaussianBlurSigma)
    )
}

data class SummaryRow(
    val fileName: String,
    val summary: TransductionResult
) : BaseRow {
    override fun toList() = listOf(
        StringField(fileName),
        IntField(summary.targetCellCount),
        IntField(summary.transducedCellCount),
        DoubleField(summary.transductionEfficiency)
    )
}

data class TransductionAnalysisRow(
    val fileName: String,
    val transducedCell: Int,
    val cellAnalysis: CellColocalizationService.CellAnalysis
) : BaseRow {
    override fun toList() = listOf(
        StringField(fileName),
        IntField(transducedCell),
        IntField(cellAnalysis.area),
        IntField(cellAnalysis.mean),
        IntField(cellAnalysis.median),
        IntField(cellAnalysis.min),
        IntField(cellAnalysis.max),
        IntField(cellAnalysis.rawIntDen)
    )
}

/**
 * Outputs the result of cell counting.
 */
abstract class ColocalizationOutput(val transductionParameters: Parameters.Transduction) : Output {

    private val fileNameAndResultsList = mutableListOf<Pair<String, TransductionResult>>()

    val transducedChannel = transductionParameters.transducedChannel

    companion object {
        const val PLUGIN_NAME = "RGC Transduction"
    }

    open fun addTransductionResultForFile(transductionResult: TransductionResult, file: String) {
        fileNameAndResultsList.add(Pair(file, transductionResult))
    }

    abstract fun writeSummary()
    abstract fun writeAnalysis()
    abstract fun writeParameters()
    abstract fun writeDocumentation()

    fun documentationData(): Table = Table(listOf()).apply {
        addRow(DocumentationRow("The article: ", "TODO: Insert citation"))
        addRow(DocumentationRow("", ""))
        addRow(DocumentationRow("Abbreviation", "Description"))
        addRow(DocumentationRow("Summary", "Key measurements per image"))
        addRow(DocumentationRow("Transduced cells analysis", "Per-cell metrics of transduced cells"))
        addRow(DocumentationRow("Parameters", "Parameters used to run the SimpleRGC plugin"))
    }

    fun summaryData(): Table {
        val t = Table(
            listOf(
                "File Name",
                "Number of Cells",
                "Number of Transduced Cells",
                "Transduction Efficiency (%)",
                "Average Morphology Area (pixel^2)",
                "Mean Fluorescence Intensity (a.u.)",
                "Median Fluorescence Intensity (a.u.)",
                "Min Fluorescence Intensity (a.u.)",
                "Max Fluorescence Intensity (a.u.)",
                "RawIntDen"
            )
        )
        // Add summary data.
        for ((fileName, result) in fileNameAndResultsList) {
            t.addRow(SummaryRow(fileName = fileName, summary = result))
        }
        return t
    }

    fun analysisData(): Table {
        val t = Table(
            listOf(
                "File Name",
                "Transduced Cell",
                "Morphology Area (pixel^2)",
                "Mean Fluorescence Intensity (a.u.)",
                "Median Fluorescence Intensity (a.u.)",
                "Min Fluorescence Intensity (a.u.)",
                "Max Fluorescence Intensity (a.u.)",
                "RawIntDen"
            )
        )
        for ((fileName, result) in fileNameAndResultsList) {
            result.channelResults[transducedChannel].cellAnalyses.forEachIndexed { i, cellAnalysis ->
                t.addRow(
                    TransductionAnalysisRow(
                        fileName = fileName,
                        transducedCell = i + 1,
                        cellAnalysis = cellAnalysis
                    )
                )
            }
        }
        return t
    }

    fun parameterData(): Table {
        val t = Table(
            listOf(
                "File Name",
                "SimpleRGC Plugin",
                "Plugin Version",
                "Morphology channel",
                "Exclude Axons from morphology channel?",
                "Transduction channel",
                "Exclude Axons from transduction channel?",
                "Cell diameter range (px)",
                "Local threshold radius",
                "Gaussian blur sigma"
            )
        )
        // Add parameter data.
        for ((fileName, _) in fileNameAndResultsList) {
            t.addRow(
                ParametersRow(
                    fileName = fileName,
                    morphologyChannel = transductionParameters.targetChannel,
                    excludeAxonsFromMorphologyChannel = transductionParameters.shouldRemoveAxonsFromTargetChannel,
                    transductionChannel = transductionParameters.transducedChannel,
                    excludeAxonsFromTransductionChannel = transductionParameters.shouldRemoveAxonsFromTransductionChannel,
                    cellDiameterText = transductionParameters.cellDiameterText,
                    localThresholdRadius = transductionParameters.localThresholdRadius,
                    gaussianBlurSigma = transductionParameters.gaussianBlurSigma
                )
            )
        }
        return t
    }
}

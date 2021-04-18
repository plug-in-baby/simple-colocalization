package simplergc.services.colocalizer.output

import java.io.File
import org.apache.commons.io.FilenameUtils
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import simplergc.services.Aggregate
import simplergc.services.AggregateRow
import simplergc.services.CellColocalizationService
import simplergc.services.Field
import simplergc.services.FieldRow
import simplergc.services.HeaderField
import simplergc.services.HorizontallyMergedHeaderField
import simplergc.services.IntField
import simplergc.services.Metric
import simplergc.services.Metric.ChannelSelection.TRANSDUCTION_ONLY
import simplergc.services.Parameters
import simplergc.services.StringField
import simplergc.services.Table
import simplergc.services.VerticallyMergedHeaderField
import simplergc.services.XlsxAggregateGenerator
import simplergc.services.XlsxTableWriter

/**
 * Outputs the analysis with the result of overlapping, transduced cells in XLSX format.
 */
class XlsxColocalizationOutput(
    private val outputFile: File,
    transductionParameters: Parameters.Transduction,
    private val workbook: XSSFWorkbook = XSSFWorkbook()
) :
    ColocalizationOutput(transductionParameters) {

    override val tableWriter = XlsxTableWriter(workbook)

    fun writeWorkbook() {
        val filename = FilenameUtils.removeExtension(outputFile.path) ?: "Untitled"
        val file = File("$filename.xlsx")
        val outputStream = file.outputStream()

        workbook.write(outputStream)
        outputStream.close()
        workbook.close()
    }

    override fun output() {
        writeDocumentation()
        writeSummary()
        writeAnalysis()
        writeParameters()
        writeWorkbook()
    }

    override fun writeDocumentation() {
        tableWriter.produce(documentationData(), "Documentation")
    }

    override fun generateAggregateRow(
        aggregate: Aggregate,
        rawValues: List<List<Number>>,
        spaces: Int,
        startRow: Int
    ): AggregateRow {
        var column = 'B' + spaces
        val rowValues = mutableListOf<Field<*>>()
        rawValues.forEach { values ->
            rowValues.add(aggregate.generateValue(
                XlsxAggregateGenerator(startRow, column++, values.size)
            )
            )
        }
        return AggregateRow(aggregate.abbreviation, rowValues, spaces)
    }

    override fun writeSummary() {
        val t: Table = getSummaryTable()
        tableWriter.produce(t, "Summary")
    }

    override fun writeSummaryWithAggregates() {
        val t: Table = getSummaryTable()
        val rawCellCounts = mutableListOf<Int>()
        val rawTransducedCellCounts = mutableListOf<Int>()
        val rawTransductionEfficiencies = mutableListOf<Number>()
        val rawMorphAreas = mutableListOf<Number>()
        val numChannels = channelNames().size
        val rawChannelMeans = Array<MutableList<Number>>(numChannels) { mutableListOf() }
        val rawChannelMedians = Array<MutableList<Number>>(numChannels) { mutableListOf() }
        val rawChannelMins = Array<MutableList<Number>>(numChannels) { mutableListOf() }
        val rawChannelMaxs = Array<MutableList<Number>>(numChannels) { mutableListOf() }
        val rawChannelIntDens = Array<MutableList<Number>>(numChannels) { mutableListOf() }
        for ((_, result) in fileNameAndResultsList) {
            rawCellCounts.add(result.targetCellCount)
            rawTransducedCellCounts.add(result.transducedCellCount)
            rawTransductionEfficiencies.add(result.transductionEfficiency)
            rawMorphAreas.add(result.channelResults[0].avgMorphologyArea)
            for (i in 0 until numChannels) {
                rawChannelMeans[i].add(result.channelResults[i].meanFluorescenceIntensity)
                rawChannelMedians[i].add(result.channelResults[i].medianFluorescenceIntensity)
                rawChannelMins[i].add(result.channelResults[i].minFluorescenceIntensity)
                rawChannelMaxs[i].add(result.channelResults[i].maxFluorescenceIntensity)
                rawChannelIntDens[i].add(result.channelResults[i].rawIntDen)
            }
        }
        val rawValues = mutableListOf<List<Number>>(rawCellCounts, rawTransducedCellCounts)
        // TODO: Create aggregate for total.
        val totalRow = AggregateRow(
            "Total",
            listOf(IntField(rawCellCounts.sum()), IntField(rawTransducedCellCounts.sum())),
            spaces = 0
        )
        t.addRow(totalRow)
        rawValues.addAll(listOf(rawTransductionEfficiencies, rawMorphAreas))
        rawValues.addAll(rawChannelMeans)
        rawValues.addAll(rawChannelMedians)
        rawValues.addAll(rawChannelMins)
        rawValues.addAll(rawChannelMaxs)
        rawValues.addAll(rawChannelIntDens)
        SUMMARY_AGGREGATES.forEach {
            t.addRow(generateAggregateRow(it, rawValues, spaces = 0, startRow = 3))
        }
        tableWriter.produce(t, "Summary")
    }

    override fun getSummaryTable(): Table {
        val channelNames = channelNames()
        val headers = mutableListOf(
            "File Name",
            "Number of Cells",
            "Number of Transduced Cells",
            "Transduction Efficiency (%)"
        ).map { VerticallyMergedHeaderField(HeaderField(it), 2) }

        val subHeaders: MutableList<Field<*>> = MutableList(headers.size) { StringField("") }
        val metricHeaders = mutableListOf<Field<*>>()

        for (metric in Metric.values()) {
            if (metric.channels == TRANSDUCTION_ONLY) {
                metricHeaders.add(VerticallyMergedHeaderField(HeaderField(metric.summaryHeader), 2))
                subHeaders.add(StringField(""))
            } else {
                metricHeaders.add(HorizontallyMergedHeaderField(HeaderField(metric.summaryHeader), channelNames.size))
                for (channelName in channelNames) {
                    subHeaders.add(HeaderField(channelName))
                }
            }
        }

        val t = Table()

        t.addRow(FieldRow(headers + metricHeaders))
        t.addRow(FieldRow(subHeaders))

        // Add summary data.
        for ((fileName, result) in fileNameAndResultsList) {
            // TODO: figure out what raw values are and return as pair with table.
            t.addRow(SummaryRow(fileName = fileName, summary = result))
        }
        return t
    }

    override fun writeAnalysis() {
        val t = Table()
        val channelNames = channelNames()
        val headers = listOf(
            "File Name",
            "Transduced Cell"
        ).map { VerticallyMergedHeaderField(HeaderField(it), 2) }

        val subHeaders: MutableList<Field<*>> = MutableList(headers.size) { StringField("") }
        val metricHeaders = mutableListOf<Field<*>>()

        for (metric in Metric.values()) {
            if (metric.channels == TRANSDUCTION_ONLY) {
                metricHeaders.add(VerticallyMergedHeaderField(HeaderField(metric.full), 2))
                subHeaders.add(StringField(""))
            } else {
                metricHeaders.add(HorizontallyMergedHeaderField(HeaderField(metric.full), channelNames.size))
                for (channelName in channelNames) {
                    subHeaders.add(HeaderField(channelName))
                }
            }
        }

        t.addRow(FieldRow(headers + metricHeaders))
        t.addRow(FieldRow(subHeaders))

        for ((fileName, result) in fileNameAndResultsList) {
            val cellAnalyses = result.channelResults[transducedChannel - 1].cellAnalyses
            for (i in cellAnalyses.indices) {
                val channelAnalyses = mutableListOf<CellColocalizationService.CellAnalysis>()
                for (idx in channelNames.indices) {
                    channelAnalyses.add(result.channelResults[idx].cellAnalyses[i])
                }
                t.addRow(
                    MultiChannelTransductionAnalysisRow(
                        fileName,
                        i + 1,
                        channelAnalyses,
                        transducedChannel - 1
                    )
                )
            }

            for (aggregate in METRIC_AGGREGATES) {
                val rawValues = mutableListOf<List<Number>>()
                for (metric in Metric.values()) {
                    if (metric.channels == TRANSDUCTION_ONLY) {
                        rawValues.add(result.channelResults[transducedChannel - 1].cellAnalyses.map { cell ->
                            metric.compute(cell)
                        })
                    } else {
                        for (idx in channelNames.indices) {
                            rawValues.add(result.channelResults[idx].cellAnalyses.map { cell ->
                                metric.compute(cell)
                            })
                        }
                    }
                }
                t.addRow(generateAggregateRow(aggregate, rawValues, spaces = 1))
            }
        }
        tableWriter.produce(t, "Transduced cell analysis")
    }

    override fun writeParameters() {
        tableWriter.produce(parameterData(), "Parameters")
    }
}

package simplecolocalization.commands

import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import ij.gui.GenericDialog
import ij.gui.HistogramWindow
import ij.gui.MessageDialog
import ij.plugin.ChannelSplitter
import ij.plugin.frame.RoiManager
import ij.process.FloatProcessor
import ij.process.StackStatistics
import java.io.File
import java.io.IOException
import javax.xml.transform.TransformerException
import kotlin.math.max
import kotlin.math.min
import net.imagej.ImageJ
import net.imagej.ops.OpService
import org.apache.commons.io.FilenameUtils
import org.scijava.ItemVisibility
import org.scijava.app.StatusService
import org.scijava.command.Command
import org.scijava.command.Previewable
import org.scijava.log.LogService
import org.scijava.plugin.Parameter
import org.scijava.plugin.Plugin
import org.scijava.ui.UIService
import org.scijava.widget.FileWidget
import org.scijava.widget.NumberWidget
import simplecolocalization.services.CellColocalizationService
import simplecolocalization.services.CellDiameterRange
import simplecolocalization.services.CellSegmentationService
import simplecolocalization.services.DiameterParseException
import simplecolocalization.services.cellcomparator.PixelCellComparator
import simplecolocalization.services.cellcomparator.SubsetPixelCellComparator
import simplecolocalization.services.colocalizer.BucketedNaiveColocalizer
import simplecolocalization.services.colocalizer.ColocalizationAnalysis
import simplecolocalization.services.colocalizer.PositionedCell
import simplecolocalization.services.colocalizer.addToRoiManager
import simplecolocalization.services.colocalizer.drawCells
import simplecolocalization.services.colocalizer.output.CSVColocalizationOutput
import simplecolocalization.services.colocalizer.output.ImageJTableColocalizationOutput
import simplecolocalization.services.colocalizer.output.XMLColocalizationOutput
import simplecolocalization.services.colocalizer.resetRoiManager
import simplecolocalization.widgets.AlignedTextWidget

@Plugin(type = Command::class, menuPath = "Plugins > Simple Cells > Simple Colocalization")
class SimpleColocalization : Command, Previewable {

    private val intensityPercentageThreshold: Float = 90f

    @Parameter
    private lateinit var logService: LogService

    @Parameter
    private lateinit var statusService: StatusService

    @Parameter
    private lateinit var cellSegmentationService: CellSegmentationService

    @Parameter
    private lateinit var cellColocalizationService: CellColocalizationService

    @Parameter
    private lateinit var opsService: OpService

    /**
     * Entry point for UI operations, automatically handling both graphical and
     * headless use of this plugin.
     */
    @Parameter
    private lateinit var uiService: UIService

    @Parameter(
        label = "Channel selection",
        visibility = ItemVisibility.MESSAGE,
        required = false
    )
    private lateinit var channelSelectionHeader: String

    /**
     * Specify the channel for the target cell. ImageJ does not have a way to retrieve
     * the channels available at the parameter initiation stage.
     * By default this is 1 (red) channel.
     */
    @Parameter(
        label = "Cell morphology channel 1",
        min = "1",
        stepSize = "1",
        required = true,
        persist = true
    )
    var targetChannel = 1

    /**
     * Specify the channel for the all cells channel.
     * By default this is the 0 (disabled).
     */
    @Parameter(
        label = "Cell morphology channel 2 (0 to disable)",
        min = "0",
        stepSize = "1",
        required = true,
        persist = true
    )
    var allCellsChannel = 0

    private fun isAllCellsEnabled(): Boolean {
        return allCellsChannel > 0
    }

    /**
     * Specify the channel for the transduced cells.
     * By default this is the 2 (green) channel.
     */
    @Parameter(
        label = "Transduction channel",
        min = "1",
        stepSize = "1",
        required = true,
        persist = true
    )
    var transducedChannel = 2

    @Parameter(
        label = "Preprocessing parameters",
        visibility = ItemVisibility.MESSAGE,
        required = false
    )
    private lateinit var preprocessingParamsHeader: String

    /**
     * Used during the cell identification stage to filter out cells that are too small
     */
    @Parameter(
        label = "Cell diameter for morphology channel 1 (px)",
        description = "Used as minimum/maximum diameter when identifying cells",
        required = true,
        style = AlignedTextWidget.RIGHT,
        persist = true
    )
    var cellDiameterText = "0.0-30.0"

    /**
     * Used as the size of the window over which the threshold will be locally computed.
     */
    @Parameter(
        label = "Local threshold radius",
        // TODO(#133): Improve this description to make more intuitive.
        description = "The radius of the local domain over which the threshold will be computed.",
        min = "1",
        stepSize = "1",
        style = NumberWidget.SPINNER_STYLE,
        required = true,
        persist = true
    )
    var localThresholdRadius = 30

    /**
     * Used during the cell identification stage to filter out cells that are too small
     */
    @Parameter(
        label = "Cell diameter (px) for morphology channel 2 (px) (only if enabled)",
        description = "Used as minimum/maximum diameter when identifying cells",
        required = true,
        style = AlignedTextWidget.RIGHT,
        persist = true
    )
    var allCellDiameterText = "0.0-30.0"

    @Parameter(
        label = "Gaussian blur sigma",
        description = "Sigma value used for blurring the image during the processing," +
            " a lower value is recommended if there are lots of cells densely packed together",
        min = "1",
        stepSize = "1",
        style = NumberWidget.SPINNER_STYLE,
        required = true,
        persist = true
    )
    var gaussianBlurSigma = 3.0

    @Parameter(
        label = "Output parameters",
        visibility = ItemVisibility.MESSAGE,
        required = false
    )
    private lateinit var outputParametersHeader: String

    /**
     * The user can optionally output the results to a file.
     */
    object OutputFormat {
        const val DISPLAY = "Display in ImageJ"
        const val CSV = "Save as CSV file"
        const val XML = "Save as XML file"
    }

    @Parameter(
        label = "Results Output:",
        choices = [OutputFormat.DISPLAY, OutputFormat.CSV, OutputFormat.XML],
        required = true,
        persist = true,
        style = "radioButtonVertical"
    )
    private var outputFormat = OutputFormat.DISPLAY

    @Parameter(
        label = "Output file (if saving):",
        style = "save",
        required = false
    )
    private var outputFile: File? = null

    @Parameter(
        visibility = ItemVisibility.INVISIBLE,
        persist = false,
        callback = "previewChanged"
    )
    private var preview: Boolean = false

    /**
     * Result of transduction analysis for output.
     * @property targetCellCount Number of red channel cells.
     * @property overlappingTransducedIntensityAnalysis Quantification of each transduced cell overlapping target cells.
     * @property overlappingTwoChannelCells List of cells which overlap two channels.
     * @property overlappingThreeChannelCells List of cells which overlap three channels. null if not applicable.
     *
     * TODO(#134): Discuss whether we want to use targetCellCount in the single colocalisation plugin
     */
    data class TransductionResult(
        val targetCellCount: Int, // Number of red cells
        val overlappingTransducedIntensityAnalysis: Array<CellColocalizationService.CellAnalysis>,
        val overlappingTwoChannelCells: List<PositionedCell>,
        val overlappingThreeChannelCells: List<PositionedCell>?
    )

    override fun run() {
        val image = WindowManager.getCurrentImage()
        if (image == null) {
            MessageDialog(IJ.getInstance(), "Error", "There is no file open")
            return
        }

        val cellDiameterRange: CellDiameterRange
        val allCellDiameterRange: CellDiameterRange?
        try {
            cellDiameterRange = CellDiameterRange.parseFromText(cellDiameterText)
            allCellDiameterRange =
                if (isAllCellsEnabled()) CellDiameterRange.parseFromText(allCellDiameterText) else null
        } catch (e: DiameterParseException) {
            MessageDialog(IJ.getInstance(), "Error", e.message)
            return
        }

        // TODO(#135): Remove duplication in this code fragment.
        if (outputFormat != OutputFormat.DISPLAY && outputFile == null) {
            val path = image.originalFileInfo.directory
            val name = FilenameUtils.removeExtension(image.originalFileInfo.fileName) + ".csv"
            outputFile = File(path + name)
            if (!outputFile!!.createNewFile()) {
                val dialog = GenericDialog("Warning")
                dialog.addMessage("Overwriting file \"$name\"")
                dialog.showDialog()
                if (dialog.wasCanceled()) return
            }
        }

        resetRoiManager()

        val result = try {
            process(image, cellDiameterRange, allCellDiameterRange)
        } catch (e: ChannelDoesNotExistException) {
            MessageDialog(IJ.getInstance(), "Error", e.message)
            return
        }

        statusService.showStatus(100, 100, "Done!")
        writeOutput(result)

        image.show()
        addToRoiManager(result.overlappingTwoChannelCells)
        // showHistogram(result.overlappingTransducedIntensityAnalysis)
    }

    private fun writeOutput(result: TransductionResult) {
        val output = when (outputFormat) {
            OutputFormat.DISPLAY -> ImageJTableColocalizationOutput(result, uiService)
            OutputFormat.CSV -> CSVColocalizationOutput(result, outputFile!!)
            OutputFormat.XML -> XMLColocalizationOutput(result, outputFile!!)
            else -> throw IllegalArgumentException("Invalid output type provided")
        }

        try {
            output.output()
        } catch (te: TransformerException) {
            displayOutputFileErrorDialog(filetype = "XML")
        } catch (ioe: IOException) {
            displayOutputFileErrorDialog()
        }

        // The colocalization results are clearly displayed if the output
        // destination is set to DISPLAY, however, a visual confirmation
        // is useful if the output is saved to file.
        if (outputFormat != OutputFormat.DISPLAY) {
            MessageDialog(
                IJ.getInstance(),
                "Saved",
                "The colocalization results have successfully been saved to the specified file"
            )
        }
    }

    /** Processes single image. */
    @Throws(ChannelDoesNotExistException::class)
    fun process(
        image: ImagePlus,
        cellDiameterRange: CellDiameterRange,
        allCellDiameterRange: CellDiameterRange? = null
    ): TransductionResult {
        val imageChannels = ChannelSplitter.split(image)
        if (targetChannel < 1 || targetChannel > imageChannels.size) {
            throw ChannelDoesNotExistException("Target channel selected ($targetChannel) does not exist. There are ${imageChannels.size} channels available")
        }

        if (transducedChannel < 1 || transducedChannel > imageChannels.size) {
            throw ChannelDoesNotExistException("Transduced channel selected ($transducedChannel) does not exist. There are ${imageChannels.size} channels available")
        }

        if (isAllCellsEnabled() && allCellsChannel > imageChannels.size) {
            throw ChannelDoesNotExistException("All cells channel selected ($allCellsChannel) does not exist. There are ${imageChannels.size} channels available")
        }

        return analyseTransduction(
            imageChannels[targetChannel - 1],
            imageChannels[transducedChannel - 1],
            cellDiameterRange,
            if (isAllCellsEnabled()) imageChannels[allCellsChannel - 1] else null,
            allCellDiameterRange
        )
    }

    fun analyseTransduction(
        targetChannel: ImagePlus,
        transducedChannel: ImagePlus,
        cellDiameterRange: CellDiameterRange,
        allCellsChannel: ImagePlus? = null,
        allCellDiameterRange: CellDiameterRange?
    ): TransductionResult {
        logService.info("Starting extraction")
        val targetCells = cellSegmentationService.extractCells(
            targetChannel,
            cellDiameterRange,
            localThresholdRadius,
            gaussianBlurSigma
        )

        // Allow cells in the transduced channel to have unbounded area
        val transducedCells = filterCellsByIntensity(
            cellSegmentationService.extractCells(
                transducedChannel,
                CellDiameterRange(cellDiameterRange.smallest, Double.MAX_VALUE),
                localThresholdRadius,
                gaussianBlurSigma
            ),
            transducedChannel
        )
        // TODO(#105) ^^
        val allCells =
            if (allCellsChannel != null && allCellDiameterRange != null) cellSegmentationService.extractCells(
                allCellsChannel,
                allCellDiameterRange,
                localThresholdRadius,
                gaussianBlurSigma
            ) else null

        statusService.showStatus(80, 100, "Analysing transduction...")
        logService.info("Starting analysis")

        // Target layer is based and transduced layer is overlaid.
        val targetTransducedAnalysis = BucketedNaiveColocalizer(
            cellDiameterRange.largest.toInt(),
            targetChannel.width,
            targetChannel.height,
            SubsetPixelCellComparator(threshold = 0.5f)
        ).analyseColocalization(targetCells, transducedCells)

        val transductionIntensityAnalysis = cellColocalizationService.analyseCellIntensity(
            transducedChannel,
            targetTransducedAnalysis.overlappingOverlaid.map { it.toRoi() }.toTypedArray()
        )

        var allCellsAnalysis: ColocalizationAnalysis? = null
        if (allCells != null) {
            allCellsAnalysis = BucketedNaiveColocalizer(
                cellDiameterRange.largest.toInt(),
                allCellsChannel!!.width,
                allCellsChannel.height,
                PixelCellComparator(threshold = 0.01f)
            ).analyseColocalization(targetTransducedAnalysis.overlappingOverlaid, allCells)
        }

        // We return the overlapping target channel instead of transduced channel as we want to mark the target layer,
        // not the transduced layer.
        return TransductionResult(
            targetCellCount = targetCells.size,
            overlappingTransducedIntensityAnalysis = transductionIntensityAnalysis,
            overlappingTwoChannelCells = targetTransducedAnalysis.overlappingBase,
            overlappingThreeChannelCells = allCellsAnalysis?.overlappingBase
        )
    }

    /**
     * Filter the position cells by their average intensity in the given image
     * using the intensityPercentageThreshold.
     */
    private fun filterCellsByIntensity(cells: List<PositionedCell>, image: ImagePlus): List<PositionedCell> {
        var maxIntensity = 0.0f
        var minIntensity = Float.MAX_VALUE
        val intensities = cells.map {
            val intensity = it.getMeanIntensity(image)
            maxIntensity = max(maxIntensity, intensity)
            minIntensity = min(minIntensity, intensity)
            intensity
        }
        val threshold = maxIntensity - (maxIntensity - minIntensity) * (intensityPercentageThreshold / 100)
        val thresholdedCells = mutableListOf<PositionedCell>()
        intensities.forEachIndexed { index, intensity ->
            if (intensity > threshold) {
                thresholdedCells.add(cells[index])
            }
        }
        return thresholdedCells
    }

    /**
     * Displays the resulting colocalization results as a histogram.
     */
    private fun showHistogram(analysis: Array<CellColocalizationService.CellAnalysis>) {
        val data = analysis.map { it.median.toFloat() }.toFloatArray()
        val ip = FloatProcessor(analysis.size, 1, data, null)
        val imp = ImagePlus("", ip)
        val stats = StackStatistics(imp, 256, 0.0, 256.0)
        var maxCount = 0
        for (i in stats.histogram.indices) {
            if (stats.histogram[i] > maxCount)
                maxCount = stats.histogram[i]
        }
        stats.histYMax = maxCount
        HistogramWindow("Median intensity distribution of transduced cells overlapping target cells", imp, stats)
    }

    companion object {
        /**
         * Entry point to directly open the plugin, used for debugging purposes.
         *
         * @throws Exception
         */
        @Throws(Exception::class)
        @JvmStatic
        fun main(args: Array<String>) {
            val ij = ImageJ()

            ij.context().inject(CellSegmentationService())
            ij.context().inject(CellColocalizationService())
            ij.launch()

            val file: File = ij.ui().chooseFile(null, FileWidget.OPEN_STYLE)
            val imp = IJ.openImage(file.path)
            imp.show()
            ij.command().run(SimpleColocalization::class.java, true)
        }
    }

    override fun preview() {
        if (preview) {
            val image = WindowManager.getCurrentImage()
            if (image == null) {
                MessageDialog(IJ.getInstance(), "Error", "There is no file open")
                return
            }

            val cellDiameterRange: CellDiameterRange
            val allCellDiameterRange: CellDiameterRange?
            try {
                cellDiameterRange = CellDiameterRange.parseFromText(cellDiameterText)
                allCellDiameterRange =
                    if (isAllCellsEnabled()) CellDiameterRange.parseFromText(allCellDiameterText) else null
            } catch (e: DiameterParseException) {
                cancel()
                return
            }

            val result = try {
                process(image, cellDiameterRange, allCellDiameterRange)
            } catch (e: ChannelDoesNotExistException) {
                cancel()
                return
            }

            image.show()
            drawCells(image, result.overlappingTwoChannelCells)
        }
    }

    override fun cancel() {
        val roiManager = RoiManager.getRoiManager()
        roiManager.reset()
        roiManager.close()
    }

    /** Called when the preview parameter value changes. */
    private fun previewChanged() {
        if (!preview) cancel()
    }
}

class ChannelDoesNotExistException(message: String) : Exception(message)

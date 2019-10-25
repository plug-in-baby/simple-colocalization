package simplecolocalization

import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import ij.gui.MessageDialog
import ij.plugin.ZProjector
import ij.plugin.frame.RoiManager
import java.io.File
import net.imagej.Dataset
import net.imagej.ImageJ
import org.scijava.ItemVisibility
import org.scijava.command.Command
import org.scijava.log.LogService
import org.scijava.plugin.Parameter
import org.scijava.plugin.Plugin
import org.scijava.table.DefaultGenericTable
import org.scijava.table.IntColumn
import org.scijava.ui.UIService
import org.scijava.widget.NumberWidget

/**
 * Segments and counts cells which are almost circular in shape which are likely
 * to overlap.
 *
 * When this plugin is started in a graphical context (as opposed to the command
 * line), a dialog box is opened with the script parameters below as input.
 *
 * [run] contains the main pipeline, which runs only after the script parameters
 * are populated.
 */
@Plugin(type = Command::class, menuPath = "Plugins > Simple Colocalization")
class SimpleColocalization : Command {

    @Parameter
    private lateinit var logService: LogService

    @Parameter
    private lateinit var cellSegmentationService: CellSegmentationService

    /**
     * Entry point for UI operations, automatically handling both graphical and
     * headless use of this plugin.
     */
    @Parameter
    private lateinit var uiService: UIService

    @Parameter(
        label = "Preprocessing Parameters:",
        visibility = ItemVisibility.MESSAGE,
        required = false
    )
    private lateinit var preprocessingParamsHeader: String

    /**
     * Applied to the input image to reduce sensitivity of the thresholding
     * algorithm. Higher value means more blur.
     */
    @Parameter(
        label = "Gaussian Blur Sigma (Radius)",
        description = "Reduces sensitivity to cell edges by blurring the " +
            "overall image. Higher is less sensitive.",
        min = "0.0",
        stepSize = "1.0",
        style = NumberWidget.SPINNER_STYLE,
        required = true,
        persist = false
    )
    private var gaussianBlurSigma = 3.0

    private var meanGreenThreshold = 30.0

    @Parameter(
        label = "Cell Identification Parameters:",
        visibility = ItemVisibility.MESSAGE,
        required = false
    )
    private lateinit var identificationParamsHeader: String

    /**
     * Used during the cell identification stage to reduce overlapping cells
     * being grouped into a single cell.
     *
     * TODO(#5): Figure out what this value should be.
     */
    @Parameter(
        label = "Largest Cell Diameter",
        min = "5.0",
        stepSize = "1.0",
        style = NumberWidget.SPINNER_STYLE,
        required = true,
        persist = false
    )
    private var largestCellDiameter = 30.0

    /**
     * Displays the resulting counts as a results table.
     */
    private fun showCount(analyses: Array<CellAnalysis>) {
        val table = DefaultGenericTable()
        val cellCountColumn = IntColumn()
        val greenCountColumn = IntColumn()
        cellCountColumn.add(analyses.size)
        var greenCount = 0
        analyses.forEach { cellAnalysis ->
            if (cellAnalysis.channels[1].mean > meanGreenThreshold) {
                greenCount++
            }
        }
        greenCountColumn.add(greenCount)
        table.add(cellCountColumn)
        table.add(greenCountColumn)
        table.setColumnHeader(0, "Red Cell Count")
        table.setColumnHeader(1, "Green Cell Count")
        uiService.show(table)
    }

    /** Runs after the parameters above are populated. */
    override fun run() {
        var image = WindowManager.getCurrentImage()
        if (image != null) {
            if (image.nSlices > 1) {
                // Flatten slices of the image. This step should probably be done during the preprocessing step - however
                // this operation is not done in-place but creates a new image, which makes this hard.
                image = ZProjector.run(image, "max")
            }

            process(image)
        } else {
            MessageDialog(IJ.getInstance(), "Error", "There is no file open")
        }
    }

    /** Processes single image. */
    private fun process(image: ImagePlus) {
        val originalImage = image.duplicate()

        cellSegmentationService.preprocessImage(image, largestCellDiameter, gaussianBlurSigma)
        cellSegmentationService.segmentImage(image)

        val roiManager = RoiManager.getRoiManager()
        val cells = cellSegmentationService.identifyCells(roiManager, image)
        cellSegmentationService.markCells(originalImage, cells)

        val analysis = cellSegmentationService.analyseCells(originalImage, cells)

        showCount(analysis)
        showPerCellAnalysis(analysis)

        originalImage.show()
    }

    data class CellAnalysis(val area: Int, val channels: List<ChannelAnalysis>)
    data class ChannelAnalysis(val name: String, val mean: Int, val min: Int, val max: Int)

    /**
     * Displays the resulting cell analysis as a results table.
     */
    private fun showPerCellAnalysis(analyses: Array<CellAnalysis>) {
        val table = DefaultGenericTable()

        // If there are no analyses then show an empty table.
        // We wish to access the first analysis later to inspect number of channels
        // so we return to avoid an invalid deference.
        if (analyses.isEmpty()) {
            uiService.show(table)
            return
        }
        val numberOfChannels = analyses[0].channels.size

        // Retrieve the names of all the channels.
        val channelNames = mutableListOf<String>()
        for (i in 0 until numberOfChannels) {
            channelNames.add(analyses[0].channels[i].name.capitalize())
        }

        val areaColumn = IntColumn()

        val meanColumns = MutableList(numberOfChannels) { IntColumn() }
        val maxColumns = MutableList(numberOfChannels) { IntColumn() }
        val minColumns = MutableList(numberOfChannels) { IntColumn() }

        // Construct column values using the channel analysis values.
        analyses.forEach { cellAnalysis ->
            areaColumn.add(cellAnalysis.area)
            cellAnalysis.channels.forEachIndexed { channelIndex, channel ->
                meanColumns[channelIndex].add(channel.mean)
                minColumns[channelIndex].add(channel.min)
                maxColumns[channelIndex].add(channel.max)
            }
        }

        // Add all of the columns (Mean, Min, Max) for each channel.
        table.add(areaColumn)
        for (i in 0 until numberOfChannels) {
            table.add(meanColumns[i])
            table.add(minColumns[i])
            table.add(maxColumns[i])
        }

        // Add all the column headers for each channel.
        var columnIndex = 0
        table.setColumnHeader(columnIndex++, "Area")
        for (i in 0 until numberOfChannels) {
            val channelName = channelNames[i]
            table.setColumnHeader(columnIndex++, "$channelName Mean")
            table.setColumnHeader(columnIndex++, "$channelName Min")
            table.setColumnHeader(columnIndex++, "$channelName Max")
        }

        uiService.show(table)
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
            ij.launch()

            val file: File = ij.ui().chooseFile(null, "open")
            val dataset: Dataset = ij.scifio().datasetIO().open(file.path)

            ij.ui().show(dataset)
            ij.command().run(SimpleColocalization::class.java, true)
        }
    }
}

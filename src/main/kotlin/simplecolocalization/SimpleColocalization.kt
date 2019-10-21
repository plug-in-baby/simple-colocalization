package simplecolocalization

import ij.ImagePlus
import ij.gui.PointRoi
import ij.gui.Roi
import ij.plugin.filter.BackgroundSubtracter
import ij.plugin.filter.EDM
import ij.plugin.filter.GaussianBlur
import ij.plugin.filter.MaximumFinder
import ij.plugin.filter.RankFilters
import ij.process.ImageConverter
import java.io.File
import java.io.FileNotFoundException
import net.imagej.ImageJ
import org.scijava.ItemVisibility
import org.scijava.command.Command
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

    /**
     * Entry point for UI operations, automatically handling both graphical and
     * headless use of this plugin.
     */
    @Parameter
    private lateinit var uiService: UIService

    /** File path of the input image. */
    @Parameter(label = "Input File")
    private lateinit var inputFile: File

    @Parameter(
        label = "Preprocessing Parameters:",
        visibility = ItemVisibility.MESSAGE,
        required = false
    )
    private lateinit var preprocessingParamsHeader: String

    /**
     * Number of slices of the LIF file to be processed.
     */
    @Parameter(
        label = "No. of slices",
        min = "1",
        stepSize = "1",
        style = NumberWidget.SPINNER_STYLE,
        required = true,
        persist = false
    )
    private var numSlices = 1

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
     * TODO (#5): Figure out what this value should be.
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

    /** Displays the resulting count as a results table. */
    private fun showCount(count: Int) {
        val table = DefaultGenericTable()
        val countColumn = IntColumn()
        countColumn.add(count)
        table.add(countColumn)
        table.setColumnHeader(0, "Count")
        uiService.show(table)
    }

    /** Displays aggregate results of stack as a results table. */
    private fun showCounts(counts: List<Int>) {
        val table = DefaultGenericTable()
        val countColumn = IntColumn()
        countColumn.addAll(counts)
        table.add(countColumn)
        table.setColumnHeader(0, "Count")
        uiService.show(table)
    }

    /**
     * Perform pre-processing on the image to remove background and set cells to white.
     */
    private fun preprocessImage(image: ImagePlus) {
        // Convert to grayscale 8-bit.
        ImageConverter(image).convertToGray8()

        // Remove background.
        BackgroundSubtracter().rollingBallBackground(
            image.channelProcessor,
            largestCellDiameter,
            false,
            false,
            false,
            false,
            false
        )

        // Threshold grayscale image, leaving black and white image.
        image.channelProcessor.autoThreshold()

        // Despeckle the image using a median filter with radius 1.0, as defined in ImageJ docs.
        // https://imagej.nih.gov/ij/developer/api/ij/plugin/filter/RankFilters.html
        RankFilters().rank(image.channelProcessor, 1.0, RankFilters.MEDIAN)

        // Apply Gaussian Blur to group larger speckles.
        GaussianBlur().blurGaussian(image.channelProcessor, gaussianBlurSigma)

        // Threshold image to remove blur.
        image.channelProcessor.autoThreshold()
    }

    /**
     * Segment the image into individual cells, overlaying outlines for cells in the image.
     *
     * Uses ImageJ's Euclidean Distance Map plugin for performing the watershed algorithm.
     * Used as a simple starting point that'd allow for cell counting.
     */
    private fun segmentImage(image: ImagePlus) {
        // TODO (#7): Review and improve upon simple watershed.
        EDM().toWatershed(image.channelProcessor)
    }

    /** Runs after the parameters above are populated. */
    override fun run() {
        val opener = ImageJOpener()
        if (inputFile.exists()) {
            if (ImageJOpener.isStack(inputFile)) {
                // Process multiple images.
                process(opener.openStack(inputFile, numSlices))
            } else {
                // Process single image.
                process(opener.openSingleImage(inputFile))
            }
        } else throw FileNotFoundException("${inputFile.absolutePath} does not exist")
    }

    /** Processes stack of multiple images. */
    private fun process(images: List<ImagePlus>) {
        val originalImages = images.map { it.duplicate() }
        images.map { preprocessImage(it) }
        images.map { segmentImage(it) }
        val cellsList = images.map { identifyCells(it) }
        showCounts(cellsList.map { it.size() })
        (originalImages zip cellsList).map { markCells(it.first, it.second) }
        originalImages.map { it.show() }
    }

    /** Processes single image. */
    private fun process(image: ImagePlus) {
        val originalImage = image.duplicate()
        preprocessImage(image)
        segmentImage(image)
        val cells = identifyCells(image)
        showCount(cells.size())
        markCells(originalImage, cells)
        originalImage.show()
    }

    /**
     * Identify the cells in the image, produce a PointRoi containing the points.
     *
     * Uses ImageJ's Find Maxima plugin for identifying the center of cells.
     */
    private fun identifyCells(segmentedImage: ImagePlus): Roi {
        val result = MaximumFinder().getMaxima(segmentedImage.channelProcessor,
            10.0,
            false,
            false)
        return PointRoi(result.xpoints, result.ypoints, result.npoints)
    }

    /**
     * Mark the cell locations in the image.
     */
    private fun markCells(image: ImagePlus, roi: Roi) {
        image.roi = roi
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
            ij.ui().showUI()
            ij.command().run(SimpleColocalization::class.java, true)
        }
    }
}

package simplecolocalization.commands

import ij.IJ
import ij.gui.GenericDialog
import ij.gui.MessageDialog
import java.io.File
import net.imagej.ImageJ
import org.scijava.Context
import org.scijava.command.Command
import org.scijava.log.LogService
import org.scijava.plugin.Parameter
import org.scijava.plugin.Plugin
import org.scijava.ui.UIService
import org.scijava.widget.NumberWidget
import simplecolocalization.preprocessing.PreprocessingParameters
import simplecolocalization.services.CellSegmentationService
import simplecolocalization.services.counter.output.CSVCounterOutput

object PluginChoice {
    const val SIMPLE_CELL_COUNTER = "SimpleCellCounter"
    const val SIMPLE_COLOCALIZATION = "SimpleColocalization"
}

@Plugin(type = Command::class, menuPath = "Plugins > Simple Cells > Simple Batch")
class SimpleBatch : Command {

    @Parameter
    private lateinit var logService: LogService

    @Parameter
    private lateinit var uiService: UIService

    @Parameter
    private lateinit var context: Context

    @Parameter(
        label = "Input Directory:",
        description = "Please select the input directory for batch processing.",
        style = "directory",
        required = true,
        persist = false
    )
    private lateinit var inputDir: File

    /**
     * Used during the cell segmentation stage to perform local thresholding or
     * background subtraction.
     */
    @Parameter(
        label = "Largest Cell Diameter",
        description = "Value we use to apply the rolling ball algorithm to subtract the background when thresholding",
        min = "1",
        stepSize = "1",
        style = NumberWidget.SPINNER_STYLE,
        required = true,
        persist = false
    )
    private var largestCellDiameter = 30.0

    @Parameter(
        label = "Batch Process files in nested folders?",
        required = true
    )
    private var shouldProcessFilesInNestedFolders: Boolean = true

    @Parameter(
        label = "Which plugin do you want to run in Batch Mode?",
        choices = [PluginChoice.SIMPLE_CELL_COUNTER, PluginChoice.SIMPLE_COLOCALIZATION],
        required = true
    )
    private var pluginChoice = PluginChoice.SIMPLE_CELL_COUNTER

    @Parameter(
        label = "Output Directory:",
        description = "Please specify the output directory. Leaving this empty will save a CSV with the same name as the directory you choose as input in that directory.",
        style = "directory",
        required = false,
        persist = false
    )
    private lateinit var outputDir: File

    override fun run() {

        if (!inputDir.exists() or !inputDir.isDirectory) {
            MessageDialog(IJ.getInstance(), "Error",
                "The input folder could not be opened. Please create it if it does not already exist")
            return
        }

        val files = getAllFiles(inputDir, shouldProcessFilesInNestedFolders)

        val tifs = files.filter { it.extension == "tif" || it.extension == "tiff" }
        val lifs = files.filter { it.extension == "lif" }

        if (lifs.isNotEmpty()) {

            val dialog = GenericDialog(".LIF Files Found")

            dialog.addMessage(
                """
                We found ${lifs.size} file(s) with the .LIF extension. 
                Please note that this plugin will skip over files in the .LIF format. 
                Please refer to this plugin's documentation on how to automatically batch convert .LIF files to the accepted .TIF extension.
                """.trimIndent()
            )

            dialog.addMessage("Continue to process only .TIF images in your input directory.")

            dialog.showDialog()

            if (dialog.wasCanceled()) {
                return
            }
        }

        process(tifs)
    }

    private fun getAllFiles(file: File, shouldProcessFilesInNestedFolders: Boolean): List<File> {
        return if (shouldProcessFilesInNestedFolders) {
            file.walkTopDown().filter { f -> !f.isDirectory }.toList()
        } else {
            file.listFiles()?.toList() ?: listOf(file)
        }
    }

    private fun process(tifs: List<File>) {
        when (pluginChoice) {
            PluginChoice.SIMPLE_CELL_COUNTER -> processSimpleCellCounter(tifs)
            PluginChoice.SIMPLE_COLOCALIZATION -> processSimpleColocalization(tifs)
        }
    }

    private fun processSimpleCellCounter(tifs: List<File>) {
        val simpleCellCounter = SimpleCellCounter()
        context.inject(simpleCellCounter)

        val preprocessingParameters = PreprocessingParameters(largestCellDiameter = largestCellDiameter)

        val numCellsList = tifs.map { simpleCellCounter.countCells(it.absolutePath, preprocessingParameters).size }
        val imageAndCount = tifs.zip(numCellsList)

        val outputFilename = inputDir.nameWithoutExtension + ".csv"
        println(outputDir)
        val outputFile = File(outputDir.toString() + outputFilename)
        // Output is to csv by default.
        val output = CSVCounterOutput(outputFile)
        imageAndCount.forEach { output.addCountForFile(it.second, it.first.name) }
        output.save()
    }

    private fun processSimpleColocalization(tifs: List<File>) {
        // TODO("Batch SimpleColocalization unimplemented")
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
            ij.launch()

            ij.command().run(SimpleBatch::class.java, true)
        }
    }
}

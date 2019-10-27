package simplecolocalization.services

import ij.ImagePlus
import ij.gui.Roi
import ij.measure.Measurements
import ij.measure.ResultsTable
import ij.plugin.filter.BackgroundSubtracter
import ij.plugin.filter.EDM
import ij.plugin.filter.MaximumFinder
import ij.plugin.filter.ParticleAnalyzer
import ij.plugin.filter.RankFilters
import ij.plugin.frame.RoiManager
import ij.process.ImageConverter
import io.minio.errors.InvalidArgumentException
import net.imagej.ImageJService
import org.scijava.plugin.Plugin
import org.scijava.service.AbstractService
import org.scijava.service.Service

@Plugin(type = Service::class)
class CellSegmentationService : AbstractService(), ImageJService {

    data class CellAnalysis(val area: Int, val channels: List<ChannelAnalysis>)
    data class ChannelAnalysis(val name: String, val mean: Int, val min: Int, val max: Int)

    /** Preprocess image wrapper for SimpleColocalisation Plugin (temp fix). */
    fun preprocessImage(
        image: ImagePlus,
        largestCellDiameter: Double,
        gaussianBlurSigma: Double
    ) {
        preprocessImage(image, true, largestCellDiameter, "Global", "Otsu's", 30.0, true, 1.0, true, gaussianBlurSigma)
    }

    /** Perform pre-processing on the image to remove background and set cells to white. */
    fun preprocessImage(
        image: ImagePlus,
        subtractBackground: Boolean,
        largestCellDiameter: Double,
        thresholdChoice: String,
        thresholdAlgo: String,
        localThresholdRadius: Double,
        despeckle: Boolean,
        despeckleRadius: Double,
        gaussianBlur: Boolean,
        gaussianBlurSigma: Double
    ) {
        // Convert to grayscale 8-bit.
        ImageConverter(image).convertToGray8()

        if (subtractBackground) {
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
        }

        // Threshold grayscale image, leaving black and white image.
        thresholdImage(image, thresholdChoice, thresholdAlgo, localThresholdRadius)

        if (despeckle) {
            // Despeckle the image using a median filter with radius 1.0, as defined in ImageJ docs.
            // https://imagej.nih.gov/ij/developer/api/ij/plugin/filter/RankFilters.html
            RankFilters().rank(image.channelProcessor, despeckleRadius, RankFilters.MEDIAN)
        }

        if (gaussianBlur) {
            // Apply Gaussian Blur to group larger speckles.
            image.channelProcessor.blurGaussian(gaussianBlurSigma)
        }

        // Threshold image again to remove blur.
        thresholdImage(image, thresholdChoice, thresholdAlgo, localThresholdRadius)
    }

    fun thresholdImage(image: ImagePlus, thresholdChoice: String, thresholdAlgo: String, localThresholdRadius: Double) {
        when (thresholdChoice) {
            "Global" -> {
                image.channelProcessor.autoThreshold()
                when (thresholdAlgo) {
                    "Otsu's" -> return
                    "Bernsen's" -> return
                    "Niblack's" -> return
                    else ->  throw InvalidArgumentException("Threshold Algorithm selected")
                }
            }
            "Local" -> {
                // TODO: @Rasika use localThresholdRadius here and call local thresholding plugin
                when (thresholdAlgo) {
                    "Otsu's" -> return
                    "Bernsen's" -> return
                    "Niblack's" -> return
                    else -> throw InvalidArgumentException("Threshold Algorithm selected")
                }
            }
            else -> throw InvalidArgumentException("Invalid Threshold Choice selected")
        }
    }

    /**
     * Segment the image into individual cells, overlaying outlines for cells in the image.
     *
     * Uses ImageJ's Euclidean Distance Map plugin for performing the watershed algorithm.
     * Used as a simple starting point that'd allow for cell counting.
     */
    fun segmentImage(image: ImagePlus) {
        // TODO(#7): Review and improve upon simple watershed.
        EDM().toWatershed(image.channelProcessor)
    }

    /**
     * Select each cell identified in the segmented image in the original image.
     *
     * We use [ParticleAnalyzer] instead of [MaximumFinder] as the former highlights the shape of the cell instead
     * of just marking its centre.
     */
    fun identifyCells(roiManager: RoiManager, segmentedImage: ImagePlus): Array<Roi> {
        ParticleAnalyzer.setRoiManager(roiManager)
        ParticleAnalyzer(
            ParticleAnalyzer.SHOW_NONE or ParticleAnalyzer.ADD_TO_MANAGER,
            Measurements.ALL_STATS,
            ResultsTable(),
            0.0,
            Double.MAX_VALUE
        ).analyze(segmentedImage)
        return roiManager.roisAsArray
    }

    /** Mark the cell locations in the image. */
    fun markCells(image: ImagePlus, rois: Array<Roi>) {
        for (roi in rois) {
            roi.image = image
        }
    }
}

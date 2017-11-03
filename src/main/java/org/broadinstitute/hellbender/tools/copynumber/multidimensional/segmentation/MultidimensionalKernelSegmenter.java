package org.broadinstitute.hellbender.tools.copynumber.multidimensional.segmentation;

import htsjdk.samtools.util.Locatable;
import htsjdk.samtools.util.OverlapDetector;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.hellbender.tools.copynumber.allelic.alleliccount.AllelicCount;
import org.broadinstitute.hellbender.tools.copynumber.allelic.alleliccount.AllelicCountCollection;
import org.broadinstitute.hellbender.tools.copynumber.coverage.copyratio.CopyRatio;
import org.broadinstitute.hellbender.tools.copynumber.coverage.copyratio.CopyRatioCollection;
import org.broadinstitute.hellbender.tools.copynumber.formats.collections.SampleLocatableCollection;
import org.broadinstitute.hellbender.tools.copynumber.utils.segmentation.KernelSegmenter;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.param.ParamUtils;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TODO
 *
 * @author Samuel Lee &lt;slee@broadinstitute.org&gt;
 */
public final class MultidimensionalKernelSegmenter {
    private static final Logger logger = LogManager.getLogger(MultidimensionalKernelSegmenter.class);

    private static final int MIN_NUM_POINTS_REQUIRED_PER_CHROMOSOME = 10;

    //assume alternate-allele fraction is 0.5 for missing data
    private static final SimpleInterval DUMMY_INTERVAL = new SimpleInterval("DUMMY", 1, 1);
    private static final AllelicCount BALANCED_ALLELIC_COUNT = new AllelicCount(DUMMY_INTERVAL, 1, 1);

    //Gaussian kernel for a specified variance; if variance is zero, use a linear kernel
    private static final Function<Double, BiFunction<Double, Double, Double>> KERNEL =
            standardDeviation -> standardDeviation == 0.
                    ? (x, y) -> x * y
                    : (x, y) -> new NormalDistribution(null, x, standardDeviation).density(y);

    static final class MultidimensionalPoint implements Locatable {
        private final SimpleInterval interval;
        private final double log2CopyRatio;
        private final double alternateAlleleFraction;

        MultidimensionalPoint(final SimpleInterval interval,
                              final double log2CopyRatio,
                              final double alternateAlleleFraction) {
            this.interval = interval;
            this.log2CopyRatio = log2CopyRatio;
            this.alternateAlleleFraction = alternateAlleleFraction;
        }

        @Override
        public String getContig() {
            return interval.getContig();
        }

        @Override
        public int getStart() {
            return interval.getStart();
        }

        @Override
        public int getEnd() {
            return interval.getEnd();
        }
    }

    private final CopyRatioCollection denoisedCopyRatios;
    private final OverlapDetector<CopyRatio> copyRatioMidpointOverlapDetector;
    private final AllelicCountCollection allelicCounts;
    private final OverlapDetector<AllelicCount> allelicCountOverlapDetector;
    private final Map<String, List<SimpleInterval>> intervalsPerChromosome;
    private final Map<String, List<MultidimensionalPoint>> multidimensionalPointsPerChromosome;

    public MultidimensionalKernelSegmenter(final CopyRatioCollection denoisedCopyRatios,
                                           final AllelicCountCollection allelicCounts) {
        Utils.nonNull(denoisedCopyRatios);
        Utils.nonNull(allelicCounts);
        Utils.validateArg(denoisedCopyRatios.getSampleName().equals(allelicCounts.getSampleName()),
                "Sample names do not match.");
        this.denoisedCopyRatios = denoisedCopyRatios;
        copyRatioMidpointOverlapDetector = denoisedCopyRatios.getMidpointOverlapDetector();
        this.allelicCounts = allelicCounts;
        allelicCountOverlapDetector = allelicCounts.getOverlapDetector();
        intervalsPerChromosome = denoisedCopyRatios.getRecords().stream()
                .map(CopyRatio::getInterval)
                .collect(Collectors.groupingBy(
                        SimpleInterval::getContig,
                        LinkedHashMap::new,
                        Collectors.toList()));
        final int numAllelicCountsToUse = (int) denoisedCopyRatios.getRecords().stream()
                .filter(allelicCountOverlapDetector::overlapsAny)
                .count();
        logger.info(String.format("Using first allelic-count site in each copy-ratio interval (%d / %d) for multidimensional segmentation...",
                numAllelicCountsToUse, allelicCounts.size()));
        multidimensionalPointsPerChromosome = denoisedCopyRatios.getRecords().stream()
                .map(cr -> new MultidimensionalPoint(
                        cr.getInterval(),
                        cr.getLog2CopyRatioValue(),
                        allelicCountOverlapDetector.getOverlaps(cr).stream()
                                .min(SampleLocatableCollection.LEXICOGRAPHICAL_ORDER_COMPARATOR::compare)
                                .orElse(BALANCED_ALLELIC_COUNT).getAlternateAlleleFraction()))
                .collect(Collectors.groupingBy(
                        MultidimensionalPoint::getContig,
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    public MultidimensionalSegmentCollection findSegmentation(final int maxNumChangepointsPerChromosome,
                                                              final double kernelVarianceCopyRatio,
                                                              final double kernelVarianceAlleleFraction,
                                                              final double kernelScalingAlleleFraction,
                                                              final int kernelApproximationDimension,
                                                              final List<Integer> windowSizes,
                                                              final double numChangepointsPenaltyLinearFactor,
                                                              final double numChangepointsPenaltyLogLinearFactor) {
        ParamUtils.isPositiveOrZero(maxNumChangepointsPerChromosome, "Maximum number of changepoints must be non-negative.");
        ParamUtils.isPositiveOrZero(kernelVarianceCopyRatio, "Variance of copy-ratio Gaussian kernel must be non-negative (if zero, a linear kernel will be used).");
        ParamUtils.isPositiveOrZero(kernelVarianceAlleleFraction, "Variance of allele-fraction Gaussian kernel must be non-negative (if zero, a linear kernel will be used).");
        ParamUtils.isPositiveOrZero(kernelScalingAlleleFraction, "Scaling of allele-fraction Gaussian kernel must be non-negative.");
        ParamUtils.isPositive(kernelApproximationDimension, "Dimension of kernel approximation must be positive.");
        Utils.validateArg(windowSizes.stream().allMatch(ws -> ws > 0), "Window sizes must all be positive.");
        Utils.validateArg(new HashSet<>(windowSizes).size() == windowSizes.size(), "Window sizes must all be unique.");
        ParamUtils.isPositiveOrZero(numChangepointsPenaltyLinearFactor,
                "Linear factor for the penalty on the number of changepoints per chromosome must be non-negative.");
        ParamUtils.isPositiveOrZero(numChangepointsPenaltyLogLinearFactor,
                "Log-linear factor for the penalty on the number of changepoints per chromosome must be non-negative.");

        final BiFunction<MultidimensionalPoint, MultidimensionalPoint, Double> kernel = constructKernel(
                kernelVarianceCopyRatio, kernelVarianceAlleleFraction, kernelScalingAlleleFraction);

        logger.info(String.format("Finding changepoints in (%d, %d) data points and %d chromosomes...",
                denoisedCopyRatios.getRecords().size(), allelicCounts.size(), multidimensionalPointsPerChromosome.size()));

        //loop over chromosomes, find changepoints, and create allele-fraction segments
        final List<MultidimensionalSegment> segments = new ArrayList<>();
        for (final String chromosome : multidimensionalPointsPerChromosome.keySet()) {
            final List<MultidimensionalPoint> multidimensionalPointsInChromosome = multidimensionalPointsPerChromosome.get(chromosome);
            final int numMultidimensionalPointsInChromosome = multidimensionalPointsInChromosome.size();
            logger.info(String.format("Finding changepoints in %d data points in chromosome %s...",
                    numMultidimensionalPointsInChromosome, chromosome));

            if (numMultidimensionalPointsInChromosome < MIN_NUM_POINTS_REQUIRED_PER_CHROMOSOME) {
                logger.warn(String.format("Number of points in chromosome %s (%d) is less than that required (%d), skipping segmentation...",
                        chromosome, numMultidimensionalPointsInChromosome, MIN_NUM_POINTS_REQUIRED_PER_CHROMOSOME));
                final int start = multidimensionalPointsInChromosome.get(0).getStart();
                final int end = multidimensionalPointsInChromosome.get(numMultidimensionalPointsInChromosome - 1).getEnd();
                segments.add(new MultidimensionalSegment(
                        new SimpleInterval(chromosome, start, end),
                        copyRatioMidpointOverlapDetector,
                        allelicCountOverlapDetector));
                continue;
            }

            final List<Integer> changepoints = new ArrayList<>(new KernelSegmenter<>(multidimensionalPointsInChromosome)
                .findChangepoints(maxNumChangepointsPerChromosome, kernel, kernelApproximationDimension,
                        windowSizes, numChangepointsPenaltyLinearFactor, numChangepointsPenaltyLogLinearFactor, KernelSegmenter.ChangepointSortOrder.INDEX));

            if (!changepoints.contains(numMultidimensionalPointsInChromosome)) {
                changepoints.add(numMultidimensionalPointsInChromosome - 1);
            }
            int previousChangepoint = -1;
            for (final int changepoint : changepoints) {
                final int start = intervalsPerChromosome.get(chromosome).get(previousChangepoint + 1).getStart();
                final int end = intervalsPerChromosome.get(chromosome).get(changepoint).getEnd();
                segments.add(new MultidimensionalSegment(
                        new SimpleInterval(chromosome, start, end),
                        copyRatioMidpointOverlapDetector,
                        allelicCountOverlapDetector));
                previousChangepoint = changepoint;
            }
        }
        logger.info(String.format("Found %d segments in %d chromosomes.", segments.size(), multidimensionalPointsPerChromosome.keySet().size()));
        return new MultidimensionalSegmentCollection(allelicCounts.getSampleMetadata(), segments);
    }

    private BiFunction<MultidimensionalPoint, MultidimensionalPoint, Double> constructKernel(final double kernelVarianceCopyRatio,
                                                                                             final double kernelVarianceAlleleFraction,
                                                                                             final double kernelScalingAlleleFraction) {
        final double standardDeviationCopyRatio = Math.sqrt(kernelVarianceCopyRatio);
        final double standardDeviationAlleleFraction = Math.sqrt(kernelVarianceAlleleFraction);
        return (p1, p2) ->
                KERNEL.apply(standardDeviationCopyRatio).apply(p1.log2CopyRatio, p2.log2CopyRatio) +
                        kernelScalingAlleleFraction * KERNEL.apply(standardDeviationAlleleFraction).apply(p1.alternateAlleleFraction, p2.alternateAlleleFraction);

    }
}
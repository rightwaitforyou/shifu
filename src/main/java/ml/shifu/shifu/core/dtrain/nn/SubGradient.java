/**
 * Copyright [2012-2014] PayPal Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ml.shifu.shifu.core.dtrain.nn;

import java.util.Arrays;
import java.util.concurrent.Callable;

import ml.shifu.shifu.core.dtrain.dataset.BasicFloatMLDataPair;
import ml.shifu.shifu.core.dtrain.dataset.BasicFloatNetwork;
import ml.shifu.shifu.core.dtrain.dataset.FloatFlatNetwork;
import ml.shifu.shifu.core.dtrain.dataset.FloatMLDataPair;
import ml.shifu.shifu.core.dtrain.dataset.FloatMLDataSet;

import org.encog.engine.network.activation.ActivationFunction;
import org.encog.mathutil.error.ErrorCalculation;
import org.encog.neural.error.ErrorFunction;
import org.encog.neural.flat.FlatNetwork;

/**
 * {@link SubGradient} is copied from Encog framework. The reason is that we original Gradient don't pop up
 * {@link #gradients} outside. While we need gradients accumulated into {@link NNMaster} to update NN weights.
 */
public class SubGradient implements Callable<double[]> {

    /**
     * The network to train.
     */
    private FloatFlatNetwork network;

    /**
     * The error calculation method.
     */
    private final ErrorCalculation errorCalculation = new ErrorCalculation();

    /**
     * The actual values from the neural network.
     */
    private double[] actual;

    /**
     * The deltas for each layer.
     */
    private double[] layerDelta;

    /**
     * The neuron counts, per layer.
     */
    private int[] layerCounts;

    /**
     * The feed counts, per layer.
     */
    private int[] layerFeedCounts;

    /**
     * The layer indexes.
     */
    private int[] layerIndex;

    /**
     * The index to each layer's weights and thresholds.
     */
    private int[] weightIndex;

    /**
     * The output from each layer.
     */
    private double[] layerOutput;

    /**
     * The sums.
     */
    private double[] layerSums;

    /**
     * The gradients.
     */
    private double[] gradients;

    /**
     * The weights and thresholds.
     */
    private double[] weights;

    /**
     * The pair to use for training.
     */
    private FloatMLDataPair pair;

    /**
     * The training data.
     */
    private final FloatMLDataSet training;

    /**
     * The testing data, test data set here is used for training and testing cross over.
     */
    private FloatMLDataSet testing;

    /**
     * Whether to replace training and testing elements.
     */
    private final boolean isCrossOver;

    /**
     * Seed used to sample training and testing data set to choose which element is used for training
     */
    private long seed = System.currentTimeMillis();

    /**
     * error
     */
    private double error;

    /**
     * Derivative add constant. Used to combat flat spot.
     */
    private double[] flatSpot;

    /**
     * The error function to use.
     */
    private final ErrorFunction errorFunction;

    private final long trainLow;

    private final long trainHigh;

    private final long testLow;

    private final long testHigh;

    private ParallelGradient owner;

    private double[] doubleIdeal;

    /**
     * Construct a gradient worker.
     * 
     * @param theNetwork
     *            The network to train.
     * @param theOwner
     *            The owner that is doing the training.
     * @param theTraining
     *            The training data.
     * @param theLow
     *            The low index to use in the training data.
     * @param theHigh
     *            The high index to use in the training data.
     */
    public SubGradient(final FloatFlatNetwork theNetwork, final FloatMLDataSet theTraining, long trainLow, long trainHigh,
            final FloatMLDataSet theTesting, long testLow, long testHigh, final double[] flatSpot, ErrorFunction ef,
            boolean isCrossOver, ParallelGradient owner) {
        this.network = theNetwork;
        this.training = theTraining;
        this.trainLow = trainLow;
        this.trainHigh = trainHigh;
        this.testing = theTesting;
        this.testLow = testLow;
        this.testHigh = testHigh;
        this.isCrossOver = isCrossOver;
        this.flatSpot = flatSpot;
        this.errorFunction = ef;
        this.owner = owner;

        this.initNetworkParams();
    }

    private void initNetworkParams() {
        this.layerDelta = new double[this.network.getLayerOutput().length];
        this.gradients = new double[this.network.getWeights().length];
        this.actual = new double[this.network.getOutputCount()];

        this.weights = this.network.getWeights();
        this.layerIndex = this.network.getLayerIndex();
        this.layerCounts = this.network.getLayerCounts();
        this.weightIndex = this.network.getWeightIndex();
        this.layerOutput = this.network.getLayerOutput();
        this.layerSums = this.network.getLayerSums();
        this.layerFeedCounts = this.network.getLayerFeedCounts();

        this.pair = BasicFloatMLDataPair.createPair(this.network.getInputCount(), getNetwork().getOutputCount());
    }

    /**
     * Process one training set element.
     * 
     * @param input
     *            The network input.
     * @param ideal
     *            The ideal values.
     * @param s
     *            The significance.
     */
    private void process(final float[] input, final float[] ideal, double s) {
        ((FloatFlatNetwork) this.getNetwork()).compute(input, this.actual);

        // have to copy float ideal array to double array, since ideal array is small, it's ok to copy an array
        if(doubleIdeal == null) {
            doubleIdeal = new double[ideal.length];
        }
        for(int i = 0; i < doubleIdeal.length; i++) {
            doubleIdeal[i] = ideal[i];
        }

        this.errorCalculation.updateError(this.actual, doubleIdeal, s);
        this.errorFunction.calculateError(doubleIdeal, actual, this.getLayerDelta());

        for(int i = 0; i < this.actual.length; i++) {
            this.getLayerDelta()[i] = ((this.getNetwork().getActivationFunctions()[0].derivativeFunction(
                    this.layerSums[i], this.layerOutput[i]) + this.flatSpot[0])) * (this.getLayerDelta()[i] * s);
        }

        for(int i = this.getNetwork().getBeginTraining(); i < this.getNetwork().getEndTraining(); i++) {
            processLevel(i);
        }
    }

    /**
     * Process one level.
     * 
     * @param currentLevel
     *            The level.
     */
    private void processLevel(final int currentLevel) {
        final int fromLayerIndex = this.layerIndex[currentLevel + 1];
        final int toLayerIndex = this.layerIndex[currentLevel];
        final int fromLayerSize = this.layerCounts[currentLevel + 1];
        final int toLayerSize = this.layerFeedCounts[currentLevel];

        final int index = this.weightIndex[currentLevel];
        final ActivationFunction activation = this.getNetwork().getActivationFunctions()[currentLevel + 1];
        final double currentFlatSpot = this.flatSpot[currentLevel + 1];

        // handle weights
        int yi = fromLayerIndex;
        for(int y = 0; y < fromLayerSize; y++) {
            final double output = this.layerOutput[yi];
            double sum = 0;
            int xi = toLayerIndex;
            int wi = index + y;
            for(int x = 0; x < toLayerSize; x++) {
                this.gradients[wi] += output * this.getLayerDelta()[xi];
                sum += this.weights[wi] * this.getLayerDelta()[xi];
                wi += fromLayerSize;
                xi++;
            }

            this.getLayerDelta()[yi] = sum
                    * (activation.derivativeFunction(this.layerSums[yi], this.layerOutput[yi]) + currentFlatSpot);
            yi++;
        }
    }

    /**
     * Perform the gradient calculation
     */
    public final double[] call() {
        try {
            // reset errors and gradients firstly
            this.errorCalculation.reset();
            Arrays.fill(this.gradients, 0.0);

            for(long i = this.trainLow; i <= this.trainHigh; i++) {
                synchronized(this.owner) {
                    if(this.isCrossOver) {
                        // 3:1 to select testing data set, tmp hard code, TODO fix hard code issue,extract such logic to
                        // a
                        // method
                        if((i + seed) % 4 < 3) {
                            this.training.getRecord(i, this.pair);
                        } else {
                            long testingSize = this.testing.getRecordCount();
                            // it's ok to take data from all testing set
                            if(i < testingSize) {
                                this.testing.getRecord(i, this.pair);
                            } else {
                                this.testing.getRecord(i % testingSize, this.pair);
                            }
                        }
                    } else {
                        this.training.getRecord(i, this.pair);
                    }
                }
                process(this.pair.getInputArray(), this.pair.getIdealArray(), pair.getSignificance());
            }
            this.error = this.errorCalculation.calculate();
        } catch (final Throwable ex) {
            throw new RuntimeException(ex);
        }
        return this.gradients;
    }

    /**
     * Calculate the error for this neural network. The error is calculated
     * using root-mean-square(RMS).
     * 
     * @param data
     *            The training set.
     * @return The error percentage.
     */
    public final double calculateError(ErrorCalculation ec) {
        final double[] actual = new double[this.getNetwork().getOutputCount()];
        final FloatMLDataPair pair = BasicFloatMLDataPair.createPair(testing.getInputSize(), testing.getIdealSize());

        for(long i = testLow; i <= testHigh; i++) {
            synchronized(this.owner) {
                if(this.isCrossOver) {
                    // 3:1 to select testing data set, tmp hard code, TODO fix hard code issue
                    if((i + seed) % 4 < 3) {
                        this.testing.getRecord(i, pair);
                    } else {
                        long trainingSize = this.training.getRecordCount();
                        // it's ok to take data from all training set
                        if(i < trainingSize) {
                            this.training.getRecord(i, pair);
                        } else {
                            this.training.getRecord(i % trainingSize, pair);
                        }
                    }
                } else {
                    this.testing.getRecord(i, pair);
                }
            }
            ((FloatFlatNetwork) this.getNetwork()).compute(pair.getInputArray(), actual);
            // copy float idea array to double for api compatiability
            if(doubleIdeal == null) {
                doubleIdeal = new double[pair.getIdealArray().length];
            }
            for(int j = 0; j < doubleIdeal.length; j++) {
                doubleIdeal[j] = pair.getIdealArray()[j];
            }

            synchronized(ec) {
                ec.updateError(actual, doubleIdeal, pair.getSignificance());
            }
        }
        return -1;
    }

    public ErrorCalculation getErrorCalculation() {
        return errorCalculation;
    }

    /**
     * @return the gradients
     */
    public double[] getGradients() {
        return this.gradients;
    }

    /**
     * @return the error
     */
    public double getError() {
        return error;
    }

    /**
     * @return the weights
     */
    public double[] getWeights() {
        return weights;
    }

    /**
     * @param weights
     *            the weights to set
     */
    public void setWeights(double[] weights) {
        this.weights = weights;
        this.getNetwork().setWeights(weights);
    }

    public void setParams(BasicFloatNetwork network) {
        this.setNetwork((FloatFlatNetwork)network.getFlat());
        this.weights = network.getFlat().getWeights();
    }

    public FlatNetwork getNetwork() {
        return network;
    }

    public double[] getLayerDelta() {
        return layerDelta;
    }

    /**
     * @return the seed
     */
    public long getSeed() {
        return seed;
    }

    /**
     * @param seed
     *            the seed to set
     */
    public void setSeed(long seed) {
        this.seed = seed;
    }

    /**
     * @param network
     *            the network to set
     */
    public void setNetwork(FloatFlatNetwork network) {
        this.network = network;
        this.weights = this.network.getWeights();
    }

}

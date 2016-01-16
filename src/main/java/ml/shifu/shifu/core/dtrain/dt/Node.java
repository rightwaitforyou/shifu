/*
 * Copyright [2013-2015] PayPal Software Foundation
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
package ml.shifu.shifu.core.dtrain.dt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import ml.shifu.guagua.io.Bytable;

/**
 * A node tree
 * 
 * @author Zhang David (pengzhang@paypal.com)
 */
public class Node implements Bytable {

    private int id;

    private Split split;

    private Node left;

    private Node right;

    private Predict predict;

    private double gain;

    private double impurity;

    private Predict leftPredict;

    private double leftImpurity;

    private Predict rightPredict;

    private double rightImpurity;

    public Node() {
    }

    public Node(int id) {
        this.id = id;
    }

    public Node(int id, Node left, Node right) {
        this.id = id;
        this.left = left;
        this.right = right;
    }

    public Node(int id, Split split, Node left, Node right, Predict predict, double gain, double impurity) {
        this.id = id;
        this.split = split;
        this.left = left;
        this.right = right;
        this.predict = predict;
        this.gain = gain;
        this.impurity = impurity;
    }

    public Node(int id, Split split, Node left, Node right, Predict predict, double gain, double impurity,
            Predict leftPredict, double leftImpurity, Predict rightPredict, double rightImpurity) {
        this.id = id;
        this.split = split;
        this.left = left;
        this.right = right;
        this.predict = predict;
        this.gain = gain;
        this.impurity = impurity;
        this.leftPredict = leftPredict;
        this.leftImpurity = leftImpurity;
        this.rightPredict = rightPredict;
        this.rightImpurity = rightImpurity;
    }

    public boolean isLeaf() {
        return left == null && right == null;
    }

    /**
     * @return the id
     */
    public int getId() {
        return id;
    }

    /**
     * @return the left
     */
    public Node getLeft() {
        return left;
    }

    /**
     * @return the right
     */
    public Node getRight() {
        return right;
    }

    /**
     * @return the predict
     */
    public Predict getPredict() {
        return predict;
    }

    /**
     * @return the gain
     */
    public double getGain() {
        return gain;
    }

    /**
     * @return the impurity
     */
    public double getImpurity() {
        return impurity;
    }

    /**
     * @return the leftPredict
     */
    public Predict getLeftPredict() {
        return leftPredict;
    }

    /**
     * @return the leftImpurity
     */
    public double getLeftImpurity() {
        return leftImpurity;
    }

    /**
     * @return the rightPredict
     */
    public Predict getRightPredict() {
        return rightPredict;
    }

    /**
     * @return the rightImpurity
     */
    public double getRightImpurity() {
        return rightImpurity;
    }

    /**
     * @param id
     *            the id to set
     */
    public void setId(int id) {
        this.id = id;
    }

    /**
     * @param left
     *            the left to set
     */
    public void setLeft(Node left) {
        this.left = left;
    }

    /**
     * @param right
     *            the right to set
     */
    public void setRight(Node right) {
        this.right = right;
    }

    /**
     * @param predict
     *            the predict to set
     */
    public void setPredict(Predict predict) {
        this.predict = predict;
    }

    /**
     * @param gain
     *            the gain to set
     */
    public void setGain(double gain) {
        this.gain = gain;
    }

    /**
     * @param impurity
     *            the impurity to set
     */
    public void setImpurity(double impurity) {
        this.impurity = impurity;
    }

    /**
     * @param leftPredict
     *            the leftPredict to set
     */
    public void setLeftPredict(Predict leftPredict) {
        this.leftPredict = leftPredict;
    }

    /**
     * @param leftImpurity
     *            the leftImpurity to set
     */
    public void setLeftImpurity(double leftImpurity) {
        this.leftImpurity = leftImpurity;
    }

    /**
     * @param rightPredict
     *            the rightPredict to set
     */
    public void setRightPredict(Predict rightPredict) {
        this.rightPredict = rightPredict;
    }

    /**
     * @param rightImpurity
     *            the rightImpurity to set
     */
    public void setRightImpurity(double rightImpurity) {
        this.rightImpurity = rightImpurity;
    }

    /**
     * @return the split
     */
    public Split getSplit() {
        return split;
    }

    /**
     * @param split
     *            the split to set
     */
    public void setSplit(Split split) {
        this.split = split;
    }

    public static int leftIndex(int id) {
        return id << 1;
    }

    public static int rightIndex(int id) {
        return id << 1 + 1;
    }

    public static int parentIndex(int id) {
        return id >>> 1;
    }

    @Override
    public void write(DataOutput out) throws IOException {
        out.writeInt(id);
        out.writeDouble(gain);
        out.writeDouble(impurity);
        out.writeDouble(leftImpurity);
        out.writeDouble(rightImpurity);

        if(split == null) {
            out.writeBoolean(false);
        } else {
            split.write(out);
            out.writeBoolean(true);
        }

        if(predict == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            predict.write(out);
        }

        if(leftPredict == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            leftPredict.write(out);
        }

        if(rightPredict == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            rightPredict.write(out);
        }

        if(left == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            left.write(out);
        }

        if(right == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            right.write(out);
        }
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        this.id = in.readInt();
        this.gain = in.readDouble();
        this.impurity = in.readDouble();
        this.leftImpurity = in.readDouble();
        this.rightImpurity = in.readDouble();

        if(in.readBoolean()) {
            this.split = new Split();
            this.split.readFields(in);
        }

        if(in.readBoolean()) {
            this.leftPredict = new Predict();
            this.leftPredict.readFields(in);
        }

        if(in.readBoolean()) {
            this.predict = new Predict();
            this.predict.readFields(in);
        }

        if(in.readBoolean()) {
            this.rightPredict = new Predict();
            this.rightPredict.readFields(in);
        }

        if(in.readBoolean()) {
            this.left = new Node();
            this.left.readFields(in);
        }

        if(in.readBoolean()) {
            this.right = new Node();
            this.right.readFields(in);
        }
    }

    @Override
    public String toString() {
        return "Node [id=" + id + ", split=" + split + ", left=" + left + ", right=" + right + ", predict=" + predict
                + ", gain=" + gain + ", impurity=" + impurity + ", leftPredict=" + leftPredict + ", leftImpurity="
                + leftImpurity + ", rightPredict=" + rightPredict + ", rightImpurity=" + rightImpurity + "]";
    }

}
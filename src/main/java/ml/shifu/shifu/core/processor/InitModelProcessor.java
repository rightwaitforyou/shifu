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
package ml.shifu.shifu.core.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import ml.shifu.guagua.mapreduce.GuaguaMapReduceConstants;
import ml.shifu.shifu.container.obj.ColumnConfig;
import ml.shifu.shifu.container.obj.ColumnConfig.ColumnType;
import ml.shifu.shifu.container.obj.RawSourceData.SourceType;
import ml.shifu.shifu.core.autotype.AutoTypeDistinctCountMapper;
import ml.shifu.shifu.core.autotype.AutoTypeDistinctCountReducer;
import ml.shifu.shifu.core.autotype.CountAndFrequentItemsWritable;
import ml.shifu.shifu.core.dtrain.nn.NNConstants;
import ml.shifu.shifu.core.mr.input.CombineInputFormat;
import ml.shifu.shifu.core.validator.ModelInspector.ModelStep;
import ml.shifu.shifu.fs.ShifuFileUtils;
import ml.shifu.shifu.util.CommonUtils;
import ml.shifu.shifu.util.Constants;
import ml.shifu.shifu.util.Environment;
import ml.shifu.shifu.util.HDPUtils;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.collections.Predicate;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.jexl2.JexlException;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.pig.impl.util.JarManager;
import org.encog.ml.data.MLDataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.clearspring.analytics.stream.cardinality.HyperLogLogPlus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;

/**
 * Initialize processor, the purpose of this processor is create columnConfig based on modelConfig instance
 */
public class InitModelProcessor extends BasicModelProcessor implements Processor {

    private static final String TAB_STR = "\t";
    /**
     * log object
     */
    private final static Logger log = LoggerFactory.getLogger(InitModelProcessor.class);

    /**
     * runner for init the model
     * 
     * @throws Exception
     */
    @Override
    public int run() throws Exception {
        log.info("Step Start: init");
        long start = System.currentTimeMillis();
        try {
            setUp(ModelStep.INIT);

            // initialize and save ColumnConfig list firstly to make sure in mr jobs we can load columnconfig.json
            int status = initColumnConfigList();

            if(status != 0) {
                return status;
            }

            saveColumnConfigListAndColumnStats(false);

            syncDataToHdfs(modelConfig.getDataSet().getSource());

            Map<Integer, Data> distinctCountMap = null;
            if(autoTypeEnableCondition()) {
                distinctCountMap = getApproxDistinctCountByMRJob();
            }

            if(autoTypeEnableCondition() && distinctCountMap != null) {
                if(modelConfig.getDataSet().getAutoTypeThreshold() <= 0) {
                    log.info("Auto type detection is on but threshold <= 0, only compute distinct count but not detect "
                            + "categorical columns.");
                    setCategoricalColumnsAndDistinctAccount(distinctCountMap, false, true);
                } else {
                    int cateCount = setCategoricalColumnsAndDistinctAccount(distinctCountMap, true, true);
                    log.info("Automatically check {} variables to categorical type.", cateCount);
                }
            }
            // save ColumnConfig list into file
            saveColumnConfigListAndColumnStats(false);

            syncDataToHdfs(modelConfig.getDataSet().getSource());

            clearUp(ModelStep.INIT);
        } catch (Exception e) {
            log.error("Error:", e);
            return -1;
        }
        log.info("Step Finished: init with {} ms", (System.currentTimeMillis() - start));
        return 0;
    }

    /**
     * @return
     */
    private boolean autoTypeEnableCondition() {
        return modelConfig.isMapReduceRunMode() && modelConfig.getDataSet().getAutoType();
    }

    private int setCategoricalColumnsAndDistinctAccount(Map<Integer, Data> distinctCountMap, boolean cateOn,
            boolean distinctOn) {
        int cateCount = 0;
        for(ColumnConfig columnConfig: columnConfigList) {
            Long distinctCount = distinctCountMap.get(columnConfig.getColumnNum()).count;
            if(distinctCount != null && modelConfig.getDataSet().getAutoTypeThreshold() != null) {
                if(cateOn) {
                    if(distinctCount < modelConfig.getDataSet().getAutoTypeThreshold().longValue()) {
                        String[] items = distinctCountMap.get(columnConfig.getColumnNum()).items;
                        if(is01Variable(distinctCount, items)) {
                            log.info(
                                    "Column {} with index {} is set to numeric type because of 0-1 variable. Distinct count {}, items {}.",
                                    columnConfig.getColumnName(), columnConfig.getColumnNum(), distinctCount,
                                    Arrays.toString(items));
                            columnConfig.setColumnType(ColumnType.N);
                        } else if(isDoubleFrequentVariable(distinctCount, items)) {
                            log.info(
                                    "Column {} with index {} is set to numeric type because of all sampled items are double(including blank). Distinct count {}, items {}.",
                                    columnConfig.getColumnName(), columnConfig.getColumnNum(), distinctCount,
                                    Arrays.toString(items));
                            columnConfig.setColumnType(ColumnType.N);
                        } else {
                            columnConfig.setColumnType(ColumnType.C);
                            cateCount += 1;
                            log.info(
                                    "Column {} with index {} is set to categorical type according to auto type checking: distinct count {}, threshold {}.",
                                    columnConfig.getColumnName(), columnConfig.getColumnNum(), distinctCount,
                                    modelConfig.getDataSet().getAutoTypeThreshold());
                        }
                    }
                }
                if(distinctOn) {
                    columnConfig.getColumnStats().setDistinctCount(distinctCount);
                }
            }
        }
        return cateCount;
    }

    private boolean is01Variable(long distinctCount, String[] items) {
        if(distinctCount != 2) {
            return false;
        }
        if(items.length > 2) {
            return false;
        }
        for(String string: items) {
            try {
                Double d = Double.valueOf(string);
                if(d.compareTo(Double.valueOf(0d)) == 0 || d.compareTo(Double.valueOf(1d)) == 0) {
                    continue;
                } else {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private boolean isDoubleFrequentVariable(long distinctCount, String[] items) {
        int doubleNotInfSize = 0;
        boolean isExistNotEmptyAndNotNumberItem = false;
        for(String string: items) {
            boolean isDouble = false;
            boolean isInt = false;
            try {
                Double.parseDouble(string);
                isDouble = true;
            } catch (NumberFormatException e) {
                isDouble = false;
                if(StringUtils.isNotBlank(string)) {
                    isExistNotEmptyAndNotNumberItem = true;
                }
            }
            try {
                Integer.parseInt(string);
                isInt = true;
            } catch (NumberFormatException e) {
                isInt = false;
            }
            if(isDouble && !isInt) {
                doubleNotInfSize += 1;
            }
        }

        if(doubleNotInfSize == items.length
                || (doubleNotInfSize == items.length - 1 && !isExistNotEmptyAndNotNumberItem)) {
            return true;
        }

        return false;
    }

    // GuaguaOptionsParser doesn't to support *.jar currently.
    private String addRuntimeJars() {
        List<String> jars = new ArrayList<String>(16);
        // common-codec
        jars.add(JarManager.findContainingJar(Base64.class));
        // commons-compress-*.jar
        jars.add(JarManager.findContainingJar(BZip2CompressorInputStream.class));
        // commons-lang-*.jar
        jars.add(JarManager.findContainingJar(StringUtils.class));
        // common-io-*.jar
        jars.add(JarManager.findContainingJar(org.apache.commons.io.IOUtils.class));
        // common-collections
        jars.add(JarManager.findContainingJar(Predicate.class));
        // guava-*.jar
        jars.add(JarManager.findContainingJar(Splitter.class));
        // shifu-*.jar
        jars.add(JarManager.findContainingJar(getClass()));
        // jexl-*.jar
        jars.add(JarManager.findContainingJar(JexlException.class));
        // encog-core-*.jar
        jars.add(JarManager.findContainingJar(MLDataSet.class));
        // jackson-databind-*.jar
        jars.add(JarManager.findContainingJar(ObjectMapper.class));
        // jackson-core-*.jar
        jars.add(JarManager.findContainingJar(JsonParser.class));
        // jackson-annotations-*.jar
        jars.add(JarManager.findContainingJar(JsonIgnore.class));
        // stream-llib-*.jar
        jars.add(JarManager.findContainingJar(HyperLogLogPlus.class));

        return StringUtils.join(jars, NNConstants.LIB_JAR_SEPARATOR);
    }

    private Map<Integer, Data> getApproxDistinctCountByMRJob() throws IOException, InterruptedException,
            ClassNotFoundException {
        SourceType source = this.modelConfig.getDataSet().getSource();
        Configuration conf = new Configuration();

        // add jars to hadoop mapper and reducer
        new GenericOptionsParser(conf, new String[] { "-libjars", addRuntimeJars() });

        conf.setBoolean(GuaguaMapReduceConstants.MAPRED_MAP_TASKS_SPECULATIVE_EXECUTION, true);
        conf.setBoolean(GuaguaMapReduceConstants.MAPRED_REDUCE_TASKS_SPECULATIVE_EXECUTION, true);
        conf.set(NNConstants.MAPRED_JOB_QUEUE_NAME, Environment.getProperty(Environment.HADOOP_JOB_QUEUE, "default"));
        conf.setInt(GuaguaMapReduceConstants.MAPREDUCE_JOB_MAX_SPLIT_LOCATIONS, 100);
        conf.set(
                Constants.SHIFU_MODEL_CONFIG,
                ShifuFileUtils.getFileSystemBySourceType(source)
                        .makeQualified(new Path(super.getPathFinder().getModelConfigPath(source))).toString());
        conf.set(
                Constants.SHIFU_COLUMN_CONFIG,
                ShifuFileUtils.getFileSystemBySourceType(source)
                        .makeQualified(new Path(super.getPathFinder().getColumnConfigPath(source))).toString());
        conf.set(Constants.SHIFU_MODELSET_SOURCE_TYPE, source.toString());

        conf.set("mapred.reduce.slowstart.completed.maps",
                Environment.getProperty("mapred.reduce.slowstart.completed.maps", "0.9"));
        String hdpVersion = HDPUtils.getHdpVersionForHDP224();
        if(StringUtils.isNotBlank(hdpVersion)) {
            // for hdp 2.2.4, hdp.version should be set and configuration files should be add to container class path
            conf.set("hdp.version", hdpVersion);
            HDPUtils.addFileToClassPath(HDPUtils.findContainingFile("hdfs-site.xml"), conf);
            HDPUtils.addFileToClassPath(HDPUtils.findContainingFile("core-site.xml"), conf);
            HDPUtils.addFileToClassPath(HDPUtils.findContainingFile("mapred-site.xml"), conf);
            HDPUtils.addFileToClassPath(HDPUtils.findContainingFile("yarn-site.xml"), conf);
        }

        conf.setBoolean(CombineInputFormat.SHIFU_VS_SPLIT_COMBINABLE, true);

        @SuppressWarnings("deprecation")
        Job job = new Job(conf, "Shifu: Column Type Auto Checking Job : " + this.modelConfig.getModelSetName());
        job.setJarByClass(getClass());
        job.setMapperClass(AutoTypeDistinctCountMapper.class);

        job.setMapOutputKeyClass(IntWritable.class);
        job.setMapOutputValueClass(CountAndFrequentItemsWritable.class);

        job.setInputFormatClass(CombineInputFormat.class);
        FileInputFormat.setInputPaths(
                job,
                ShifuFileUtils.getFileSystemBySourceType(source).makeQualified(
                        new Path(super.modelConfig.getDataSetRawPath())));

        job.setReducerClass(AutoTypeDistinctCountReducer.class);
        job.setNumReduceTasks(1);
        job.setOutputKeyClass(IntWritable.class);
        job.setOutputValueClass(Text.class);
        job.setOutputFormatClass(TextOutputFormat.class);

        String autoTypePath = super.getPathFinder().getAutoTypeFilePath(source);
        FileOutputFormat.setOutputPath(job, new Path(autoTypePath));

        // clean output firstly
        ShifuFileUtils.deleteFile(autoTypePath, source);

        // submit job
        if(job.waitForCompletion(true)) {
            return getDistinctCountMap(source, autoTypePath);
        } else {
            throw new RuntimeException("MapReduce Job Auto Type Distinct Count failed.");
        }
    }

    private Map<Integer, Data> getDistinctCountMap(SourceType source, String autoTypePath) throws IOException {
        String outputFilePattern = autoTypePath + Path.SEPARATOR + "part-*";
        if(!ShifuFileUtils.isFileExists(outputFilePattern, source)) {
            throw new RuntimeException("Auto type checking output file not exist.");
        }

        Map<Integer, Data> distinctCountMap = new HashMap<Integer, Data>();
        List<Scanner> scanners = null;
        try {
            // here only works for 1 reducer
            FileStatus[] globStatus = ShifuFileUtils.getFileSystemBySourceType(source).globStatus(
                    new Path(outputFilePattern));
            if(globStatus == null || globStatus.length == 0) {
                throw new RuntimeException("Auto type checking output file not exist.");
            }
            scanners = ShifuFileUtils.getDataScanners(globStatus[0].getPath().toString(), source);
            Scanner scanner = scanners.get(0);
            String str = null;
            while(scanner.hasNext()) {
                str = scanner.nextLine().trim();
                if(str.contains(TAB_STR)) {
                    String[] splits1 = str.split(TAB_STR);
                    String[] splits2 = splits1[1].split(":");

                    distinctCountMap.put(Integer.valueOf(splits1[0]),
                            new Data(Long.valueOf(splits2[0]), splits2[1].split(",")));
                }
            }
            return distinctCountMap;
        } finally {
            if(scanners != null) {
                for(Scanner scanner: scanners) {
                    if(scanner != null) {
                        scanner.close();
                    }
                }
            }
        }
    }

    /**
     * initialize the columnConfig file
     * 
     * @throws IOException
     */
    private int initColumnConfigList() throws IOException {
        String[] fields = null;
        boolean isSchemaProvided = true;
        if(StringUtils.isNotBlank(modelConfig.getHeaderPath())) {
            fields = CommonUtils.getHeaders(modelConfig.getHeaderPath(), modelConfig.getHeaderDelimiter(), modelConfig
                    .getDataSet().getSource());
        } else {
            log.warn("No header path is provided, we will try to read first line and detect schema.");
            log.warn("Schema in ColumnConfig.json are named as  index 0, 1, 2, 3 ...");
            log.warn("Please make sure weight column and tag column are also taking index as name.");
            fields = CommonUtils.takeFirstLine(modelConfig.getDataSetRawPath(), modelConfig.getHeaderDelimiter(),
                    modelConfig.getDataSet().getSource());
            isSchemaProvided = false;
        }

        columnConfigList = new ArrayList<ColumnConfig>();
        for(int i = 0; i < fields.length; i++) {
            ColumnConfig config = new ColumnConfig();
            config.setColumnNum(i);
            if(isSchemaProvided) {
                config.setColumnName(fields[i]);
            } else {
                config.setColumnName(i + "");
            }
            columnConfigList.add(config);
        }

        CommonUtils.updateColumnConfigFlags(modelConfig, columnConfigList);

        boolean hasTarget = false;
        for(ColumnConfig config: columnConfigList) {
            if(config.isTarget()) {
                hasTarget = true;
            }
        }

        if(!hasTarget) {
            log.error("Target is not valid: " + modelConfig.getTargetColumnName());
            log.error("Please check your header file {} and your header delimiter {}", modelConfig.getHeaderPath(),
                    modelConfig.getHeaderDelimiter());
            return 1;
        }

        return 0;
    }

    static class Data {
        public Data(long count, String[] items) {
            this.count = count;
            this.items = items;
        }

        private final long count;

        private final String[] items;
    }

}

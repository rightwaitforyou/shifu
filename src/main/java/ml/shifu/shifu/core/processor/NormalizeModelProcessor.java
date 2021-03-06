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

import ml.shifu.shifu.actor.AkkaSystemExecutor;
import ml.shifu.shifu.container.obj.RawSourceData.SourceType;
import ml.shifu.shifu.core.validator.ModelInspector.ModelStep;
import ml.shifu.shifu.exception.ShifuErrorCode;
import ml.shifu.shifu.exception.ShifuException;
import ml.shifu.shifu.fs.ShifuFileUtils;
import ml.shifu.shifu.pig.PigExecutor;
import ml.shifu.shifu.util.CommonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Normalize processor, scaling data
 */
public class NormalizeModelProcessor extends BasicModelProcessor implements Processor {

    private final static Logger log = LoggerFactory.getLogger(NormalizeModelProcessor.class);

    /**
     * runner for normalization data
     */
    @Override
    public int run() throws Exception {
        log.info("Step Start: normalize");
        long start = System.currentTimeMillis();
        try {
            setUp(ModelStep.NORMALIZE);
            syncDataToHdfs(modelConfig.getDataSet().getSource());

            switch(modelConfig.getBasic().getRunMode()) {
                case DIST:
                case MAPRED:
                    runPigNormalize();
                    break;
                case LOCAL:
                    runAkkaNormalize();
                    break;
            }

            syncDataToHdfs(modelConfig.getDataSet().getSource());
            clearUp(ModelStep.NORMALIZE);
        } catch (Exception e) {
            log.error("Error:", e);
            return -1;
        }
        log.info("Step Finished: normalize with {} ms", (System.currentTimeMillis() - start));
        return 0;
    }

    /**
     * running akka normalize process
     * 
     * @throws IOException
     */
    private void runAkkaNormalize() throws IOException {
        SourceType sourceType = modelConfig.getDataSet().getSource();

        ShifuFileUtils.deleteFile(pathFinder.getNormalizedDataPath(), sourceType);
        ShifuFileUtils.deleteFile(pathFinder.getSelectedRawDataPath(), sourceType);

        List<Scanner> scanners = null;
        try {
            scanners = ShifuFileUtils.getDataScanners(
                    ShifuFileUtils.expandPath(modelConfig.getDataSetRawPath(), sourceType), sourceType);
        } catch (IOException e) {
            throw new ShifuException(ShifuErrorCode.ERROR_INPUT_NOT_FOUND, e, ", could not get input files "
                    + modelConfig.getDataSetRawPath());
        }

        if(scanners == null || scanners.size() == 0) {
            throw new ShifuException(ShifuErrorCode.ERROR_INPUT_NOT_FOUND, ", please check the data in "
                    + modelConfig.getDataSetRawPath() + " in " + sourceType);
        }

        AkkaSystemExecutor.getExecutor().submitNormalizeJob(modelConfig, columnConfigList, scanners);

        // release
        closeScanners(scanners);
    }

    /**
     * running pig normalize process
     * 
     * @throws IOException
     */
    private void runPigNormalize() throws IOException {
        SourceType sourceType = modelConfig.getDataSet().getSource();

        ShifuFileUtils.deleteFile(pathFinder.getNormalizedDataPath(), sourceType);
        ShifuFileUtils.deleteFile(pathFinder.getSelectedRawDataPath(), sourceType);

        Map<String, String> paramsMap = new HashMap<String, String>();
        paramsMap.put("sampleRate", modelConfig.getNormalizeSampleRate().toString());
        paramsMap.put("sampleNegOnly", ((Boolean) modelConfig.isNormalizeSampleNegOnly()).toString());
        paramsMap.put("delimiter", CommonUtils.escapePigString(modelConfig.getDataSetDelimiter()));

        try {
            String normPigPath = null;
            if(modelConfig.getNormalize().getIsParquet()) {
                if(modelConfig.getBasic().getPostTrainOn()) {
                    normPigPath = pathFinder.getAbsolutePath("scripts/NormalizeWithParquetAndPostTrain.pig");
                } else {
                    log.info("Post train is disabled by 'postTrainOn=false'.");
                    normPigPath = pathFinder.getAbsolutePath("scripts/NormalizeWithParquet.pig");
                }
            } else {
                if(modelConfig.getBasic().getPostTrainOn()) {
                    normPigPath = pathFinder.getAbsolutePath("scripts/NormalizeWithPostTrain.pig");
                } else {
                    normPigPath = pathFinder.getAbsolutePath("scripts/Normalize.pig");
                }
            }
            PigExecutor.getExecutor().submitJob(modelConfig, normPigPath, paramsMap);
        } catch (IOException e) {
            throw new ShifuException(ShifuErrorCode.ERROR_RUNNING_PIG_JOB, e);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

}

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
package ml.shifu.shifu.message;

import java.util.List;

/**
 * RunModelDataMessage class is the message class that contains the evaluation data
 */
public class RunModelDataMessage {

    private int streamId;
    private int totalStreamCnt;
    private int msgId;
    private boolean isLastMsg;
    private List<String> evalDataList;

    public RunModelDataMessage(int streamId, int totalStreamCnt, int msgId, boolean isLastMsg, List<String> evalDataList) {
        this.streamId = streamId;
        this.totalStreamCnt = totalStreamCnt;
        this.msgId = msgId;
        this.isLastMsg = isLastMsg;
        this.evalDataList = evalDataList;
    }

    public int getStreamId() {
        return streamId;
    }

    public int getTotalStreamCnt() {
        return totalStreamCnt;
    }

    public int getMsgId() {
        return msgId;
    }

    public boolean isLastMsg() {
        return isLastMsg;
    }

    public List<String> getEvalDataList() {
        return evalDataList;
    }

}

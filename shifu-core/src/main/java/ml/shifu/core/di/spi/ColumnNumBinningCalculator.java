/**
 * Copyright [2012-2014] eBay Software Foundation
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

package ml.shifu.core.di.spi;

import ml.shifu.core.container.ColumnBinningResult;
import ml.shifu.core.container.NumericalValueObject;

import java.util.List;

public interface ColumnNumBinningCalculator {

    /**
     * voList is unsorted
     * voList is filtered, only valid data(tag in either posTags or negTags)
     *
     * @param nvoList
     * @param maxNumBins
     * @return
     */

    public ColumnBinningResult calculate(List<NumericalValueObject> nvoList, int maxNumBins);

}

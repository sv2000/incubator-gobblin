/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gobblin.data.management.source;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.PeekingIterator;
import com.typesafe.config.Config;

import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.configuration.SourceState;
import org.apache.gobblin.configuration.WorkUnitState;
import org.apache.gobblin.dataset.Dataset;
import org.apache.gobblin.dataset.IterableDatasetFinder;
import org.apache.gobblin.dataset.PartitionableDataset;
import org.apache.gobblin.dataset.URNIdentified;
import org.apache.gobblin.dataset.comparators.URNLexicographicalComparator;
import org.apache.gobblin.runtime.task.NoopTask;
import org.apache.gobblin.source.workunit.BasicWorkUnitStream;
import org.apache.gobblin.source.workunit.WorkUnit;
import org.apache.gobblin.source.workunit.WorkUnitStream;
import org.apache.gobblin.util.ConfigUtils;

import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * A source that processes datasets generated by a {@link org.apache.gobblin.dataset.DatasetsFinder}, processing a few of
 * them each run, and continuing from where it left off in the next run. When it is done processing all the datasets, it
 * starts over from the beginning. The datasets are processed in lexicographical order based on URN.
 *
 * TODO: handle retries
 */
@Slf4j
public abstract class LoopingDatasetFinderSource<S, D> extends DatasetFinderSource<S, D> {
  public static final String MAX_WORK_UNITS_PER_RUN_KEY =
      "gobblin.source.loopingDatasetFinderSource.maxWorkUnitsPerRun";
  public static final int MAX_WORK_UNITS_PER_RUN = 10;
  public static final String DATASET_PARTITION_DELIMITER = "@";

  protected static final String DATASET_URN = "gobblin.source.loopingDatasetFinderSource.datasetUrn";
  protected static final String PARTITION_URN = "gobblin.source.loopingDatasetFinderSource.partitionUrn";
  protected static final String END_OF_DATASETS_KEY = "gobblin.source.loopingDatasetFinderSource.endOfDatasets";
  protected static final String GLOBAL_WATERMARK_DATASET_KEY =
      "gobblin.source.loopingDatasetFinderSource.globalWatermarkDataset";

  private final URNLexicographicalComparator lexicographicalComparator = new URNLexicographicalComparator();
  protected boolean isDatasetStateStoreEnabled;

  /**
   * @param drilldownIntoPartitions if set to true, will process each partition of a {@link PartitionableDataset} as a
   *                                separate work unit.
   */
  public LoopingDatasetFinderSource(boolean drilldownIntoPartitions) {
    super(drilldownIntoPartitions);
  }

  @Override
  public List<WorkUnit> getWorkunits(SourceState state) {
    return Lists.newArrayList(getWorkunitStream(state).getMaterializedWorkUnitCollection());
  }

  @Override
  public WorkUnitStream getWorkunitStream(SourceState state) {
    return this.getWorkunitStream(state,false);
  }

  public WorkUnitStream getWorkunitStream(SourceState state, boolean isDatasetStateStoreEnabled) {
    this.isDatasetStateStoreEnabled = isDatasetStateStoreEnabled;
    try {
      int maxWorkUnits = state.getPropAsInt(MAX_WORK_UNITS_PER_RUN_KEY, MAX_WORK_UNITS_PER_RUN);
      Preconditions.checkArgument(maxWorkUnits > 0, "Max work units must be greater than 0!");
      Config config = ConfigUtils.propertiesToConfig(state.getProperties());

      List<WorkUnitState> previousWorkUnitStates = (this.isDatasetStateStoreEnabled) ? state
          .getPreviousWorkUnitStates(ConfigurationKeys.GLOBAL_WATERMARK_DATASET_URN)
          : state.getPreviousWorkUnitStates();

      Optional<WorkUnitState> maxWorkUnit = Optional.empty();
      for (WorkUnitState workUnitState : previousWorkUnitStates) {
        if (workUnitState.getPropAsBoolean(GLOBAL_WATERMARK_DATASET_KEY, false)) {
          maxWorkUnit = Optional.of(workUnitState);
          break;
        }
      }

      String previousDatasetUrnWatermark = null;
      String previousPartitionUrnWatermark = null;
      if (maxWorkUnit.isPresent() && !maxWorkUnit.get().getPropAsBoolean(END_OF_DATASETS_KEY, false)) {
        previousDatasetUrnWatermark = maxWorkUnit.get().getProp(DATASET_URN);
        previousPartitionUrnWatermark = maxWorkUnit.get().getProp(PARTITION_URN);
      }

      IterableDatasetFinder datasetsFinder = createDatasetsFinder(state);

      Stream<Dataset> datasetStream =
          datasetsFinder.getDatasetsStream(Spliterator.SORTED, this.lexicographicalComparator);
      datasetStream = sortStreamLexicographically(datasetStream);

      return new BasicWorkUnitStream.Builder(
          new DeepIterator(datasetStream.iterator(), previousDatasetUrnWatermark, previousPartitionUrnWatermark,
              maxWorkUnits, config)).setFiniteStream(true).build();
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  /**
   * A deep iterator that advances input streams until the correct position, then possibly iterates over partitions
   * of {@link PartitionableDataset}s.
   */
  private class DeepIterator extends AbstractIterator<WorkUnit> {
    private final Iterator<Dataset> baseIterator;
    private final int maxWorkUnits;

    private Iterator<PartitionableDataset.DatasetPartition> currentPartitionIterator;
    private int generatedWorkUnits = 0;
    private Dataset previousDataset;
    private PartitionableDataset.DatasetPartition previousPartition;

    public DeepIterator(Iterator<Dataset> baseIterator, String previousDatasetUrnWatermark,
        String previousPartitionUrnWatermark, int maxWorkUnits, Config config)
        throws IOException {
      this.maxWorkUnits = maxWorkUnits;
      this.baseIterator = baseIterator;
      Dataset equalDataset =
          advanceUntilLargerThan(Iterators.peekingIterator(this.baseIterator), previousDatasetUrnWatermark);

      if (drilldownIntoPartitions && equalDataset != null && equalDataset instanceof PartitionableDataset) {
        this.currentPartitionIterator = getPartitionIterator((PartitionableDataset) equalDataset);
        advanceUntilLargerThan(Iterators.peekingIterator(this.currentPartitionIterator), previousPartitionUrnWatermark);
      } else {
        this.currentPartitionIterator = Iterators.emptyIterator();
      }
    }

    /**
     * Advance an iterator until the next value is larger than the reference.
     * @return the last value polled if it is equal to reference, or null otherwise.
     */
    @Nullable
    private <T extends URNIdentified> T advanceUntilLargerThan(PeekingIterator<T> it, String reference) {
      if (reference == null) {
        return null;
      }

      int comparisonResult = -1;
      while (it.hasNext() && (comparisonResult = lexicographicalComparator.compare(it.peek(), reference)) < 0) {
        it.next();
      }
      return comparisonResult == 0 ? it.next() : null;
    }

    private Iterator<PartitionableDataset.DatasetPartition> getPartitionIterator(PartitionableDataset dataset) {
      try {
        return this.currentPartitionIterator = sortStreamLexicographically(
            dataset.getPartitions(Spliterator.SORTED, LoopingDatasetFinderSource.this.lexicographicalComparator))
            .iterator();
      } catch (IOException ioe) {
        log.error("Failed to get partitions for dataset " + dataset.getUrn());
        return Iterators.emptyIterator();
      }
    }

    @Override
    protected WorkUnit computeNext() {
      if (this.generatedWorkUnits == this.maxWorkUnits) {
        /**
         * Add a special noop workunit to the end of the stream. This workunit contains the Dataset/Partition
         * URN of the "last" dataset/partition (in lexicographic order). This is useful to
         * efficiently determine the next dataset/partition to process in the subsequent run.
         */
        this.generatedWorkUnits++;
        WorkUnit noopWorkUnit = generateNoopWorkUnit();
        /**
         * Check if we are at the end of datasets. If so, add the END_OF_DATASETS marker to the workunit.
         */
        if(!this.baseIterator.hasNext() && this.currentPartitionIterator != null && !this.currentPartitionIterator.hasNext()) {
          noopWorkUnit.setProp(END_OF_DATASETS_KEY,true);
        }
        return noopWorkUnit;
      } else if (this.generatedWorkUnits > this.maxWorkUnits) {
        return endOfData();
      }

      while (this.baseIterator.hasNext() || this.currentPartitionIterator.hasNext()) {
        if (this.currentPartitionIterator != null && this.currentPartitionIterator.hasNext()) {
          PartitionableDataset.DatasetPartition partition = this.currentPartitionIterator.next();
          WorkUnit workUnit = workUnitForDatasetPartition(partition);
          if (workUnit == null) {
            continue;
          }
          addDatasetInfoToWorkUnit(workUnit, partition.getDataset());
          addPartitionInfoToWorkUnit(workUnit, partition);
          this.previousDataset = partition.getDataset();
          this.previousPartition = partition;
          this.generatedWorkUnits++;
          return workUnit;
        }

        Dataset dataset = this.baseIterator.next();
        if (drilldownIntoPartitions && dataset instanceof PartitionableDataset) {
          this.currentPartitionIterator = getPartitionIterator((PartitionableDataset) dataset);
        } else {
          WorkUnit workUnit = workUnitForDataset(dataset);
          if (workUnit == null) {
            continue;
          }
          addDatasetInfoToWorkUnit(workUnit, dataset);
          this.previousDataset = dataset;
          this.generatedWorkUnits++;
          return workUnit;
        }
      }
      WorkUnit workUnit = generateNoopWorkUnit();
      this.generatedWorkUnits = Integer.MAX_VALUE;
      workUnit.setProp(END_OF_DATASETS_KEY, true);
      return workUnit;
    }

    private void addDatasetInfoToWorkUnit(WorkUnit workUnit, Dataset dataset) {
      if (isDatasetStateStoreEnabled) {
        workUnit.setProp(ConfigurationKeys.DATASET_URN_KEY, dataset.getUrn());
      }
    }

    private void addPartitionInfoToWorkUnit(WorkUnit workUnit, PartitionableDataset.DatasetPartition partition) {
      if (isDatasetStateStoreEnabled) {
        workUnit.setProp(ConfigurationKeys.DATASET_URN_KEY,
            Joiner.on(DATASET_PARTITION_DELIMITER).join(partition.getDataset().getUrn(), partition.getUrn()));
      }
    }

    private WorkUnit generateNoopWorkUnit() {
      WorkUnit workUnit = NoopTask.noopWorkunit();
      workUnit.setProp(GLOBAL_WATERMARK_DATASET_KEY, true);
      if (previousDataset != null) {
        workUnit.setProp(DATASET_URN, previousDataset.getUrn());
      }
      if (drilldownIntoPartitions && this.previousPartition != null) {
        workUnit.setProp(PARTITION_URN, previousPartition.getUrn());
      }
      if (isDatasetStateStoreEnabled) {
        workUnit.setProp(ConfigurationKeys.DATASET_URN_KEY, ConfigurationKeys.GLOBAL_WATERMARK_DATASET_URN);
      }
      return workUnit;
    }
  }

  /**
   * Sort input stream lexicographically. Noop if the input stream is already sorted.
   */
  private <T extends URNIdentified> Stream<T> sortStreamLexicographically(Stream<T> inputStream) {
    Spliterator<T> spliterator = inputStream.spliterator();
    if (spliterator.hasCharacteristics(Spliterator.SORTED) && spliterator.getComparator()
        .equals(this.lexicographicalComparator)) {
      return StreamSupport.stream(spliterator, false);
    }
    return StreamSupport.stream(spliterator, false).sorted(this.lexicographicalComparator);
  }
}

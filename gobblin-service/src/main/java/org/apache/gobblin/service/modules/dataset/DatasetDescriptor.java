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

package org.apache.gobblin.service.modules.dataset;

import org.apache.gobblin.annotation.Alpha;


/**
 * The interface for dataset descriptors.
 */
@Alpha
public interface DatasetDescriptor {
  /**
   * @return the dataset platform i.e. the storage backing the dataset (e.g. HDFS, JDBC, Espresso etc.)
   */
  public String getPlatform();

  /**
   * @return the type of dataset descriptor.
   */
  public String getType();

  /**
   * @return a human-readable description of the dataset.
   */
  public String getDescription();

  /**
   * @return the dataset urn.
   */
  public String getDatasetUrn();

  /**
   * @return true if this {@link DatasetDescriptor} is compatible with the other {@link DatasetDescriptor}.
   */
  public boolean isCompatibleWith(DatasetDescriptor other);
}

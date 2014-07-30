/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.io.merge;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;

public class MergeMapper extends MapReduceBase {
  protected JobConf jc;
  protected Path finalPath;
  protected FileSystem fs;
  protected boolean exception = false;
  protected boolean autoDelete = false;
  protected Path outPath;
  protected boolean hasDynamicPartitions = false;
  protected boolean isListBucketingDML = false;
  protected boolean isListBucketingAlterTableConcatenate = false;
  //used as depth for dir-calculation and if it is list bucketing case.
  protected int listBucketingDepth;
  protected boolean tmpPathFixedConcatenate = false;
  protected boolean tmpPathFixed = false;
  protected Path tmpPath;
  protected Path taskTmpPath;
  protected Path dpPath;

  public final static Log LOG = LogFactory.getLog("MergeMapper");

  @Override
  public void configure(JobConf job) {
    jc = job;
    hasDynamicPartitions = HiveConf.getBoolVar(job,
        HiveConf.ConfVars.HIVEMERGECURRENTJOBHASDYNAMICPARTITIONS);
    isListBucketingAlterTableConcatenate = HiveConf.getBoolVar(job,
        HiveConf.ConfVars.HIVEMERGECURRENTJOBCONCATENATELISTBUCKETING);
    listBucketingDepth = HiveConf.getIntVar(job,
        HiveConf.ConfVars.HIVEMERGECURRENTJOBCONCATENATELISTBUCKETINGDEPTH);

    Path specPath = MergeOutputFormat.getMergeOutputPath(job);
    Path tmpPath = Utilities.toTempPath(specPath);
    Path taskTmpPath = Utilities.toTaskTempPath(specPath);
    updatePaths(tmpPath, taskTmpPath);
    try {
      fs = specPath.getFileSystem(job);
      autoDelete = fs.deleteOnExit(outPath);
    } catch (IOException e) {
      this.exception = true;
      throw new RuntimeException(e);
    }
  }

  private void updatePaths(Path tmpPath, Path taskTmpPath) {
    String taskId = Utilities.getTaskId(jc);
    this.tmpPath = tmpPath;
    this.taskTmpPath = taskTmpPath;
    finalPath = new Path(tmpPath, taskId);
    outPath = new Path(taskTmpPath, Utilities.toTempPath(taskId));
  }


  /**
   * Validates that each input path belongs to the same partition since each mapper merges the input
   * to a single output directory
   * @param inputPath
   * @throws HiveException
   */
  protected void checkPartitionsMatch(Path inputPath) throws HiveException {
    if (!dpPath.equals(inputPath)) {
      // Temp partition input path does not match exist temp path
      String msg = "Multiple partitions for one block merge mapper: " + dpPath + " NOT EQUAL TO "
          + inputPath;
      LOG.error(msg);
      throw new HiveException(msg);
    }
  }

  /**
   * Fixes tmpPath to point to the correct partition. Before this is called, tmpPath will default to
   * the root tmp table dir fixTmpPath(..) works for DP + LB + multiple skewed values + merge.
   * reason: 1. fixTmpPath(..) compares inputPath and tmpDepth, find out path difference and put it
   * into newPath. Then add newpath to existing this.tmpPath and this.taskTmpPath. 2. The path
   * difference between inputPath and tmpDepth can be DP or DP+LB. It will automatically handle it.
   * 3. For example, if inputpath is <prefix>/-ext-10002/hr=a1/HIVE_DEFAULT_LIST_BUCKETING_DIR_NAME/
   * HIVE_DEFAULT_LIST_BUCKETING_DIR_NAME tmppath is <prefix>/_tmp.-ext-10000 newpath will be
   * hr=a1/HIVE_DEFAULT_LIST_BUCKETING_DIR_NAME/HIVE_DEFAULT_LIST_BUCKETING_DIR_NAME Then,
   * this.tmpPath and this.taskTmpPath will be update correctly. We have list_bucket_dml_6.q cover
   * this case: DP + LP + multiple skewed values + merge.
   * @param inputPath
   * @throws HiveException
   * @throws IOException
   */
  protected void fixTmpPath(Path inputPath) throws HiveException, IOException {
    dpPath = inputPath;
    Path newPath = new Path(".");
    int inputDepth = inputPath.depth();
    int tmpDepth = tmpPath.depth();

    // Build the path from bottom up
    while (inputPath != null && inputDepth > tmpDepth) {
      newPath = new Path(inputPath.getName(), newPath);
      inputDepth--;
      inputPath = inputPath.getParent();
    }

    Path newTmpPath = new Path(tmpPath, newPath);
    Path newTaskTmpPath = new Path(taskTmpPath, newPath);
    if (!fs.exists(newTmpPath)) {
      fs.mkdirs(newTmpPath);
    }
    updatePaths(newTmpPath, newTaskTmpPath);
  }

  /**
   * Fixes tmpPath to point to the correct list bucketing sub-directories. Before this is called,
   * tmpPath will default to the root tmp table dir Reason to add a new method instead of changing
   * fixTmpPath() Reason 1: logic has slightly difference fixTmpPath(..) needs 2 variables in order
   * to decide path delta which is in variable newPath. 1. inputPath.depth() 2. tmpPath.depth()
   * fixTmpPathConcatenate needs 2 variables too but one of them is different from fixTmpPath(..) 1.
   * inputPath.depth() 2. listBucketingDepth Reason 2: less risks The existing logic is a little not
   * trivial around map() and fixTmpPath(). In order to ensure minimum impact on existing flow, we
   * try to avoid change on existing code/flow but add new code for new feature.
   * @param inputPath
   * @throws HiveException
   * @throws IOException
   */
  protected void fixTmpPathConcatenate(Path inputPath) throws HiveException, IOException {
    dpPath = inputPath;
    Path newPath = new Path(".");

    int depth = listBucketingDepth;
    // Build the path from bottom up. pick up list bucketing subdirectories
    while ((inputPath != null) && (depth > 0)) {
      newPath = new Path(inputPath.getName(), newPath);
      inputPath = inputPath.getParent();
      depth--;
    }

    Path newTmpPath = new Path(tmpPath, newPath);
    Path newTaskTmpPath = new Path(taskTmpPath, newPath);
    if (!fs.exists(newTmpPath)) {
      fs.mkdirs(newTmpPath);
    }
    updatePaths(newTmpPath, newTaskTmpPath);
  }

  @Override
  public void close() throws IOException {
    if (!exception) {
      FileStatus fss = fs.getFileStatus(outPath);
      LOG.info("renamed path " + outPath + " to " + finalPath + " . File size is " + fss.getLen());
      if (!fs.rename(outPath, finalPath)) {
        throw new IOException("Unable to rename output to " + finalPath);
      }
    } else {
      if (!autoDelete) {
        fs.delete(outPath, true);
      }
    }
  }

  protected void fixTmpPathAlterTable(Path path) throws IOException, HiveException {
    /**
     * 1. boolean isListBucketingAlterTableConcatenate will be true only if it is alter table ...
     * concatenate on stored-as-dir so it will handle list bucketing alter table merge in the if
     * cause with the help of fixTmpPathConcatenate 2. If it is DML,
     * isListBucketingAlterTableConcatenate will be false so that it will be handled by else
     * cause. In this else cause, we have another if check. 2.1 the if check will make sure DP or
     * LB, we will fix path with the help of fixTmpPath(..). Since both has sub-directories. it
     * includes SP + LB. 2.2 only SP without LB, we dont fix path.
     */

    // Fix temp path for alter table ... concatenate
    if (isListBucketingAlterTableConcatenate) {
      if (this.tmpPathFixedConcatenate) {
        checkPartitionsMatch(path);
      } else {
        fixTmpPathConcatenate(path);
        tmpPathFixedConcatenate = true;
      }
    } else {
      if (hasDynamicPartitions || (listBucketingDepth > 0)) {
        if (tmpPathFixed) {
          checkPartitionsMatch(path);
        } else {
          // We haven't fixed the TMP path for this mapper yet
          fixTmpPath(path);
          tmpPathFixed = true;
        }
      }
    }
  }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.test.checkpointing;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HighAvailabilityOptions;
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobStatus;
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.test.state.ManualWindowSpeedITCase;
import org.apache.flink.test.util.MiniClusterResource;
import org.apache.flink.util.TestLogger;

import org.apache.curator.test.TestingServer;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

/**
 * IT case for resuming from checkpoints automatically via their external pointer.
 * When create checkpoint sub-directory is set to true, the tests would find latest completed checkpoint from previous job-id's directory,
 * otherwise the tests would find latest completed checkpoint from checkpoint base directory. If no checkpoint found, it will start from scratch.
 * This test checks that this works properly with the common state backends and checkpoint stores, in combination with incremental snapshots.
 *
 * <p>This tests considers full and incremental checkpoints and was introduced to guard against problems like FLINK-6964.
 */
@RunWith(Parameterized.class)
public class ResumeCheckpointAutomaticallyITCase extends TestLogger {

	private static final int PARALLELISM = 2;
	private static final int NUM_TASK_MANAGERS = 2;
	private static final int SLOTS_PER_TASK_MANAGER = 2;

	@Parameterized.Parameter
	public Boolean createCheckpointSubDir;

	@Parameterized.Parameters(name = "createCheckpointSubDir = {0}")
	public static List<Boolean> parameters() {
		return Arrays.asList(true, false);
	}

	@ClassRule
	public static TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void testExternalizedIncrementalRocksDBCheckpointsStandalone() throws Exception {
		final File checkpointDir = temporaryFolder.newFolder();
		testExternalizedCheckpoints(
			checkpointDir,
			null,
			createRocksDBStateBackend(checkpointDir, true));
	}

	@Test
	public void testExternalizedIncrementalRocksDBCheckpointsZookeeper() throws Exception {
		TestingServer zkServer = new TestingServer();
		zkServer.start();
		try {
			final File checkpointDir = temporaryFolder.newFolder();
			testExternalizedCheckpoints(
				checkpointDir,
				zkServer.getConnectString(),
				createRocksDBStateBackend(checkpointDir, true));
		} finally {
			zkServer.stop();
		}
	}

	@Test
	public void testExternalizedFullRocksDBCheckpointsStandalone() throws Exception {
		final File checkpointDir = temporaryFolder.newFolder();
		testExternalizedCheckpoints(
			checkpointDir,
			null,
			createRocksDBStateBackend(checkpointDir, false));
	}

	@Test
	public void testExternalizedFullRocksDBCheckpointsZookeeper() throws Exception {
		TestingServer zkServer = new TestingServer();
		zkServer.start();
		try {
			final File checkpointDir = temporaryFolder.newFolder();
			testExternalizedCheckpoints(
				checkpointDir,
				zkServer.getConnectString(),
				createRocksDBStateBackend(checkpointDir, false));
		} finally {
			zkServer.stop();
		}
	}

	@Test
	public void testExternalizedFSCheckpointsStandalone() throws Exception {
		final File checkpointDir = temporaryFolder.newFolder();
		testExternalizedCheckpoints(
			checkpointDir,
			null,
			createFsStateBackend(checkpointDir));
	}

	@Test
	public void testExternalizedFSCheckpointsZookeeper() throws Exception {
		TestingServer zkServer = new TestingServer();
		zkServer.start();
		try {
			final File checkpointDir = temporaryFolder.newFolder();
			testExternalizedCheckpoints(
				checkpointDir,
				zkServer.getConnectString(),
				createFsStateBackend(checkpointDir));
		} finally {
			zkServer.stop();
		}
	}

	private FsStateBackend createFsStateBackend(File checkpointDir) throws IOException {
		return new FsStateBackend(checkpointDir.toURI().toString(), true);
	}

	private RocksDBStateBackend createRocksDBStateBackend(
		File checkpointDir,
		boolean incrementalCheckpointing) throws IOException {

		return new RocksDBStateBackend(checkpointDir.toURI().toString(), incrementalCheckpointing);
	}

	private void testExternalizedCheckpoints(
		File checkpointDir,
		String zooKeeperQuorum,
		StateBackend backend) throws Exception {

		final Configuration config = new Configuration();

		final File savepointDir = temporaryFolder.newFolder();

		config.setString(CheckpointingOptions.CHECKPOINTS_DIRECTORY, checkpointDir.toURI().toString());
		config.setString(CheckpointingOptions.SAVEPOINT_DIRECTORY, savepointDir.toURI().toString());
		config.setBoolean(CheckpointingOptions.CHCKPOINTS_CREATE_SUBDIRS, createCheckpointSubDir);

		// ZooKeeper recovery mode?
		if (zooKeeperQuorum != null) {
			final File haDir = temporaryFolder.newFolder();
			config.setString(HighAvailabilityOptions.HA_MODE, "ZOOKEEPER");
			config.setString(HighAvailabilityOptions.HA_ZOOKEEPER_QUORUM, zooKeeperQuorum);
			config.setString(HighAvailabilityOptions.HA_STORAGE_PATH, haDir.toURI().toString());
		}

		MiniClusterResource cluster = new MiniClusterResource(
			new MiniClusterResource.MiniClusterResourceConfiguration(
				config,
				NUM_TASK_MANAGERS,
				SLOTS_PER_TASK_MANAGER),
			true);

		cluster.before();

		ClusterClient<?> client = cluster.getClusterClient();
		client.setDetached(true);

		try {
			// main test sequence:  start job -> eCP -> restore job -> eCP -> restore job
			JobID dummyJobID = new JobID();
			new File(checkpointDir, dummyJobID.toString()).mkdirs();
			// resume from a dummy job, would not find any valid checkpoint, but continue running from scratch.
			JobID jobID1 = runJobAndGetExternalizedCheckpoint(backend, checkpointDir, dummyJobID, client);

			JobID jobID2 = runJobAndGetExternalizedCheckpoint(backend, checkpointDir, jobID1, client);

			runJobAndGetExternalizedCheckpoint(backend, checkpointDir, jobID2, client);
		} finally {
			cluster.after();
		}
	}

	private JobID runJobAndGetExternalizedCheckpoint(StateBackend backend, File checkpointDir, JobID previousJobID, ClusterClient<?> client) throws Exception {
		JobGraph initialJobGraph = getJobGraph(backend, checkpointDir, previousJobID);
		NotifyingInfiniteTupleSource.countDownLatch = new CountDownLatch(PARALLELISM);

		client.submitJob(initialJobGraph, ResumeCheckpointManuallyITCase.class.getClassLoader());

		// wait until all sources have been started
		NotifyingInfiniteTupleSource.countDownLatch.await();

		waitUntilExternalizedCheckpointCreated(checkpointDir, initialJobGraph.getJobID());
		JobID newJobID = initialJobGraph.getJobID();
		client.cancel(newJobID);
		waitUntilCanceled(initialJobGraph.getJobID(), client);
		return newJobID;
	}

	private JobGraph getJobGraph(StateBackend backend, File checkpointDir, JobID previousJobID) {
		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

		env.enableCheckpointing(500);
		env.setStateBackend(backend);
		env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime);
		env.setParallelism(PARALLELISM);
		env.getCheckpointConfig().enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);

		env.addSource(new NotifyingInfiniteTupleSource(10_000))
			.keyBy(0)
			.timeWindow(Time.seconds(3))
			.reduce((value1, value2) -> Tuple2.of(value1.f0, value1.f1 + value2.f1))
			.filter(value -> value.f0.startsWith("Tuple 0"));

		StreamGraph streamGraph = env.getStreamGraph();
		streamGraph.setJobName("Test");

		JobGraph jobGraph = streamGraph.getJobGraph();

		// always try to find latest completed checkpoint, if not found, just start from scratch.
		if (createCheckpointSubDir) {
			jobGraph.setSavepointRestoreSettings(SavepointRestoreSettings.forResumePath(new File(checkpointDir, previousJobID.toString()).toString(), false));
		} else {
			jobGraph.setSavepointRestoreSettings(SavepointRestoreSettings.forResumePath(checkpointDir.toURI().toString(), false));
		}

		return jobGraph;
	}

	private void waitUntilExternalizedCheckpointCreated(File checkpointDir, JobID jobId) throws InterruptedException, IOException {
		while (true) {
			Thread.sleep(50);
			Optional<Path> externalizedCheckpoint = findExternalizedCheckpoint(checkpointDir, jobId);
			if (externalizedCheckpoint.isPresent()) {
				break;
			}
		}
	}

	private Optional<Path> findExternalizedCheckpoint(File checkpointDir, JobID jobId) throws IOException {
		Path checkpointPath = createCheckpointSubDir ? checkpointDir.toPath().resolve(jobId.toString()) : checkpointDir.toPath();
		return Files.list(checkpointPath)
			.filter(path -> path.getFileName().toString().startsWith("chk-"))
			.filter(path -> {
				try {
					return Files.list(path).anyMatch(child -> child.getFileName().toString().contains("meta"));
				} catch (IOException ignored) {
					return false;
				}
			})
			.findAny();
	}

	private static void waitUntilCanceled(JobID jobId, ClusterClient<?> client) throws ExecutionException, InterruptedException {
		while (client.getJobStatus(jobId).get() != JobStatus.CANCELED) {
			Thread.sleep(50);
		}
	}

	/**
	 * Infinite source which notifies when all of its sub tasks have been started via the count down latch.
	 */
	public static class NotifyingInfiniteTupleSource extends ManualWindowSpeedITCase.InfiniteTupleSource {

		private static final long serialVersionUID = 8120981235081181746L;

		private static CountDownLatch countDownLatch;

		public NotifyingInfiniteTupleSource(int numKeys) {
			super(numKeys);
		}

		@Override
		public void run(SourceContext<Tuple2<String, Integer>> out) throws Exception {
			if (countDownLatch != null) {
				countDownLatch.countDown();
			}

			super.run(out);
		}
	}
}
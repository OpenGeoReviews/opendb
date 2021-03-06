package org.openplacereviews.opendb.service.bots;

import static org.openplacereviews.opendb.ops.OpBlockchainRules.F_TYPE;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openplacereviews.opendb.ops.OpBlockchainRules;
import org.openplacereviews.opendb.ops.OpBlockchainRules.ErrorType;
import org.openplacereviews.opendb.ops.OpObject;
import org.openplacereviews.opendb.ops.OpOperation;
import org.openplacereviews.opendb.ops.PerformanceMetrics;
import org.openplacereviews.opendb.ops.PerformanceMetrics.Metric;
import org.openplacereviews.opendb.ops.PerformanceMetrics.PerformanceMetric;
import org.openplacereviews.opendb.service.BlocksManager;
import org.openplacereviews.opendb.service.LogOperationService;
import org.openplacereviews.opendb.service.SettingsManager;
import org.openplacereviews.opendb.util.JsonFormatter;
import org.openplacereviews.opendb.util.exception.FailedVerificationException;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

public abstract class GenericMultiThreadBot<T> implements IOpenDBBot<T> {

	protected final Log LOGGER = LogFactory.getLog(this.getClass());
	private static final long TIMEOUT_OVERPASS_HOURS = 4;
	private static final long TIMEOUT_BLOCK_CREATION_MS = 15000;
	public static final String F_BLOCKCHAIN_CONFIG = "blockchain-config";
	public static final String F_PLACES_PER_OPERATION = "places_per_operation";
	public static final String F_OPERATIONS_PER_BLOCK = "operations_per_block";
	public static final String F_OPERATIONS_MIN_BLOCK_CAPACITY = "min_block_capacity";
	public static final String F_THREADS = "threads";
	
	public static final String F_CONFIG = "config";
	public static final String F_BOT_STATE = "bot-state";
	public static final String F_OSM_TAGS = "osm-tags";
	public static final String F_DATE = "date";
	
	private static final PerformanceMetric mBlock = PerformanceMetrics.i().getMetric("bot.osm-sync.block");

	protected final boolean systemBot;
	protected final String id;
	protected final BotRunStats botRunStats = new BotRunStats();
	protected final List<TaskResult> successfulResults = new ArrayList<>();
	
	protected ThreadPoolExecutor service;
	protected long placesPerOperation = 250;
	protected long operationsPerBlock = 16;
	protected double blockCapacity = 0.8;
	protected OpObject botObject;
	protected String opType;
	
	@Autowired
	private BlocksManager blocksManager;
	
	@Autowired
	private LogOperationService logSystem;

	@Autowired
	private SettingsManager settingsManager;

	public GenericMultiThreadBot(OpObject botObject) {
		this.botObject = botObject;
		this.id = botObject.getId().get(0);
		this.systemBot = false;
	}
	
	public GenericMultiThreadBot(String id) {
		this.id = id;
		this.systemBot = true;
	}
	
	protected static class TaskResult { 
		public TaskResult(String msg, Exception e) {
			this.msg = msg;
			this.e = e;
		}
		
		public TaskResult(String msg, int cnt, Exception e) {
			this.msg = msg;
			this.e = e;
			this.counter = cnt;
		}
		
		Exception e;
		String msg;
		int counter;
	}
	
	protected Future<TaskResult> submitTask(String msg, Callable<TaskResult> task, Deque<Future<TaskResult>> futures) {
		if (service == null) {
			return null;
		}
		Future<TaskResult> f = service.submit(task);
		futures.add(f);
		botRunStats.getCurrentBotState().setAmountOfTasks(progress());
		return f;
	}
	
	protected int submitTaskAndWait(String msg, Callable<TaskResult> task, Deque<Future<TaskResult>> futures)
			throws Exception {
		if(service == null) {
			return 0;
		}
		if(msg != null) {
			info(msg);
		}
		Future<TaskResult> f = service.submit(task);
		int cnt = 0;
		int processed = 0;
		TaskResult r = waitFuture(cnt++, processed, f, futures);
		processed += r.counter;
		while (!futures.isEmpty()) {
			if(isInterrupted()) {
				break;
			}
			r = waitFuture(cnt++, processed, futures.pop(), futures);
			processed += r.counter;
		}
		return processed;
	}
	
	protected void waitBlockCreation() throws InterruptedException {
		while(blockCreateNeeded(3)) {
			Thread.sleep(TIMEOUT_BLOCK_CREATION_MS);
		}
	}

	private TaskResult waitFuture(int id, int overall,  Future<TaskResult> f, 
			Deque<Future<TaskResult>> futures) throws Exception {
		TaskResult r = f.get(TIMEOUT_OVERPASS_HOURS, TimeUnit.HOURS);
		int tot = id + futures.size();
		String msg = String.format("%d / %d (%d + %d): %s", id, tot, overall, r.counter, r.msg);
		if(r.e != null) {
			error(msg, r.e);
			throw r.e;
		} else {
			successfulResults.add(r);
			info(msg);
		}
		while (blockCreateNeeded(1)) {
			Metric m = mBlock.start();
			blocksManager.createBlock(blockCapacity);
			m.capture();
		}
		return r;
		
	}
	
	public OpOperation addOpIfNeeded(OpOperation op, boolean force) throws FailedVerificationException {
		int sz = (int) (force ? 0 : placesPerOperation - 1);
		if(op.getEdited().size() > sz || 
				op.getCreated().size() > sz || op.getDeleted().size() > sz) {
			generateHashAndSignAndAdd(op);
			op = initOpOperation(op.getType());
			if (blockCreateNeeded(1)) {
				blocksManager.createBlock(blockCapacity);
			}
		}
		return op;
	}
	
	protected boolean blockCreateNeeded(int factor) {
		return blocksManager.getBlockchain().getQueueOperations().size() >= operationsPerBlock * factor && 
				blocksManager.getQueueCapacity() >= blockCapacity * factor;
	}
	
	public OpOperation initOpOperation(String opType) {
		OpOperation opOperation = new OpOperation();
		opOperation.setType(opType);
		opOperation.setSignedBy(blocksManager.getServerUser());
		return opOperation;
	}
	
	public OpOperation generateHashAndSignAndAdd(OpOperation opOperation) throws FailedVerificationException {
		JsonFormatter formatter = blocksManager.getBlockchain().getRules().getFormatter();
		opOperation = formatter.parseOperation(formatter.opToJson(opOperation));
		OpOperation op = blocksManager.generateHashAndSign(opOperation, blocksManager.getServerLoginKeyPair());
		blocksManager.addOperation(op);
		botRunStats.getCurrentBotState().addOperation(opOperation);
		return op;
	}

	public boolean isInterrupted() {
		return this.service == null;
	}
	
	public List<TaskResult> getSuccessfulResults() {
		return successfulResults;
	}

	public void setSuccessState() {
		botRunStats.getCurrentBotState().setSuccess(progress());
	}

	public void setFailedState() {
		botRunStats.getCurrentBotState().setFailed(progress());
	}

	public void addNewBotStat() {
		botRunStats.createNewState(getMaxLogsAmount());
	}

	public Integer getMaxLogsAmount() {
		return settingsManager.OPENDB_BOTS_MAX_LOGS_AMOUNT.get();
	}


	private void addLogEntry(String log, Exception e) {
		botRunStats.getCurrentBotState().addLogEntry(log, e);
	}

	public void info(String msg) {
		LOGGER.info(msg);
		addLogEntry(msg, null);
	}

	public void info(String msg, Exception e) {
		LOGGER.info(msg, e);
		addLogEntry(msg, e);
	}

	public void error(String msg, Exception e) {
		LOGGER.error(msg, e);
		addLogEntry(msg, e);
	}
	
	public TaskResult errorResult(String msg, Exception e) {
		logSystem.logError(botObject, ErrorType.BOT_PROCESSING_ERROR, getTaskName() + " failed: " + e.getMessage(), e);
		addLogEntry(getTaskName() + " failed: " + e.getMessage(), e);
		return new TaskResult(e.getMessage(), e);
	}
	
	public void logError(String msg) {
		logSystem.logError(botObject, ErrorType.BOT_PROCESSING_ERROR, msg, null);
		addLogEntry(msg, null);
	}
	
	protected void initVars() {
		OpObject nbot = blocksManager.getBlockchain().getObjectByName(OpBlockchainRules.OP_BOT, id);
		if (nbot == null) {
			throw new IllegalStateException("Can't retrieve bot object state");
		}
		botObject = nbot;
		this.opType = botObject.getStringMap(F_BLOCKCHAIN_CONFIG).get(F_TYPE);
		this.placesPerOperation = botObject.getField(placesPerOperation, F_BLOCKCHAIN_CONFIG,
				F_PLACES_PER_OPERATION);
		this.operationsPerBlock = botObject.getField(operationsPerBlock, F_BLOCKCHAIN_CONFIG,
				F_OPERATIONS_PER_BLOCK);
		this.blockCapacity = botObject.getField(blockCapacity, F_BLOCKCHAIN_CONFIG,
				F_OPERATIONS_MIN_BLOCK_CAPACITY);
		ThreadFactory namedThreadFactory = new ThreadFactoryBuilder()
				.setNameFormat(Thread.currentThread().getName() + "-%d").build();
		this.service = (ThreadPoolExecutor) Executors.newFixedThreadPool(botObject.getIntValue(F_THREADS, 1),
				namedThreadFactory);
		successfulResults.clear();
	}
	
	protected void shutdown() {
		if (this.service != null) {
			this.service.shutdownNow();
			this.service = null;
		}
	}

	@Override
	public abstract String getTaskDescription();

	@Override
	public abstract String getTaskName();

	@Override
	public int taskCount() {
		return 1;
	}

	@Override
	public int total() {
		return 1 + (service  == null ? 1 : (int) service.getTaskCount());
	}

	@Override
	public int progress() {
		return 1 + (service == null ? 1 : (int) service.getCompletedTaskCount());
	}

	@Override
	public boolean isRunning() {
		return botRunStats.isRunning();
	}

	@Override
	public Deque<BotRunStats.BotStats> getHistoryRuns() {
		return botRunStats.botStats;
	}

	@Override
	public boolean interrupt() {
		if(this.service != null) {
			this.service.shutdownNow();
			this.service = null;
			botRunStats.getCurrentBotState().setInterrupted();
			return true;
		}
		return false;
	}
	
	@Override
	public String getId() {
		return id;
	}

	@Override
	public String getAPI() {
		return getClass().getName();
	}

	@Override
	public boolean isSystemBot() {
		return systemBot;
	}
}

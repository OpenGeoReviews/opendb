package org.openplacereviews.opendb.service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openplacereviews.opendb.FailedVerificationException;
import org.openplacereviews.opendb.OUtils;
import org.openplacereviews.opendb.OpenDBServer.MetadataDb;
import org.openplacereviews.opendb.SecUtils;
import org.openplacereviews.opendb.api.MgmtController;
import org.openplacereviews.opendb.ops.OpBlock;
import org.openplacereviews.opendb.ops.OpBlockChain;
import org.openplacereviews.opendb.ops.OpBlockchainRules;
import org.openplacereviews.opendb.ops.OpBlockchainRules.ErrorType;
import org.openplacereviews.opendb.ops.OpObject;
import org.openplacereviews.opendb.ops.OpOperation;
import org.openplacereviews.opendb.ops.ValidationTimer;
import org.openplacereviews.opendb.util.JsonFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;


@Service
public class BlocksManager {
	protected static final Log LOGGER = LogFactory.getLog(BlocksManager.class);
	
	@Autowired
	private LogOperationService logSystem;
	
	@Autowired
	private JsonFormatter formatter;
	
	@Autowired
	private DBConsensusManager dataManager;
	
	protected String[] BOOTSTRAP_LIST = 
			new String[] {"opr-0-test-user", "std-ops-defintions", "std-roles", "opr-0-test-grant", "std-validations"};
	
	@Value("${opendb.replicate.url}")
	private String replicateUrl;
	
	@Value("${opendb.mgmt.user}")
	private String serverUser;
	
	@Value("${opendb.mgmt.privateKey}")
	private String serverPrivateKey;
	
	@Value("${opendb.mgmt.publicKey}")
	private String serverPublicKey;
	private KeyPair serverKeyPair;
	
	private BlockchainMgmtStatus mgmtStatus = BlockchainMgmtStatus.BLOCK_CREATION; 
	
	private OpBlockChain blockchain; 
	
	private enum BlockchainMgmtStatus {
		BLOCK_CREATION,
		REPLICATION,
		NONE,
	}
	
	public String getServerPrivateKey() {
		return serverPrivateKey;
	}
	
	public String getServerUser() {
		return serverUser;
	}
	
	public KeyPair getServerLoginKeyPair() {
		return serverKeyPair;
	}
	
	public boolean isBlockCreationOn() {
		return this.mgmtStatus == BlockchainMgmtStatus.BLOCK_CREATION;
	}
	
	public boolean isReplicateOn() {
		return this.mgmtStatus == BlockchainMgmtStatus.REPLICATION && !OUtils.isEmpty(replicateUrl);
	}
	
	public String getReplicateUrl() {
		return replicateUrl;
	}
	
	public synchronized void setReplicateOn(boolean on) {
		if(on && this.mgmtStatus == BlockchainMgmtStatus.NONE) {
			this.mgmtStatus = BlockchainMgmtStatus.REPLICATION;
		} else if(!on && this.mgmtStatus == BlockchainMgmtStatus.REPLICATION) {
			this.mgmtStatus = BlockchainMgmtStatus.NONE;
		}
	}
	
	public synchronized void setBlockCreationOn(boolean on) {
		if(on && this.mgmtStatus == BlockchainMgmtStatus.NONE) {
			this.mgmtStatus = BlockchainMgmtStatus.BLOCK_CREATION;
		} else if(!on && this.mgmtStatus == BlockchainMgmtStatus.BLOCK_CREATION) {
			this.mgmtStatus = BlockchainMgmtStatus.NONE;
		}
	}
	
	public synchronized void init(MetadataDb metadataDB, OpBlockChain initBlockchain) {
		try {
			this.serverKeyPair = SecUtils.getKeyPair(SecUtils.ALGO_EC, serverPrivateKey, serverPublicKey);
		} catch (FailedVerificationException e) {
			LOGGER.error("Error validating server private / public key: " + e.getMessage(), e);
			throw new RuntimeException(e);
		}
		this.blockchain = initBlockchain;
		
		String msg = "";
		// db is bootstraped
		LOGGER.info("+++ Blockchain is inititialized. " + msg);
	}
	
	public synchronized boolean unlockBlockchain() {
		if(blockchain.getStatus() == OpBlockChain.LOCKED_BY_USER) {
			blockchain.unlockByUser();
			return true;
		}
		return false;
	}
	
	public synchronized boolean lockBlockchain() {
		if(blockchain.getStatus() == OpBlockChain.UNLOCKED) {
			blockchain.lockByUser();
			return true;
		}
		return false;
	}
	
	public synchronized boolean validateOperation(OpOperation op) {
		if(blockchain == null) {
			return false;
		}
		return blockchain.validateOperation(op);
	}
	
	public synchronized boolean removeOrphanedBlock(String blockHash) {
		OpBlock block = dataManager.getOrphanedBlocks().get(blockHash);
		if(block != null) {
			return dataManager.removeFullBlock(block);
		}
		return false;
	}
	
	public synchronized boolean addOperation(OpOperation op) {
		if(blockchain == null) {
			return false;
		}
		op.makeImmutable();
		boolean existing = dataManager.validateExistingOperation(op);
		boolean added = blockchain.addOperation(op);
		// all 3 methods in synchronized block, so it is almost guaranteed insertOperation won't fail
		// or that operation will be lost in queue and system needs to be restarted
		if(!existing) {
			dataManager.insertOperation(op);
		}
		return added;
	}
	
	public synchronized OpBlock createBlock() throws FailedVerificationException {
		// should be changed synchronized in future:
		// This method doesn't need to be full synchronized cause it could block during compacting or any other operation adding ops
		
		if (OpBlockChain.UNLOCKED != blockchain.getStatus()) {
			throw new IllegalStateException("Blockchain is not ready to create block");
		}
		ValidationTimer timer = new ValidationTimer();
		timer.start();
		
		List<OpOperation> candidates = pickupOpsFromQueue(blockchain.getQueueOperations());
		
		int tmAddOps = timer.startExtra();
		OpBlockChain blc = new OpBlockChain(blockchain.getParent(), blockchain.getRules());
		for (OpOperation o : candidates) {
			if(!blc.addOperation(o)) {
				return null;
			}
		}
		timer.measure(tmAddOps, ValidationTimer.BLC_ADD_OPERATIONS);
		
		int tmNewBlock = timer.startExtra();
		OpBlock opBlock = blc.createBlock(serverUser, serverKeyPair);
		if(opBlock == null) {
			return null;
		}
		timer.measure(tmNewBlock, ValidationTimer.BLC_NEW_BLOCK);
		
		return replicateValidBlock(timer, blc, opBlock);
	}

	private OpBlock replicateValidBlock(ValidationTimer timer, OpBlockChain blockChain, OpBlock opBlock) {
		// insert block could fail if hash is duplicated but it won't hurt the system
		int tmDbSave = timer.startExtra();
		dataManager.insertBlock(opBlock);
		timer.measure(tmDbSave, ValidationTimer.BLC_BLOCK_SAVE);
		
		// change only after block is inserted into db
		int tmRebase = timer.startExtra();
		boolean changeParent = blockchain.rebaseOperations(blockChain);
		if(!changeParent) {
			return null;
		}
		timer.measure(tmRebase, ValidationTimer.BLC_REBASE);
		
		int tmSDbSave = timer.startExtra();
		OpBlockChain savedParent = dataManager.saveMainBlockchain(blockchain.getParent());
		if(blockchain.getParent() != savedParent) {
			blockchain.changeToEqualParent(savedParent);
		}
		timer.measure(tmSDbSave, ValidationTimer.BLC_SAVE);
		
		int tmCompact = timer.startExtra();
		compact();
		timer.measure(tmCompact, ValidationTimer.BLC_COMPACT);
		
		
		opBlock.putCacheObject(OpObject.F_VALIDATION, timer.getTimes());
		logSystem.logSuccessBlock(opBlock, 
				String.format("New block '%s':%d  is created on top of '%s'. ",
						opBlock.getFullHash(), opBlock.getBlockId(), opBlock.getStringValue(OpBlock.F_PREV_BLOCK_HASH) ));
		return opBlock;
	}

	public synchronized boolean compact() {
		OpBlockChain newParent = dataManager.compact(0, blockchain.getParent(), true);
		if(newParent != blockchain.getParent()) {
			blockchain.changeToEqualParent(newParent);
		}
		return true;
	}
	
	public synchronized boolean clearQueue() {
		TreeSet<String> set = new TreeSet<>(); 
		for(OpOperation o: blockchain.getQueueOperations()) {
			set.add(o.getRawHash());
		}
		boolean cleared = blockchain.removeAllQueueOperations();
		if(!cleared) {
			return false;
		}
		return dataManager.removeOperations(set) == set.size();
		// blockchain = new OpBlockChain(blockchain.getParent(), blockchain.getRules());
	}
	
	private Reader readerFromUrl(String url) throws IOException {
		return new InputStreamReader(new URL(url).openStream());
	}
	
	public synchronized boolean replicate() {
		if(isReplicateOn()) {
			try {
				String from = blockchain.getLastBlockRawHash();
				OpBlock[] replicateBlockHeaders = formatter.fromJson(
						readerFromUrl(replicateUrl + "blocks?from=" + from), 
								OpBlock[].class);
				LinkedList<OpBlock> headersToReplicate = new LinkedList<OpBlock>(Arrays.asList(replicateBlockHeaders));
				if(!OUtils.isEmpty(from) && headersToReplicate.size() > 0) {
					if(!OUtils.equals(headersToReplicate.peekFirst().getRawHash(), from)) {
						logSystem.logError(headersToReplicate.peekFirst(), ErrorType.MGMT_REPLICATION_BLOCK_CONFLICTS, 
								ErrorType.MGMT_REPLICATION_BLOCK_CONFLICTS.getErrorFormat(
										headersToReplicate.peekFirst().getRawHash(), from, headersToReplicate), null);
						return false;
					} else {
						headersToReplicate.removeFirst();	
					}
				}
				for(OpBlock header : headersToReplicate) {
					OpBlock fullBlock = downloadBlock(header);
					if(fullBlock == null) {
						logSystem.logError(header, ErrorType.MGMT_REPLICATION_BLOCK_DOWNLOAD_FAILED, 
								ErrorType.MGMT_REPLICATION_BLOCK_DOWNLOAD_FAILED.getErrorFormat(header.getRawHash()), null);
						return false;
					}
					fullBlock.makeImmutable();
					for (OpOperation o : fullBlock.getOperations()) {
						if (!dataManager.validateExistingOperation(o)) {
							dataManager.insertOperation(o);
						}
					}
					replicateOneBlock(fullBlock);
				}
				return true;
			} catch (IOException e) {
				LOGGER.error(e.getMessage(), e);
				logSystem.logError(null, ErrorType.MGMT_REPLICATION_IO_FAILED, "Failed to replicate from " + replicateUrl, e);
			}
		}
		return false;
	}

	private OpBlock downloadBlock(OpBlock header) throws MalformedURLException, IOException {
		URL downloadByHash = new URL(replicateUrl + "block-by-hash?hash=" + header.getRawHash());
		OpBlock res = formatter.fromJson(new InputStreamReader(downloadByHash.openStream()), OpBlock.class);
		if(res.getBlockId() == -1) {
			return null;
		}
		return res;
	}
	
	public synchronized boolean replicateOneBlock(OpBlock block) {
		ValidationTimer timer = new ValidationTimer();
		timer.start();
		OpBlockChain blc = new OpBlockChain(blockchain.getParent(), blockchain.getRules());
		OpBlock res;
		res = blc.replicateBlock(block);
		if(res == null) {
			return false;
		}
		res = replicateValidBlock(timer, blc, res);
		if(res == null) {
			return false;
		}
		return true;
	}
	
	public synchronized Set<String> removeQueueOperations(Set<String> operationsToDelete) {
		Set<String> deleted;
		try {
			deleted = blockchain.removeQueueOperations(operationsToDelete);
		} catch (RuntimeException e) {
			// handle non last operations - slow method
			deleted = new TreeSet<String>();
			OpBlockChain blc = new OpBlockChain(blockchain.getParent(), blockchain.getRules());
			for (OpOperation o : blockchain.getQueueOperations()) {
				if (!operationsToDelete.contains(o.getRawHash())) {
					if (!blc.addOperation(o)) {
						return null;
					}
				} else {
					deleted.add(o.getRawHash());
				}
			}
			blockchain = blc; 
		}
		dataManager.removeOperations(deleted);
		return deleted;
	}
	
	public synchronized void bootstrap(String serverName, KeyPair serverLoginKeyPair) throws FailedVerificationException {
		for (String f : BOOTSTRAP_LIST) {
			OpOperation[] lst = formatter.fromJson(
					new InputStreamReader(MgmtController.class.getResourceAsStream("/bootstrap/" + f + ".json")),
					OpOperation[].class);
			if (!OUtils.isEmpty(serverName)) {
				for (OpOperation o : lst) {
					OpOperation op = o;
					if (!OUtils.isEmpty(serverName) && o.getSignedBy().isEmpty()) {
						op.setSignedBy(serverName);
						op = generateHashAndSign(op, serverLoginKeyPair);
					}
					addOperation(op);
				}
			}
		}
	}
	
	public synchronized boolean revertOneBlock() throws FailedVerificationException {
		if (OpBlockChain.UNLOCKED != blockchain.getStatus()) {
			throw new IllegalStateException("Blockchain is not ready to create block");
		}
		if (blockchain.getLastBlockRawHash().equals("")) {
			return false;
		}
		if (blockchain.getParent().getSuperblockSize() == 1 || blockchain.getParent().isDbAccessed()) {
			return revertSuperblock();
		}

		OpBlockChain newParent = new OpBlockChain(blockchain.getParent().getParent(), blockchain.getRules());
		Deque<OpBlock> superblockFullBlocks = blockchain.getParent().getSuperblockFullBlocks();
		Iterator<OpBlock> it = superblockFullBlocks.descendingIterator();
		if (!it.hasNext()) {
			return false;
		}
		OpBlock blockToRevert = null;
		while (it.hasNext()) {
			blockToRevert = it.next();
			if (it.hasNext()) {
				newParent.replicateBlock(blockToRevert);
			}
		}
		OpBlockChain blc = new OpBlockChain(newParent, blockchain.getRules());
		for (OpOperation o : blockToRevert.getOperations()) {
			if (!blc.addOperation(o)) {
				return false;
			}
		}
		for (OpOperation o : blockchain.getQueueOperations()) {
			if (!blc.addOperation(o)) {
				return false;
			}
		}
		dataManager.removeFullBlock(blockToRevert);
		blockchain = blc;
		String msg = String.format("Revert block '%s:%d'", 
				blockToRevert.getRawHash(), blockToRevert.getBlockId());
		logSystem.logSuccessBlock(blockToRevert, msg);
		return true;
	}
	
	public synchronized boolean revertSuperblock() throws FailedVerificationException {
		if (OpBlockChain.UNLOCKED != blockchain.getStatus()) {
			throw new IllegalStateException("Blockchain is not ready to create block");
		}
		if(blockchain.getParent() == null) {
			return false;
		}
		
		OpBlockChain parent = blockchain.getParent();
		if(parent.isDbAccessed()) {
			OpBlockChain newParent = dataManager.unloadSuperblockFromDB(parent);
			return blockchain.changeToEqualParent(newParent);
		} else {
			OpBlockChain blc = new OpBlockChain(blockchain.getParent().getParent(), blockchain.getRules());
			OpBlockChain pnt = blockchain.getParent();
			List<OpBlock> lst = new ArrayList<OpBlock>(pnt.getSuperblockFullBlocks());
			Collections.reverse(lst);
			for (OpBlock bl : lst) {
				for (OpOperation u : bl.getOperations()) {
					if (!blc.addOperation(u)) {
						return false;
					}
				}
				dataManager.removeFullBlock(bl);
			}
			for (OpOperation o : blockchain.getQueueOperations()) {
				if (!blc.addOperation(o)) {
					return false;
				}
			}
			blockchain = blc;
			String msg = String.format("Revert superblock from '%s:%d' to '%s:%d'", 
					parent.getLastBlockFullHash(), parent.getLastBlockId(), blockchain.getLastBlockFullHash(), blockchain.getLastBlockId());
			logSystem.logSuccessBlock(blockchain.getLastBlockHeader(), msg);
		}
		return true;
	}
	
	public OpBlockChain getBlockchain() {
		return blockchain == null ? OpBlockChain.NULL : blockchain;
	}
	
	public Map<String, OpBlock> getOrphanedBlocks() {
		return dataManager.getOrphanedBlocks();
	}
	
	public String getCurrentState() {
		if(blockchain.getStatus() == OpBlockChain.UNLOCKED) {
			return "READY";
		} else if(blockchain.getStatus() == OpBlockChain.LOCKED_STATE) {
			return "LOCKED";
		} else if(blockchain.getStatus() == OpBlockChain.LOCKED_OP_IN_PROGRESS) {
			return "OP_IN_PROGRESS";
		}
		return "ERROR";
	}
	
	public boolean isBlockchainPaused() {
		return blockchain.getStatus() != OpBlockChain.UNLOCKED;
	}
	
	public OpOperation generateHashAndSign(OpOperation op, KeyPair... keyPair) throws FailedVerificationException {
		return blockchain.getRules().generateHashAndSign(op, keyPair);
	}
	
	public KeyPair getLoginKeyPairFromPwd(String name, String pwd) throws FailedVerificationException {
		return blockchain.getRules().getSignUpKeyPairFromPwd(blockchain, name, pwd);
	}
	
	public KeyPair getLoginKeyPair(String name, String privateKey) throws FailedVerificationException {
		return blockchain.getRules().getLoginKeyPair(blockchain, name, privateKey);
	}

	public OpObject getLoginObj(String nickname) {
		return blockchain.getRules().getLoginKeyObj(blockchain, nickname);
	}

	private List<OpOperation> pickupOpsFromQueue(Collection<OpOperation> q) {
		int size = 0;
		List<OpOperation> candidates = new ArrayList<OpOperation>();
		for (OpOperation o : q) {
			int l = formatter.opToJson(o).length();
			if (size + l > OpBlockchainRules.MAX_BLOCK_SIZE_MB) {
				break;
			}
			if (candidates.size() + 1 >= OpBlockchainRules.MAX_BLOCK_SIZE_OPS) {
				break;
			}
			candidates.add(o);
		}
		return candidates;
	}

}

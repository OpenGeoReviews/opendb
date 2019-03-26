package org.openplacereviews.opendb.ops;

import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.openplacereviews.opendb.ops.OpBlockChain.BlockDbAccessInterface;

public class OpPrivateBlocksList {

	private final Deque<OpBlock> blocks = new ConcurrentLinkedDeque<OpBlock>();
	private final Deque<OpBlock> blockHeaders = new ConcurrentLinkedDeque<OpBlock>();
	private final Map<String, OpBlock> blocksInfo = new ConcurrentHashMap<>();
	private final BlockDbAccessInterface dbAccess;
	
	public OpPrivateBlocksList() {
		this.dbAccess = null;
	}
	
	public OpPrivateBlocksList(Collection<OpBlock> headers, int superBlockDepth, BlockDbAccessInterface dbAccess) {
		if (headers != null) {
			for (OpBlock o : headers) {
				addBlockHeader(o, superBlockDepth);
			}
		}
		this.dbAccess = dbAccess;
	}

	public Collection<OpBlock> getAllBlocks() {
		if(dbAccess != null){
			return dbAccess.getAllBlocks(blockHeaders);
		}
		return blocks;
	}
	
	public OpBlock getBlockByHash(String rawHash) {
		if (dbAccess != null) {
			return dbAccess.getBlockByHash(rawHash);
		}
		// it could be optimized cause we could access blockheader quickly
		for(OpBlock b : blocks) {
			if(b.getRawHash().equals(rawHash)) {
				return b;
			}
		}
		return null;
	}
	
	public Collection<OpBlock> getAllBlockHeaders() {
		return blockHeaders;
	}
	
	public OpBlock getLastBlockHeader() {
		return blockHeaders.peekFirst();
	}
	
	public OpBlock getFirstBlockHeader() {
		return blockHeaders.peekLast();
	}
	
	public int size() {
		return blockHeaders.size();
	}
	
	public OpBlock getBlockHeaderByHash(String rawHash) {
		return blocksInfo.get(rawHash);
	}
	
	public String getSuperBlockHash() {
		if (blockHeaders.size() == 0) {
			return "";
		}
		OpBlock l = getLastBlockHeader();
		OpBlock f = getFirstBlockHeader();
		if( l == null || f == null) {
			return "";
		}
		String hash = l == null ? "" : l.getRawHash();
		int sz = l.getBlockId() - f.getBlockId() + 1;
		return OpBlockchainRules.calculateSuperblockHash(sz, hash);
	}


	void addBlock(OpBlock block, int superBlockDepth) {
		if(dbAccess != null){
			throw new UnsupportedOperationException();
		}
		blocks.push(block);
		addBlockHeader(block, superBlockDepth);
	}

	private void addBlockHeader(OpBlock block, int superBlockDepth) {
		OpBlock blockHeader = new OpBlock(block, false, true).makeImmutable();
		blockHeader.putCacheObject(OpBlock.F_OPERATIONS_SIZE, block.getOperations().size());
		blocksInfo.put(blockHeader.getRawHash(), blockHeader);
		blockHeaders.push(blockHeader);
		updateHeaders(superBlockDepth);
	}

	private void updateHeaders(int superBlockDepth) {
		String sb = getSuperBlockHash();
		for(OpBlock blHeader : blockHeaders) {
			blHeader.putCacheObject(OpBlock.F_SUPERBLOCK_HASH, sb);
			blHeader.putCacheObject(OpBlock.F_SUPERBLOCK_ID, superBlockDepth);
		}
	}


	void copyAndMerge(OpPrivateBlocksList copy, OpPrivateBlocksList parent, int superBlockDepth) {
		if(dbAccess != null){
			throw new UnsupportedOperationException();
		}
		blocks.addAll(copy.blocks);
		blocks.addAll(parent.blocks);
		
		blockHeaders.addAll(copy.blockHeaders);
		blockHeaders.addAll(parent.blockHeaders);
		
		blocksInfo.putAll(copy.blocksInfo);
		blocksInfo.putAll(parent.blocksInfo);
		updateHeaders(superBlockDepth);
		
	}

	void clear() {
		if(dbAccess != null){
			throw new UnsupportedOperationException();
		}
		blocks.clear();
		blocksInfo.clear();
		blockHeaders.clear();
		
	}


	
}

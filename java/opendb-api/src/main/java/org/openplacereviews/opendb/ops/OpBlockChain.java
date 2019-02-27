package org.openplacereviews.opendb.ops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.openplacereviews.opendb.FailedVerificationException;
import org.openplacereviews.opendb.OUtils;
import org.openplacereviews.opendb.ops.OpBlockchainRules.ErrorType;

public class OpBlockChain {
	
	private final OpBlockChain parent;
	private final int parentBlockLastId;
	
	private static final int LOCKED_ERROR = -1;
	private static final int LOCKED_SUCCESS =  1;
	private static final int UNLOCKED =  0;
	private int locked = UNLOCKED; // 0, -1 error, 1 intentional
	
	private final Deque<OpBlock> blocks;
	private final Deque<OpOperation> operations;
	private final Map<String, Integer> blockDepth = new ConcurrentHashMap<>();
	// stores information about created and deleted objects in this blockchain 
	private final Map<String, OperationDeleteInfo> opsByHash = new ConcurrentHashMap<>();
	// stores information about last object by name in this blockchain
	private final Map<String, ObjectInstancesById> objByName = new ConcurrentHashMap<>();

	
	public OpBlockChain(OpBlockChain parent) {
		this.parent = parent;
		this.operations = new ConcurrentLinkedDeque<OpOperation>();
		this.blocks = new ConcurrentLinkedDeque<OpBlock>();
		// TODO this.parent.makeImmutable();
		if(this.parent != null) {
			parentBlockLastId = this.parent.getLastBlockId();
		} else {
			parentBlockLastId = -1;
		}
	}
	
	public synchronized void makeImmutable() {
		if(this.locked == UNLOCKED) {
			this.locked = LOCKED_SUCCESS;
		}
	}
	
	public synchronized OpBlock createBlock(OpBlockchainRules rules) throws FailedVerificationException {
		if(locked != UNLOCKED) {
			throw new IllegalStateException("Object is immutable");
		}
		OpBlock block = rules.createAndSignBlock(operations, getLastBlock());
		rules.validateBlock(this, block, getLastBlock());
		String blockHash = block.getHash();
		int blockId = block.getBlockId();
		operations.clear();
		blockDepth.put(blockHash, blockId);
		blocks.push(block);
		return block;
	}
	
	public synchronized void changeParent(OpBlockChain blc) {
		// TODO Auto-generated method stub
		// this.objByName - doesn't change
		// this.opsByHash - doesn't change
		
		// this.blockDepth
		// this.blocks
		// 
		
	}
	
	public synchronized void compact() {
		// TODO Auto-generated method stub
		
	}
	
	public synchronized boolean removeDupOperation(OpOperation op, OpBlockchainRules rules) {
		if(locked != 0) {
			throw new IllegalStateException("This chain is immutable");
		}

		String type = op.getType();
		boolean deleted = false;
		Iterator<OpOperation> it = operations.iterator();
		List<OpOperation> prevOperationsSameType = null;
		while (it.hasNext()) {
			OpOperation n = it.next();
			if (n.getHash().equals(op.getHash())) {
				deleted = true;
				break;
			}
			if (OUtils.equals(type, n.getType())) {
				if (prevOperationsSameType == null) {
					prevOperationsSameType = new ArrayList<OpOperation>();
				}
				prevOperationsSameType.add(n);
				type = null;
			}
		}
		if (!deleted) {
			return false;
		}
		locked = LOCKED_SUCCESS;
		try {
			atomicRemoveOperation(op, it, prevOperationsSameType);
			locked = UNLOCKED;
		} finally {
			if(locked == LOCKED_SUCCESS) {
				locked = LOCKED_ERROR;
			}
		}
		return true;
	}

	private void atomicRemoveOperation(OpOperation op, Iterator<OpOperation> it, List<OpOperation> prevOperationsSameType) {
		// delete operation itself
		it.remove();
		
		OperationDeleteInfo odi = opsByHash.get(op.getHash());
		odi.create = false;
		// delete deleted objects by name
		List<String> deletedRefs = op.getOld();
		for (int i = 0; i < deletedRefs.size(); i++) {
			String delRef = deletedRefs.get(i);
			String delHash = getHashFromAbsRef(delRef);
			int delInd = getIndexFromAbsRef(delRef);
			OperationDeleteInfo pi = opsByHash.get(delHash);
			if (pi != null && pi.deletedObjects.length > delInd) {
				pi.deletedObjects[delInd] = false;
			}
		}

		// delete new objects by name
		for (OpObject ok : op.getNew()) {
			List<String> id = ok.getId();
			if (id != null && id.size() > 0) {
				String objType = id.get(0);
				ObjectInstancesById oinf = getObjectsByIdMap(objType, true);
				OpObject currentObj = oinf.getObjectById(1, id);
				if (ok.equals(currentObj)) {
					OpObject p = null;
					if (prevOperationsSameType != null) {
						p = findLast(prevOperationsSameType, id);
					}
					oinf.add(id, p);
				}
			}
		}
	}
	
	private OpObject findLast(List<OpOperation> list, List<String> id) {
		OpObject last = null;
		for(OpOperation o : list) {
			for(OpObject obj : o.getNew()) {
				if(OUtils.equals(obj.getId(), id)) {
					last = obj;
				}
			}
		}
		return last;
	}

	/**
	 * Adds operation and validates it to block chain
	 */
	public synchronized boolean addOperation(OpOperation op, OpBlockchainRules rules) {
		// make this method synchronized so during preparation there are no possible 
		if(locked != UNLOCKED) {
			throw new IllegalStateException("This chain is immutable");
		}
		LocalValidationCtx validationCtx = new LocalValidationCtx();
		validationCtx.rules = rules;
		validationCtx.blockHash = "";
		boolean valid = validateAndPrepareOperation(op, validationCtx);
		if(!valid) {
			return valid;
		}
		locked = LOCKED_SUCCESS;
		try {
			atomicAddOperationAfterPrepare(op, validationCtx);
			locked = UNLOCKED;
		} finally {
			if(locked == LOCKED_SUCCESS) {
				locked = LOCKED_ERROR;
			}
		}
		return valid;
	}
	
	public Collection<OpOperation> getOperations() {
		return operations;
	}
	
	public OpBlockChain getParent() {
		return parent;
	}
	
	public boolean isImmutable() {
		return locked != UNLOCKED;
	}
	

	public OpBlock getLastBlock() {
		if(blocks.size() == 0) {
			return parent == null ? null : parent.getLastBlock();
		}
		return blocks.peekFirst();
	}


	
	public int getLastBlockId() {
		OpBlock o = getLastBlock();
		return o != null ? o.getBlockId() : -1;
	}
	
	public int getSubchainSize() {
		return blocks.size();
	}
	
	public OpBlock getBlockById(int blockId) {
		int last = getLastBlockId();
		if(last < blockId) {
			return null;
		}
		int first = last - getSubchainSize() + 1;
		if(first <= blockId) {
			int it = last - blockId; 
			Iterator<OpBlock> its = blocks.iterator();
			while(--it > 0) {
				its.next();
			}
			return its.next();
		}
		return parent == null ? null : parent.getBlockById(blockId); 
	}
	

	public int getBlockDepth(String hash) {
		Integer n = blockDepth.get(hash);
		if(n != null) {
			return n;
		}
		if(parent != null) {
			return parent.getBlockDepth(hash);
		}
		return -1;
	}
	
	
	private ObjectInstancesById getObjectsByIdMap(String type, boolean create) {
		ObjectInstancesById oi = objByName.get(type);
		if(oi == null) {
			ObjectInstancesById pi = parent == null ? null : parent.getObjectsByIdMap(type, true);
			if(create) {
				oi = new ObjectInstancesById(type, pi);
				objByName.put(type, oi);
			} else {
				oi = pi;
			}
		}
		return oi;
	}
	
	public OpObject getObjectByName(String type, String key) {
		ObjectInstancesById ot = getObjectsByIdMap(type, false);
		if(ot == null) {
			return null;
		}
		return ot.getObjectById(key, null);
	}
	
	public OpObject getObjectByName(String type, String key, String secondary) {
		ObjectInstancesById ot = getObjectsByIdMap(type, false);
		if(ot == null) {
			return null;
		}
		return ot.getObjectById(key, secondary);
	}
	
	private OpObject getObjectByName(List<String> o) {
		String objType = o.get(0);
		ObjectInstancesById ot = getObjectsByIdMap(objType, false);
		if(ot == null) {
			return null;
		}
		return ot.getObjectById(1, o);
	}
	
	
	private String getHashFromAbsRef(String r) {
		int i = r.indexOf(':');
		if(i == -1) {
			return r;
		}
		return r.substring(0, i);
	}
	
	private int getIndexFromAbsRef(String r) {
		int i = r.indexOf(':');
		if (i == -1) {
			return 0;
		}
		return Integer.parseInt(r.substring(i + 1));
	}
	
	private void atomicAddOperationAfterPrepare(OpOperation u, LocalValidationCtx validationCtx) {
		for(OpObject newObj : u.getNew()){
			List<String> id = newObj.getId();
			if(id != null && id.size() > 0) {
				String objType = id.get(0);
				ObjectInstancesById oinf = getObjectsByIdMap(objType, true);
				oinf.add(id, newObj);
			}
		}
		List<String> deletedRefs = u.getOld();
		for(int i = 0; i < deletedRefs.size(); i++) {
			String delRef = deletedRefs.get(i);
			String delHash = getHashFromAbsRef(delRef);
			int delInd = getIndexFromAbsRef(delRef);
			OperationDeleteInfo pi = opsByHash.get(delHash);
			if(pi == null) {
				pi = new OperationDeleteInfo();
				pi.op = validationCtx.deletedOpsCache.get(i);
				pi.deletedObjects = new boolean[pi.op.getNew().size()];
				opsByHash.put(delHash, pi);
			}
			pi.deletedObjects[delInd] = true;
		}
		OperationDeleteInfo infop = new OperationDeleteInfo();
		infop.op = u;
		opsByHash.put(u.getHash(), infop);
	}
	
	private OperationDeleteInfo getOperationInfo(String hash) {
		OperationDeleteInfo opInfo = null;
		OpBlockChain blc = this;
		while (blc != null && opInfo == null) {
			opInfo = blc.opsByHash.get(hash);
			blc = blc.parent;
		}
		return opInfo;
	}
	
	private boolean validateAndPrepareOperation(OpOperation u, LocalValidationCtx ctx) {
		OperationDeleteInfo oin = getOperationInfo(u.getHash());
		boolean valid = true;
		if(oin != null) {
			return ctx.rules.error(ErrorType.OP_HASH_IS_DUPLICATED, u.getHash(), ctx.blockHash);
		}
		valid = prepareDeletedObjects(u, ctx);
		if(!valid) {
			return false;
		}
		valid = prepareReferencedObjects(u, ctx);
		if(!valid) {
			return valid;
		}
		valid = ctx.rules.validateOp(this, u, ctx.deletedObjsCache, ctx.refObjsCache);
		if(!valid) {
			return valid;
		}
		return true;
	}
	
	
	
	private boolean prepareReferencedObjects(OpOperation u, LocalValidationCtx ctx) {
		Map<String, List<String>> refs = u.getRef();
		Iterator<Entry<String, List<String>>> it = refs.entrySet().iterator();
		while(it.hasNext()) {
			Entry<String, List<String>> e = it.next();
			String refName = e.getKey();
			List<String> refObjName = e.getValue();
			OpObject oi = null;
			if(refObjName.size() > 1) {
				// type is necessary
				OpBlockChain blc = this;
				while (blc != null && oi == null) {
					oi = blc.getObjectByName(refObjName);
					blc = blc.parent;
				}
			}
			if(oi == null) {
				return ctx.rules.error(ErrorType.REF_OBJ_NOT_FOUND, u.getHash(), refObjName);
			}
			ctx.refObjsCache.put(refName, oi);
		}
		return true;
	}
	
	private boolean prepareDeletedObjects(OpOperation u, LocalValidationCtx ctx) {
		List<String> deletedRefs = u.getOld();
		ctx.deletedObjsCache.clear();
		ctx.deletedOpsCache.clear();
		for(int i = 0; i < deletedRefs.size(); i++) {
			String delRef = deletedRefs.get(i);
			String delHash = getHashFromAbsRef(delRef);
			int delInd = getIndexFromAbsRef(delRef);
			
			
			OperationDeleteInfo opInfo = getOperationInfo(delHash);
			
			if(opInfo == null || opInfo.op.getNew().size() <= delInd) {
				return ctx.rules.error(ErrorType.DEL_OBJ_NOT_FOUND, u.getHash(), delRef);
			}
			if(opInfo.deletedObjects != null || delInd < opInfo.deletedObjects.length){
				if(opInfo.deletedObjects[delInd]) {
					return ctx.rules.error(ErrorType.DEL_OBJ_DOUBLE_DELETED, u.getHash(), 
							delRef, opInfo.deletedObjects[delInd]);
				}
			}
			List<OpObject> nw = opInfo.op.getNew();
			ctx.deletedObjsCache.add(nw.get(delInd));
			ctx.deletedOpsCache.add(opInfo.op);
		}
		return true;
	}



	// no multi thread issue (used only in synchronized blocks)
	private static class LocalValidationCtx {
		Map<String, OpObject> refObjsCache = new HashMap<String, OpObject>();
		List<OpObject> deletedObjsCache = new ArrayList<OpObject>();
		List<OpOperation> deletedOpsCache = new ArrayList<OpOperation>();
		
		String blockHash;
		OpBlockchainRules rules;
	}

	private static class OperationDeleteInfo {
		private OpOperation op;
		private boolean create;
		private boolean[] deletedObjects;
	}


}

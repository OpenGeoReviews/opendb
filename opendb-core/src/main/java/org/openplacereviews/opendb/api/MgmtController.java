package org.openplacereviews.opendb.api ;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openplacereviews.opendb.ops.OpBlock;
import org.openplacereviews.opendb.service.BlocksManager;
import org.openplacereviews.opendb.service.LogOperationService;
import org.openplacereviews.opendb.service.SettingsManager;
import org.openplacereviews.opendb.util.JsonFormatter;
import org.openplacereviews.opendb.util.OUtils;
import org.openplacereviews.opendb.util.exception.FailedVerificationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.io.StringReader;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;


@Controller
@RequestMapping("/api/mgmt")
public class MgmtController {

	protected static final Log LOGGER = LogFactory.getLog(MgmtController.class);
    
    @Autowired
    private BlocksManager manager;
    
    @Autowired
    private LogOperationService logService;

    @Autowired
	private SettingsManager settingsManager;
    
    @Autowired
    private JsonFormatter formatter;
    
    public boolean validateServerLogin(HttpSession session) {
    	String loginName = (String) session.getAttribute(OpApiController.ADMIN_LOGIN_NAME);
    	return OUtils.equals(loginName, manager.getServerUser());
	}
    
    private KeyPair getServerLoginKeyPair(HttpSession session) {
    	return manager.getServerLoginKeyPair();
	}
    
    private String getServerUser(HttpSession session) {
    	return manager.getServerUser();
	}
    
    private ResponseEntity<String> unauthorizedByServer() {
    	return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
    			.body("{\"status\":\"ERROR\"}");
	}
    
    @PostMapping(path = "/create", produces = "text/json;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> createBlock(HttpSession session) throws FailedVerificationException {
    	if(!validateServerLogin(session)) {
    		return unauthorizedByServer();
    	}
    	OpBlock block = manager.createBlock();
    	if(block == null) {
    		return ResponseEntity.status(HttpStatus.BAD_REQUEST).
    				body("{\"status\":\"FAILED\", \"msg\":\"Block creation failed\"}");
    	}
    	return ResponseEntity.ok(formatter.fullObjectToJson(block));
    }
    
    @PostMapping(path = "/queue-clear")
    @ResponseBody
    public ResponseEntity<String> clearQueue(HttpSession session) {
    	if(!validateServerLogin(session)) {
    		return unauthorizedByServer();
    	}
    	manager.clearQueue();
        return ResponseEntity.ok("{\"status\":\"OK\"}");
    }
    
    @PostMapping(path = "/logs-clear")
    @ResponseBody
    public ResponseEntity<String> logsClear(HttpSession session) {
    	if(!validateServerLogin(session)) {
    		return unauthorizedByServer();
    	}
    	logService.clearLogs();
    	return ResponseEntity.ok("{\"status\":\"OK\"}");
    }
    
    
    @PostMapping(path = "/revert-superblock", produces = "text/json;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> revertSuperblock(HttpSession session) throws FailedVerificationException {
    	if(!validateServerLogin(session)) {
    		return unauthorizedByServer();
    	}
    	if(!manager.revertSuperblock()) {
    		return ResponseEntity.ok("{\"status\":\"FAILED\", \"msg\":\"Revert super block failed\"}");
    	}
    	return ResponseEntity.ok("{\"status\":\"OK\", \"msg\":\"Blocks are reverted and operations added to the queue.\"}");
    }
    
    @PostMapping(path = "/revert-1-block", produces = "text/json;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> revert1block(HttpSession session) throws FailedVerificationException {
    	if(!validateServerLogin(session)) {
    		return unauthorizedByServer();
    	}
    	if(!manager.revertOneBlock()) {
    		return ResponseEntity.ok("{\"status\":\"FAILED\", \"msg\":\"Revert block failed\"}");
    	}
    	return ResponseEntity.ok("{\"status\":\"OK\", \"msg\":\"Block isreverted and operations added to the queue.\"}");
    }
    
    @PostMapping(path = "/compact", produces = "text/json;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> compact(HttpSession session) throws FailedVerificationException {
    	if(!validateServerLogin(session)) {
    		return unauthorizedByServer();
    	}
    	if(!manager.compact()) {
    		return ResponseEntity.ok("{\"status\":\"FAILED\", \"msg\":\"Compacting blocks failed\"}");
    	}
    	return ResponseEntity.ok("{\"status\":\"OK\", \"msg\":\"Blocks are compacted.\"}");
    }
    
    @PostMapping(path = "/toggle-blockchain-pause", produces = "text/json;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> toggleOpsCreation(HttpSession session) {
    	if(!validateServerLogin(session)) {
    		return unauthorizedByServer();
    	}
    	if(manager.isBlockchainPaused()) {
    		manager.unlockBlockchain();
    	} else {
    		manager.lockBlockchain();
    	}
    	return ResponseEntity.ok("{\"status\":\"OK\"}");
    }
    
    @PostMapping(path = "/toggle-blocks-pause", produces = "text/json;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> toggleBlockCreation(HttpSession session) {
    	if(!validateServerLogin(session)) {
    		return unauthorizedByServer();
    	}
    	manager.setBlockCreationOn(!manager.isBlockCreationOn());
    	return ResponseEntity.ok("{\"status\":\"OK\"}");
    }
    
    @PostMapping(path = "/toggle-replicate-pause", produces = "text/json;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> toggleReplicateCreation(HttpSession session) {
    	if(!validateServerLogin(session)) {
    		return unauthorizedByServer();
    	}
    	manager.setReplicateOn(!manager.isReplicateOn());
    	return ResponseEntity.ok("{\"status\":\"OK\"}");
    }
    
    @PostMapping(path = "/replicate", produces = "text/json;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> replicate(HttpSession session) {
    	if(!validateServerLogin(session)) {
    		return unauthorizedByServer();
    	}
    	manager.replicate();
    	return ResponseEntity.ok("{\"status\":\"OK\"}");
    }
    
    @PostMapping(path = "/bootstrap", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> bootstrap(HttpSession session) throws Exception {
    	if(!validateServerLogin(session)) {
    		return unauthorizedByServer();
    	}
    	String serverName = getServerUser(session);
    	KeyPair serverLoginKeyPair = getServerLoginKeyPair(session);
    	manager.bootstrap(serverName, serverLoginKeyPair);
		return ResponseEntity.ok("{}");
    }
    
    
	@PostMapping(path = "/delete-orphaned-blocks", produces = "text/html;charset=UTF-8")
	@ResponseBody
	public ResponseEntity<String> deleteOrphanedBlocks(HttpSession session,
			@RequestParam(required = true) String blockListOrSingleValue) throws Exception {
		if (!validateServerLogin(session)) {
			return unauthorizedByServer();
		}
		String trimmedList = blockListOrSingleValue.trim();
		String[] blocks;
		if (trimmedList.startsWith("[")) {
			blocks = formatter.fromJson(new StringReader(blockListOrSingleValue), String[].class);
		} else {
			blocks = new String[] { trimmedList };
		}
		List<String> deleted = new ArrayList<>();
		for (String b : blocks) {
			if (manager.removeOrphanedBlock(b)) {
				deleted.add(b);
			}
		}
		return ResponseEntity.ok(formatter.fullObjectToJson(blocks));
	}
	
	@PostMapping(path = "/delete-queue-ops", produces = "text/html;charset=UTF-8")
	@ResponseBody
	public ResponseEntity<String> deleteQueueOperations(HttpSession session,
			@RequestParam(required = true) String opsListOrSingleValue) throws Exception {
		if (!validateServerLogin(session)) {
			return unauthorizedByServer();
		}
		String trimmedList = opsListOrSingleValue.trim();
		if (trimmedList.startsWith("[")) {
			trimmedList = trimmedList.substring(1, trimmedList.length() - 1);
		}
		Set<String> toDelete = new TreeSet<String>();
		for(String s : trimmedList.split(",")) {
			toDelete.add(s);
		}
		Set<String> removeQueueOperations = manager.removeQueueOperations(toDelete);
		return ResponseEntity.ok(formatter.fullObjectToJson(removeQueueOperations));
	}

	@GetMapping(path = "/config", produces = "text/html;charset=UTF-8")
	@ResponseBody
	public ResponseEntity<String> getAppPreferences(HttpSession session) {
		if (!validateServerLogin(session)) {
			return unauthorizedByServer();
		}

		return ResponseEntity.ok(formatter.fullObjectToJson(settingsManager.getPreferences()));
	}

	@PostMapping(path = "/config", produces = "text/html;charset=UTF-8")
	@ResponseBody
	public ResponseEntity<String> updatePreference(HttpSession session,
												   @RequestParam String key,
												   @RequestParam String value,
												   @RequestParam(required = false) String type,
												   @RequestParam Boolean restartIsNeeded) {
		if (!validateServerLogin(session)) {
			return unauthorizedByServer();
		}

		if (settingsManager.updatePreference(key, settingsManager.generateObjectByType(value, type), restartIsNeeded, true)) {
			settingsManager.savePreferences();
			return ResponseEntity.ok("Preference was update");
		} else {
			return ResponseEntity.ok("Preference type is not valid");
		}
	}

}
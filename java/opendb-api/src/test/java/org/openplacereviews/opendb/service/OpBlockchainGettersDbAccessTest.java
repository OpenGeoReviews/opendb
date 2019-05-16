package org.openplacereviews.opendb.service;

import org.junit.*;
import org.mockito.InjectMocks;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.openplacereviews.opendb.FailedVerificationException;
import org.openplacereviews.opendb.OpenDBServer;
import org.openplacereviews.opendb.ops.OpBlock;
import org.openplacereviews.opendb.ops.OpBlockChain;
import org.openplacereviews.opendb.ops.OpBlockchainGettersTest;
import org.openplacereviews.opendb.ops.OpOperation;
import org.openplacereviews.opendb.psql.PostgreSQLServer;
import org.openplacereviews.opendb.util.JsonFormatter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.SQLException;

import static org.mockito.ArgumentMatchers.any;
import static org.openplacereviews.opendb.ObjectGeneratorTest.generateMetadataDB;
import static org.openplacereviews.opendb.VariableHelperTest.serverKeyPair;
import static org.openplacereviews.opendb.VariableHelperTest.serverName;

public class OpBlockchainGettersDbAccessTest {

	@Spy
	@InjectMocks
	private DBConsensusManager dbConsensusManager;

	@Spy
	@InjectMocks
	private DBSchemaManager dbSchemaManager;

	@Spy
	@InjectMocks
	private FileBackupManager fileBackupManager;

	@Spy
	private JsonFormatter jsonFormatter;

	@ClassRule
	public static final PostgreSQLServer databaseServer = new PostgreSQLServer();

	private OpBlockchainGettersTest opBlockchainGettersTest;
	private JdbcTemplate jdbcTemplate;
	private OpenDBServer.MetadataDb metadataDb;

	@Before
	public void beforeEachTest() throws SQLException, FailedVerificationException {
		MockitoAnnotations.initMocks(this);

		Connection connection = databaseServer.getConnection();
		this.jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, false));
		this.opBlockchainGettersTest = new OpBlockchainGettersTest();
		this.opBlockchainGettersTest.beforeEachTestMethod();

		ReflectionTestUtils.setField(dbConsensusManager, "jdbcTemplate", jdbcTemplate);
		ReflectionTestUtils.setField(dbConsensusManager, "dbSchema", dbSchemaManager);
		ReflectionTestUtils.setField(dbConsensusManager, "backupManager", fileBackupManager);

		Mockito.doCallRealMethod().when(dbConsensusManager).insertBlock(any());
		Mockito.doNothing().when(fileBackupManager).init();

		metadataDb = new OpenDBServer.MetadataDb();
		generateMetadataDB(metadataDb, jdbcTemplate);
	}

	@After
	public void afterEachTest() throws Exception {
		databaseServer.wipeDatabase();
	}

	@AfterClass
	public static void afterClassTest() throws SQLException {
		databaseServer.getConnection().close();
	}

	@Test
	public void testGetLastBlockFullHashIfBlockExistWithDBAccess() throws FailedVerificationException {
		opBlockchainGettersTest.blc.createBlock(serverName, serverKeyPair);

		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetLastBlockFullHashIfBlockExist(blockChain);
	}

	@Test
	public void testGetLastBlockFullHashIfBlockIsNotExistWithDBAccess() {
		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetLastBlockFullHashIfBlockIsNotExist(blockChain);
	}

	@Test
	public void testGetLastBlockRawHashHashIfBlockExistWithDBAccess() throws FailedVerificationException {
		opBlockchainGettersTest.blc.createBlock(serverName, serverKeyPair);

		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetLastBlockRawHashHashIfBlockExist(blockChain);
	}

	@Test
	public void testGetLastBlockRawHashIfBlockIsNotExistWithDBAccess() {
		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetLastBlockRawHashIfBlockIsNotExist(blockChain);
	}

	@Test
	public void testGetSuperBlockHashWithDBAccess() throws FailedVerificationException {
		opBlockchainGettersTest.blc.createBlock(serverName, serverKeyPair);

		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetSuperBlockHash(blockChain);
	}

	@Test
	public void testGetSuperBlockHashIfSuperBlockWasNotCreatedWithDBAccess() {
		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetSuperBlockHashIfSuperBlockWasNotCreated(blockChain);
	}

	@Test
	public void testGetSuperBlockSizeWithDBAccess() throws FailedVerificationException {
		opBlockchainGettersTest.blc.createBlock(serverName, serverKeyPair);

		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetSuperBlockSize(blockChain);
	}

	@Test
	public void testGetSuperblockHeadersIfBlockWasNotCreatedWithDBAccess() {
		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetSuperblockHeadersIfBlockWasNotCreated(blockChain);
	}

	@Test
	public void testGetSuperblockHeadersWithDBAccess() throws FailedVerificationException {
		opBlockchainGettersTest.blc.createBlock(serverName, serverKeyPair);

		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetSuperblockHeaders(blockChain);
	}

	@Test
	public void testGetSuperblockFullBlocksIfBlockWasNotCreatedWithDBAccess() {
		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetSuperblockFullBlocksIfBlockWasNotCreated(blockChain);
	}

	@Test
	public void testGetSuperblockFullBlocksWithDBAccess() throws FailedVerificationException {
		OpBlock opBlock = opBlockchainGettersTest.blc.createBlock(serverName, serverKeyPair);
		opBlock.getOperations().forEach(opOperation -> dbConsensusManager.insertOperation(opOperation));
		dbConsensusManager.insertBlock(opBlock);

		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetSuperblockFullBlocks(blockChain);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void testGetSuperblockDeleteInfoWithDBAccess() throws FailedVerificationException {
		opBlockchainGettersTest.blc.createBlock(serverName, serverKeyPair);

		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetSuperblockDeleteInfo(blockChain);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void testGetSuperblockObjectsWithDBAccess() throws FailedVerificationException {
		opBlockchainGettersTest.blc.createBlock(serverName, serverKeyPair);

		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetSuperblockObjects(blockChain);
	}

	@Test
	public void testGetBlockHeadersWithDBAccess() throws FailedVerificationException {
		opBlockchainGettersTest.blc.createBlock(serverName, serverKeyPair);

		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetBlockHeaders(blockChain);
	}

	@Test
	public void testGetBlockHeadersByIdWithDBAccess() throws FailedVerificationException {
		OpBlock opBlock = opBlockchainGettersTest.blc.createBlock(serverName, serverKeyPair);

		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetBlockHeadersById(blockChain, opBlock);
	}

	@Test
	public void testGetBlockHeadersByNotExistingIdWithDBAccess() {
		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetBlockHeadersByNotExistingId(blockChain);
	}

	@Test
	public void testGetBlockHeaderByRawHashWithDBAccess() throws FailedVerificationException {
		OpBlock opBlock = opBlockchainGettersTest.blc.createBlock(serverName, serverKeyPair);

		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetBlockHeaderByRawHash(blockChain, opBlock);
	}

	@Test
	public void testGetBlockHeaderByNotExistingRawHashWithDBAccess() {
		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetBlockHeaderByNotExistingRawHash(blockChain);
	}

	@Test
	public void testGetFullBlockByRawHashWithDBAccess() throws FailedVerificationException {
		OpBlock opBlock = opBlockchainGettersTest.blc.createBlock(serverName, serverKeyPair);

		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetFullBlockByRawHash(blockChain, opBlock);
	}

	@Test
	public void testGetFullBlockByNotExistingRawHashWithDBAccess() {
		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetFullBlockByNotExistingRawHash(blockChain);
	}

	//TODO
	@Ignore
	@Test
	public void testGetOperationByHashWithDBAccess() throws FailedVerificationException {
		OpOperation opOperation = opBlockchainGettersTest.blc.getQueueOperations().getLast();

		OpBlock opBlock = opBlockchainGettersTest.blc.createBlock(serverName, serverKeyPair);
		opBlock.getOperations().forEach(opOperation1 -> dbConsensusManager.insertOperation(opOperation1));

		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetOperationByHash(blockChain, opOperation);
	}

	@Test
	public void testGetOperationByNotExistingHashWithDBAccess() {
		OpBlockChain blockChain = generateBlockchainWithDBAccess();

		opBlockchainGettersTest.testGetOperationByNotExistingHash(blockChain);
	}

	private OpBlockChain generateBlockchainWithDBAccess() {
		return new OpBlockChain(opBlockchainGettersTest.blc.getParent(), opBlockchainGettersTest.blc.getBlockHeaders(0), dbConsensusManager.createDbAccess(
				opBlockchainGettersTest.blc.getSuperBlockHash(), opBlockchainGettersTest.blc.getSuperblockHeaders()), opBlockchainGettersTest.blc.getRules());
	}

}

package org.openplacereviews.opendb;

import org.openplacereviews.opendb.api.MgmtController;
import org.openplacereviews.opendb.ops.OpBlock;
import org.openplacereviews.opendb.ops.OpBlockChain;
import org.openplacereviews.opendb.ops.OpBlockchainRules;
import org.openplacereviews.opendb.ops.OpOperation;
import org.openplacereviews.opendb.service.DBConsensusManager;
import org.openplacereviews.opendb.util.JsonFormatter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStreamReader;
import java.security.KeyPair;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.openplacereviews.opendb.VariableHelperTest.serverKeyPair;
import static org.openplacereviews.opendb.VariableHelperTest.serverName;

public class ObjectGeneratorTest {

	private static String[] BOOTSTRAP_LIST =
			new String[]{"opr-0-test-user", "std-ops-defintions", "std-roles", "opr-0-test-user-test", "opr-0-test-grant", "std-validations"};

	public static void generateOperations(JsonFormatter formatter, OpBlockChain blc) throws FailedVerificationException {
		for (String f : BOOTSTRAP_LIST) {
			OpOperation[] lst = formatter.fromJson(
					new InputStreamReader(MgmtController.class.getResourceAsStream("/bootstrap/" + f + ".json")),
					OpOperation[].class);
			for (OpOperation o : lst) {
				if (!OUtils.isEmpty(serverName) && o.getSignedBy().isEmpty()) {
					o.setSignedBy(serverName);
					o = blc.getRules().generateHashAndSign(o, serverKeyPair);
				}
				o.makeImmutable();
				blc.addOperation(o);
			}
		}
	}

	public static void generate30Blocks(JsonFormatter formatter, OpBlockChain blc, DBConsensusManager dbConsensusManager) throws FailedVerificationException {
		int i = 0;
		for (String f : BOOTSTRAP_LIST) {
			OpOperation[] lst = formatter.fromJson(
					new InputStreamReader(MgmtController.class.getResourceAsStream("/bootstrap/" + f + ".json")),
					OpOperation[].class);

			for (OpOperation o : lst) {
				if (!OUtils.isEmpty(serverName) && o.getSignedBy().isEmpty()) {
					o.setSignedBy(serverName);
					o = blc.getRules().generateHashAndSign(o, serverKeyPair);
				}
				o.makeImmutable();
				blc.addOperation(o);
				dbConsensusManager.insertOperation(o);
				if (i >= 3) {
					OpBlock opBlock = blc.createBlock(serverName, serverKeyPair);
					dbConsensusManager.insertBlock(opBlock);
				}
				i++;
			}
		}
	}

	public static void generateHashAndSignForOperation(OpOperation opOperation, OpBlockChain blc, boolean signedBy, KeyPair... keyPair) throws FailedVerificationException {
		if (signedBy) {
			opOperation.setSignedBy(serverName);
		}

		blc.getRules().generateHashAndSign(opOperation, keyPair);
	}

	/**
	 * Allows to generate JSON with big size for creating Error Type.OP_SIZE_IS_EXCEEDED
	 * @return - String Json
	 */
	public static String generateBigJSON() {
		StringBuilder startOperation =
				new StringBuilder(
						"{\n" +
						"\t\t\"type\" : \"sys.grant\",\n" +
						"\t\t\"ref\" : {\n" +
						"\t\t\t\"s\" : [\"sys.signup\",\"openplacereviews\"]\n" +
						"\t\t},\n" +
						"\t\t\"new\" : ["
				);

		while (startOperation.length() <= OpBlockchainRules.MAX_OP_SIZE_MB) {
			for(int i = 0; i < 100; i++) {
				startOperation
						.append("\t\t{ \n" + "\t\t\t\"id\" : [\"openplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviews")
						.append(startOperation.length())
						.append(i)
						.append("\"],\n")
						.append("\t\t\t\"roles\" : [\"owner\"]\n")
						.append("\t\t},");
			}
		}

		startOperation
				.append("\t\t{ \n" + "\t\t\t\"id\" : [\"openplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviewsopenplacereviews\"],\n" + "\t\t\t\"roles\" : [\"owner\"]\n" + "\t\t}]\n" + "\t}");

		return startOperation.toString();
	}

	public static void generateMetadataDB(OpenDBServer.MetadataDb metadataDb, JdbcTemplate jdbcTemplate) {
		try {
			DatabaseMetaData mt = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection().getMetaData();
			ResultSet rs = mt.getColumns(null, DBConstants.SCHEMA_NAME, null, null);
			while(rs.next()) {
				String tName = rs.getString("TABLE_NAME");
				if(!metadataDb.tablesSpec.containsKey(tName)) {
					metadataDb.tablesSpec.put(tName, new ArrayList<>());
				}
				List<OpenDBServer.MetadataColumnSpec> cols = metadataDb.tablesSpec.get(tName);
				OpenDBServer.MetadataColumnSpec spec = new OpenDBServer.MetadataColumnSpec();
				spec.columnName = rs.getString("COLUMN_NAME");
				spec.sqlType = rs.getInt("DATA_TYPE");
				spec.dataType = rs.getString("TYPE_NAME");
				spec.columnSize = rs.getInt("COLUMN_SIZE");
				cols.add(spec);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Can't read db metadata", e);
		}
	}
}

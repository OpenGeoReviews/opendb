package org.openplacereviews.opendb.service.ipfs.file;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openplacereviews.opendb.service.ipfs.storage.ImageDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

@Service
public class IPFSFileManager {

	protected static final Log LOGGER = LogFactory.getLog(IPFSFileManager.class);

	@Value("${ipfs.directory:/opendb/storage/}")
	public String DIRECTORY;

	public IPFSFileManager() {

	}

	public void init() {
		try {
			Files.createDirectories(Paths.get(getRootDirectoryPath()));
			LOGGER.debug("IPFS directory for images was created");
		} catch (IOException e) {
			e.printStackTrace();
			LOGGER.error("IPFS directory for images was not created");
		}
	}

	/**
	 * @return root directory for images.
	 */
	public String getRootDirectoryPath() {
		return System.getProperty("user.home") + "/" + DIRECTORY;
	}

	public void addFileToStorage(ImageDTO imageDTO) throws IOException {
		File file = new File(getRootDirectoryPath() + generateFileName(imageDTO.getCid(), imageDTO.getExtension()));

		FileUtils.writeByteArrayToFile(file, imageDTO.getMultipartFile().getBytes());
	}

	public String generateFileName(String hash, String ext) {
		StringBuilder fPath = generateDirectoryHierarchy(hash);
		fPath.append(hash).append(".").append(ext);

		return fPath.toString();
	}

	private StringBuilder generateDirectoryHierarchy(String hash) {
		byte[] bytes = hash.getBytes();
		StringBuilder fPath = new StringBuilder();

		for(int i = 0; i < 12; i+=4) {
			fPath.append(new String(bytes, i, 4)).append("/");
		}

		try {
			Files.createDirectories(Paths.get(getRootDirectoryPath() + fPath.toString()));
			LOGGER.debug("Image directory for cid : " + hash + " was created");
		} catch (IOException e) {
			e.printStackTrace();
			LOGGER.error("Image directory was not created");
		}

		return fPath;
	}

}

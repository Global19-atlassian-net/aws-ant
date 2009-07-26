/*
 * Copyright 2007-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.build.aws.ant;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.FileSet;
import org.jets3t.service.S3Service;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;

/**
 * A member of the S3 ANT task for dealing with Amazon S3 download behavior.
 * This operation will use the credentials setup in its parent S3 task tag.
 * 
 * @author Ben Hale
 */
public class Download extends AbstractS3Operation {

	private static final int BUFFER_SIZE = 64 * 1024;

	private String file;

	private List<FileSet> fileSets = new ArrayList<FileSet>(1);

	private File toDir;

	private File toFile;

	/**
	 * Optional parameter that corresponds to the source object key in S3
	 * @param file The source object key in S3
	 */
	public void setFile(String file) {
		this.file = file;
	}

	/**
	 * Adds an optional fileSet to read files from.
	 * @param fileSet The set of files to download
	 */
	public void addFileSet(FileSet fileSet) {
		fileSets.add(fileSet);
	}

	/**
	 * Optional parameter that corresponds to the target object directory
	 * @param toDir The target object directory
	 */
	public void setToDir(File toDir) {
		this.toDir = toDir;
	}

	/**
	 * Required parameter that corresponds to the file to download
	 * @param toFile The file to download
	 */
	public void setToFile(File toFile) {
		this.toFile = toFile;
	}

	/**
	 * Verify that required parameters have been set
	 */
	public void init() {
		if (bucketName == null) {
			throw new BuildException("bucketName must be set");
		}
		if (file != null && fileSets.size() > 0) {
			throw new BuildException("Only one of file and <fileset> may be set");
		}
		if (file == null && fileSets.size() == 0) {
			throw new BuildException("At least one of file and <fileset> must be set");
		}
		if (toFile != null && toDir != null) {
			throw new BuildException("Only one of toFile and toDir may be set");
		}
		if (toFile == null && toDir == null) {
			throw new BuildException("At least one of toFile and toDir must be set");
		}
		if (fileSets.size() > 0 && toFile != null) {
			throw new BuildException("toFile cannot be used when specifying a <fileset> to download");
		}
	}

	public void execute(S3Service service) throws S3ServiceException, IOException {
		if (file != null && toFile != null) {
			processFileToFile(service);
		}
		else if (file != null && toDir != null) {
			processFileToDir(service);
		}
		else if (fileSets.size() > 0 && toDir != null) {
			processSetToDir(service);
		}
	}

	private void processFileToFile(S3Service service) throws S3ServiceException, IOException {
		getFile(service, getOperationBucket(), file, toFile);
	}

	private void processFileToDir(S3Service service) throws S3ServiceException, IOException {
		getFile(service, getOperationBucket(), file, new File(toDir, file.substring(file.lastIndexOf('/'))));
	}

	private void processSetToDir(S3Service service) throws S3ServiceException, IOException {
		S3Bucket bucket = getOperationBucket();
		for (FileSet fileSet : fileSets) {
			S3Scanner scanner = getS3Scanner(bucket, fileSet.mergePatterns(project), getS3SafeDirectory(fileSet
					.getDir()));
			List<String> keys = scanner.getQualifiyingKeys(service);
			for (String key : keys) {
				if (!key.endsWith("/")) {
					getFile(service, bucket, key, new File(toDir, key.substring(getS3SafeDirectory(fileSet.getDir())
							.length())));
				}
			}
		}
	}

	private void getFile(S3Service service, S3Bucket bucket, String key, File destination) throws S3ServiceException,
			IOException {
		InputStream in = null;
		OutputStream out = null;
		try {
			if (!destination.getParentFile().exists()) {
				destination.getParentFile().mkdirs();
			}

			S3Object source = service.getObject(bucket, key);
			in = source.getDataInputStream();
			out = new FileOutputStream(destination);

			logStart(source, destination);
			long startTime = System.currentTimeMillis();
			byte[] buffer = new byte[BUFFER_SIZE];
			int length;
			while ((length = in.read(buffer)) != -1) {
				out.write(buffer, 0, length);
			}
			long endTime = System.currentTimeMillis();
			logEnd(source, startTime, endTime);
		}
		finally {
			if (in != null) {
				try {
					in.close();
				}
				catch (IOException e) {
					// Nothing to do at this point
				}
			}
			if (out != null) {
				try {
					out.close();
				}
				catch (IOException e) {
					// Nothing to do at this point
				}
			}
		}
	}

	private void logStart(S3Object source, File destination) throws IOException {
		project.log("Downloading s3://" + source.getBucketName() + "/" + source.getKey() + " ("
				+ TransferUtils.getFormattedSize(source.getContentLength()) + ") to " + destination.getCanonicalPath(),
				Project.MSG_INFO);
	}

	private void logEnd(S3Object source, long startTime, long endTime) {
		long transferTime = endTime - startTime;
		project.log("Transfer Time: " + TransferUtils.getFormattedTime(transferTime) + " - Transfer Rate: "
				+ TransferUtils.getFormattedSpeed(source.getContentLength(), transferTime), Project.MSG_INFO);
	}
}
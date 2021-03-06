package com.microsoft.azure.datalake.store;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.microsoft.azure.datalake.store.UploadJob.JobType;;
/*
 * Enumerates the given Directory and populates the upload jobs
 */

class EnumerateFile implements Runnable {
	private static final Logger log = LoggerFactory.getLogger("com.microsoft.azure.datalake.store.FileUploader");
	private ProcessingQueue<MetaData> metaDataQ;
	private ConsumerQueue<UploadJob> jobQ;
	private static int chunkSize = 256 * 1024 * 1024; // 256 MB
	private static int threshhold = 356 * 1024 * 1024; // 356 MB
	private long bytesToUpload;
	
	EnumerateFile(File srcDir, String destination, ProcessingQueue<MetaData> metaDataQ, ConsumerQueue<UploadJob> jobQ) {
		this.metaDataQ = metaDataQ;
		this.jobQ = jobQ;
		int size = AdlsTool.getChunkSize(chunkSize);
		if(size != chunkSize) {
			chunkSize = size;
			threshhold = size;
		}
		metaDataQ.add(new MetaData(srcDir, destination));
	}
	public void run() {
		MetaData front;
		while((front = metaDataQ.poll()) != null) {
			try {
				File source = front.sourceFile;
				if(source.isDirectory()) {
					File[] subDir = source.listFiles();
					if(subDir != null) {
						String dstPrefix = front.getDestinationFinalPath();
						for(File sub : subDir) {
							metaDataQ.add(new MetaData(sub, dstPrefix));
						}
						if(subDir.length == 0) {
							generateMkDirJob(front);
						}
					}
				} else if(source.isFile()) {
					generateUploadJob(front);
				}
			} catch (Exception e) {
				log.error(e.getMessage());
			} finally {
				metaDataQ.unregister();
			}
		}
	}
	
	private void generateMkDirJob(MetaData front) {
		jobQ.add(new UploadJob(front, 0, 0, 0, JobType.MKDIR));
	}
	
	private void generateUploadJob(MetaData front) {
		long size = 0, chunks = 0, offset = 0;
		do {
			if(front.size() - offset <= threshhold) {
				size = front.size() - offset;
			} else {
				size = chunkSize;
			}
			jobQ.add(new UploadJob(front, offset, size, chunks, JobType.FILEUPLOAD));
			chunks++;
			offset += size;
		} while(offset < front.size());
		log.debug("Generated " + front.splits + " number of upload jobs for file " 
				+ front.getSourceFilePath() + " with destination " + front.getDestinationIntermediatePath());
		bytesToUpload += front.size();
	}
	
	public long getBytesToUpload() {
		return bytesToUpload;
	}
	
	static long getNumberOfFileChunks(long size) {
		if(size <= threshhold) {
			return 1;
		}
		long chunks = 0;
		if(size%chunkSize <= (threshhold-chunkSize)) {
			chunks = size/chunkSize;
		} else {
			chunks = (long)Math.ceil(1.0*size/chunkSize);
		}
		return chunks;
	}
}
package com.microsoft.azure.datalake.store;


import java.io.File;
import java.io.IOException;
import java.util.PriorityQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.azure.datalake.store.ADLStoreClient;
import com.microsoft.azure.datalake.store.DirectoryEntry;
import com.microsoft.azure.datalake.store.DirectoryEntryType;


public class FileUploader {
	private static final Logger log = LoggerFactory.getLogger("com.microsoft.azure.datalake.store.FileUploader");
	private static int uploaderThreadCount;
	private ProcessingQueue<MetaData> metaDataQ;
	private ConsumerQueue<UploadJob> jobQ;
	private ADLStoreClient client;
	private Thread[] executorThreads;
	private JobExecutor[] executor;
	private IfExists overwrite;
	private EnumerateFile jobGen;
	
	public FileUploader(ADLStoreClient client, IfExists overwriteOption) {
		metaDataQ = new ProcessingQueue<MetaData>();
		jobQ = new ConsumerQueue<UploadJob>(new PriorityQueue<UploadJob>());
		uploaderThreadCount = AdlsTool.threadSetup();
		this.client = client;
		this.overwrite = overwriteOption;
	}
	
	/*
	 * Uploads the given source dir/file to a directory on ADLS.
	 * @param source Local Source directory or file to upload from.
	 * @param destination Destination directory to copy the files to.
	 * @param client ADLStoreClient to use to upload the file.
	 */
	public static UploadResult upload(String source, String destination, ADLStoreClient client, IfExists overwriteOption) throws IOException, InterruptedException {
		FileUploader F = new FileUploader(client, overwriteOption);
		return F.uploadInternal(source, destination);
	}
	
	private UploadResult uploadInternal(String source, String destination) throws InterruptedException, IOException {
		if(source == null) {
			throw new IllegalArgumentException("source is null");
		} else if(destination == null) {
			throw new IllegalArgumentException("destination is null");
		}
		
		source = source.trim();
		destination = destination.trim();
		if(source.isEmpty()) {
			throw new IllegalArgumentException("source is empty");
		} else if(destination.isEmpty()) {
			throw new IllegalArgumentException("destination is empty");
		}
		
		File srcDir = new File(source);
		if(!srcDir.exists()) {
			throw new IllegalArgumentException("Source doesn't exist");
		}
		
		if(!isDirectory(srcDir)) {
			if(!verifyDestination(destination)) {
				return new UploadResult();
			}
		}
		
		return upload(srcDir, destination);
	}
	
	private static boolean isDirectory(File inFile) {
		return inFile.listFiles() != null;
	}
	
	private void startUploaderThreads(ConsumerQueue<UploadJob> jobQ) {
		executorThreads = new Thread[uploaderThreadCount];
		executor = new JobExecutor[uploaderThreadCount];
		for(int i = 0; i < executorThreads.length; i++) {
			executor[i] = new JobExecutor(jobQ, client, overwrite);
			executorThreads[i] = new Thread(executor[i]);
			executorThreads[i].start();
		}
	}
	
	private Thread startEnumeration(File source, String destination) {
		jobGen = new EnumerateFile(source, destination, metaDataQ, jobQ);
		Thread t = new Thread(jobGen);
		t.start();
		return t;
	}
	
	private UploadResult upload(File source, String destination) throws IOException, InterruptedException {
		Thread generateJob = startEnumeration(source, destination);
		startUploaderThreads(jobQ);
		Thread statusThread = waitForCompletion(generateJob);
		UploadResult R = joinUploaderThreads();
		statusThread.interrupt();
		return R;
	}
	
	private Thread waitForCompletion(Thread generateJob) throws InterruptedException {
		generateJob.join();
		jobQ.markComplete(); // Consumer threads wait until enumeration is active.
		StatusBar statusBar = new StatusBar(jobGen.getBytesToUpload(), executor);
		Thread status = new Thread(statusBar);  // start a status bar.
		status.start();
		return status;
	}
	
	private UploadResult joinUploaderThreads() throws InterruptedException {
		UploadResult result = new UploadResult();
		for(int i = 0; i < executorThreads.length; i++) {
			executorThreads[i].join();
			result.update(executor[i].stats);
		}
		return result;
	}

	private boolean verifyDestination(String dst) throws InterruptedException {
		DirectoryEntry D = null;
		try {
			D = client.getDirectoryEntry(dst);
		} catch (IOException e) {
			log.debug("Destination directory doesn't exists, will be created");
		}
		if(D != null && D.type != DirectoryEntryType.DIRECTORY) {
			log.error("Destination path points to a file");
			throw new IllegalArgumentException("Destination path points to a file. Please provide a directory");
		}
		return true;
	}
	
	class StatusBar implements Runnable {
		private long totalBytesToUpload;
		private JobExecutor[] uploaders;
		private static final long sleepTime = 500;
		StatusBar(long bytesToUpload, JobExecutor[] uploaders) {
			totalBytesToUpload = bytesToUpload;
			this.uploaders = uploaders;
		}
		public void run() {
			int percent = 0;
			while(percent < 100) {
				try {
					Thread.sleep(sleepTime);
				} catch (InterruptedException e) {
					break;
				}
				long bytesUploaded = 0;
				for(int i = 0; i < uploaders.length; i++) {
					bytesUploaded += executor[i].stats.getBytesUploaded();
				}
				percent = (int) ((100.0*bytesUploaded)/totalBytesToUpload);
				System.out.printf("%% Uploaded: %d\r", percent);
			}
		}
		
	}
}

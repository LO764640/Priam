/*
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.priam.backup;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.netflix.priam.backup.AbstractBackupPath.BackupFileType;
import com.netflix.priam.config.IConfiguration;
import com.netflix.priam.scheduler.Task;
import com.netflix.priam.utils.SystemUtils;
import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Abstract Backup class for uploading files to backup location */
public abstract class AbstractBackup extends Task {
    private static final Logger logger = LoggerFactory.getLogger(AbstractBackup.class);
    static final String INCREMENTAL_BACKUP_FOLDER = "backups";
    public static final String SNAPSHOT_FOLDER = "snapshots";

    protected final Provider<AbstractBackupPath> pathFactory;

    protected IBackupFileSystem fs;

    @Inject
    public AbstractBackup(
            IConfiguration config,
            IFileSystemContext backupFileSystemCtx,
            Provider<AbstractBackupPath> pathFactory) {
        super(config);
        this.pathFactory = pathFactory;
        this.fs = backupFileSystemCtx.getFileStrategy(config);
    }

    /** A means to override the type of backup strategy chosen via BackupFileSystemContext */
    protected void setFileSystem(IBackupFileSystem fs) {
        this.fs = fs;
    }

    private AbstractBackupPath getAbstractBackupPath(final File file, final BackupFileType type)
            throws ParseException {
        final AbstractBackupPath bp = pathFactory.get();
        bp.parseLocal(file, type);
        return bp;
    }

    /**
     * Upload files in the specified dir. Does not delete the file in case of error. The files are
     * uploaded serially or async based on flag provided.
     *
     * @param parent Parent dir
     * @param type Type of file (META, SST, SNAP etc)
     * @param async Upload the file(s) in async fashion if enabled.
     * @param waitForCompletion wait for completion for all files to upload if using async API. If
     *     `false` it will queue the files and return with no guarantee to upload.
     * @return List of files that are successfully uploaded as part of backup
     * @throws Exception when there is failure in uploading files.
     */
    protected List<AbstractBackupPath> upload(
            final File parent, final BackupFileType type, boolean async, boolean waitForCompletion)
            throws Exception {
        final List<AbstractBackupPath> bps = Lists.newArrayList();
        final List<Future<Path>> futures = Lists.newArrayList();

        File[] files = parent.listFiles();
        if (files == null) return bps;

        for (File file : files) {
            if (file.isFile() && file.exists()) {
                AbstractBackupPath bp = getAbstractBackupPath(file, type);

                if (async)
                    futures.add(
                            fs.asyncUploadFile(
                                    Paths.get(bp.getBackupFile().getAbsolutePath()),
                                    Paths.get(bp.getRemotePath()),
                                    bp,
                                    10,
                                    true));
                else
                    fs.uploadFile(
                            Paths.get(bp.getBackupFile().getAbsolutePath()),
                            Paths.get(bp.getRemotePath()),
                            bp,
                            10,
                            true);

                bps.add(bp);
            }
        }

        // Wait for all files to be uploaded.
        if (async && waitForCompletion) {
            for (Future future : futures)
                future.get(); // This might throw exception if there is any error
        }

        return bps;
    }

    protected final void initiateBackup(
            String monitoringFolder, BackupRestoreUtil backupRestoreUtil) throws Exception {

        File dataDir = new File(config.getDataFileLocation());
        if (!dataDir.exists() || !dataDir.isDirectory()) {
            throw new IllegalArgumentException(
                    "The configured 'data file location' does not exist or is not a directory: "
                            + config.getDataFileLocation());
        }
        logger.debug("Scanning for backup in: {}", dataDir.getAbsolutePath());
        File[] keyspaceDirectories = dataDir.listFiles();
        if (keyspaceDirectories == null) return;

        for (File keyspaceDir : keyspaceDirectories) {
            if (keyspaceDir.isFile()) continue;

            logger.debug("Entering {} keyspace..", keyspaceDir.getName());
            File[] columnFamilyDirectories = keyspaceDir.listFiles();
            if (columnFamilyDirectories == null) continue;

            for (File columnFamilyDir : columnFamilyDirectories) {
                File backupDir = new File(columnFamilyDir, monitoringFolder);
                if (backupDir.exists() && backupDir.isDirectory() && backupDir.canRead()) {
                    String columnFamilyName = columnFamilyDir.getName().split("-")[0];
                    if (backupRestoreUtil.isFiltered(keyspaceDir.getName(), columnFamilyName)) {
                        // Clean the backup/snapshot directory else files will keep getting
                        // accumulated.
                        SystemUtils.cleanupDir(backupDir.getAbsolutePath(), null);
                    } else {
                        processColumnFamily(keyspaceDir.getName(), columnFamilyName, backupDir);
                    }
                }
            } // end processing all CFs for keyspace
        } // end processing keyspaces under the C* data dir
    }

    /**
     * Process the columnfamily in a given snapshot/backup directory.
     *
     * @param keyspace Name of the keyspace
     * @param columnFamily Name of the columnfamily
     * @param backupDir Location of the backup/snapshot directory in that columnfamily.
     * @throws Exception throws exception if there is any error in process the directory.
     */
    protected abstract void processColumnFamily(
            String keyspace, String columnFamily, File backupDir) throws Exception;

    /**
     * Get all the backup directories for Cassandra.
     *
     * @param config to get the location of the data folder.
     * @param monitoringFolder folder where cassandra backup's are configured.
     * @return Set of the path(s) containing the backup folder for each columnfamily.
     * @throws Exception incase of IOException.
     */
    public static Set<Path> getBackupDirectories(IConfiguration config, String monitoringFolder)
            throws Exception {
        HashSet<Path> backupPaths = new HashSet<>();
        if (config.getDataFileLocation() == null) return backupPaths;
        Path dataPath = Paths.get(config.getDataFileLocation());
        if (Files.exists(dataPath) && Files.isDirectory(dataPath))
            try (DirectoryStream<Path> directoryStream =
                    Files.newDirectoryStream(dataPath, path -> Files.isDirectory(path))) {
                for (Path keyspaceDirPath : directoryStream) {
                    try (DirectoryStream<Path> keyspaceStream =
                            Files.newDirectoryStream(
                                    keyspaceDirPath, path -> Files.isDirectory(path))) {
                        for (Path columnfamilyDirPath : keyspaceStream) {
                            Path backupDirPath =
                                    Paths.get(columnfamilyDirPath.toString(), monitoringFolder);
                            if (Files.exists(backupDirPath) && Files.isDirectory(backupDirPath)) {
                                logger.debug("Backup folder: {}", backupDirPath);
                                backupPaths.add(backupDirPath);
                            }
                        }
                    }
                }
            }
        return backupPaths;
    }
}

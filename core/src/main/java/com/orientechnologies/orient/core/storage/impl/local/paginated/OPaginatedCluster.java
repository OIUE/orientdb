/*
 * Copyright 2010-2013 OrientDB LTD (info--at--orientdb.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.storage.impl.local.paginated;

import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.io.OFileUtils;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.compression.OCompression;
import com.orientechnologies.orient.core.compression.OCompressionFactory;
import com.orientechnologies.orient.core.config.OContextConfiguration;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.config.OStorageClusterConfiguration;
import com.orientechnologies.orient.core.config.OStoragePaginatedClusterConfiguration;
import com.orientechnologies.orient.core.conflict.ORecordConflictStrategy;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.encryption.OEncryption;
import com.orientechnologies.orient.core.encryption.OEncryptionFactory;
import com.orientechnologies.orient.core.exception.OPaginatedClusterException;
import com.orientechnologies.orient.core.exception.ORecordNotFoundException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.metadata.OMetadataInternal;
import com.orientechnologies.orient.core.storage.*;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperation;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurableComponent;
import com.orientechnologies.orient.core.storage.impl.local.statistic.OSessionStoragePerformanceStatistic;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.orientechnologies.orient.core.config.OGlobalConfiguration.DISK_CACHE_PAGE_SIZE;
import static com.orientechnologies.orient.core.config.OGlobalConfiguration.PAGINATED_STORAGE_LOWEST_FREELIST_BOUNDARY;

/**
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 10/7/13
 */
public class OPaginatedCluster extends ODurableComponent implements OCluster {

  public enum RECORD_STATUS {
    NOT_EXISTENT, PRESENT, ALLOCATED, REMOVED
  }

  private final boolean addRidMetadata = OGlobalConfiguration.STORAGE_TRACK_CHANGED_RECORDS_IN_WAL.getValueAsBoolean();

  public static final  String DEF_EXTENSION            = ".pcl";
  private static final int    DISK_PAGE_SIZE           = DISK_CACHE_PAGE_SIZE.getValueAsInteger();
  private static final int    LOWEST_FREELIST_BOUNDARY = PAGINATED_STORAGE_LOWEST_FREELIST_BOUNDARY.getValueAsInteger();
  private final static int    FREE_LIST_SIZE           = DISK_PAGE_SIZE - LOWEST_FREELIST_BOUNDARY;
  private static final int    PAGE_INDEX_OFFSET        = 16;
  private static final int    RECORD_POSITION_MASK     = 0xFFFF;
  private static final int    ONE_KB                   = 1024;

  private volatile OCompression                          compression;
  private volatile OEncryption                           encryption;
  private final    boolean                               systemCluster;
  private          OClusterPositionMap                   clusterPositionMap;
  private          OAbstractPaginatedStorage             storageLocal;
  private volatile int                                   id;
  private          long                                  fileId;
  private          OStoragePaginatedClusterConfiguration config;
  private          long                                  pinnedStateEntryIndex;
  private          ORecordConflictStrategy               recordConflictStrategy;

  private static final class AddEntryResult {
    private final long pageIndex;
    private final int  pagePosition;

    private final int recordVersion;
    private final int recordsSizeDiff;

    public AddEntryResult(long pageIndex, int pagePosition, int recordVersion, int recordsSizeDiff) {
      this.pageIndex = pageIndex;
      this.pagePosition = pagePosition;
      this.recordVersion = recordVersion;
      this.recordsSizeDiff = recordsSizeDiff;
    }
  }

  private static final class FindFreePageResult {
    private final long pageIndex;
    private final int  freePageIndex;

    private FindFreePageResult(long pageIndex, int freePageIndex) {
      this.pageIndex = pageIndex;
      this.freePageIndex = freePageIndex;
    }
  }

  public OPaginatedCluster(final String name, final OAbstractPaginatedStorage storage) {
    super(storage, name, ".pcl", name + ".pcl");

    systemCluster = OMetadataInternal.SYSTEM_CLUSTER.contains(name);
  }

  @Override
  public void configure(final OStorage storage, final int id, final String clusterName, final Object... parameters)
      throws IOException {
    startOperation();
    try {
      acquireExclusiveLock();
      try {
        final OContextConfiguration ctxCfg = storage.getConfiguration().getContextConfiguration();
        final String cfgCompression = ctxCfg.getValueAsString(OGlobalConfiguration.STORAGE_COMPRESSION_METHOD);
        final String cfgEncryption = ctxCfg.getValueAsString(OGlobalConfiguration.STORAGE_ENCRYPTION_METHOD);
        final String cfgEncryptionKey = ctxCfg.getValueAsString(OGlobalConfiguration.STORAGE_ENCRYPTION_KEY);

        config = new OStoragePaginatedClusterConfiguration(storage.getConfiguration(), id, clusterName, null, true,
            OStoragePaginatedClusterConfiguration.DEFAULT_GROW_FACTOR, OStoragePaginatedClusterConfiguration.DEFAULT_GROW_FACTOR,
            cfgCompression, cfgEncryption, cfgEncryptionKey, null, OStorageClusterConfiguration.STATUS.ONLINE);
        config.name = clusterName;

        init((OAbstractPaginatedStorage) storage, config);
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public void configure(final OStorage storage, final OStorageClusterConfiguration config) throws IOException {
    acquireExclusiveLock();
    try {
      init((OAbstractPaginatedStorage) storage, config);
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public boolean exists() {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          final OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
          return isFileExists(atomicOperation, getFullName());
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public void create(final int startSize) throws IOException {
    startOperation();
    try {
      final OAtomicOperation atomicOperation = startAtomicOperation(false);
      acquireExclusiveLock();
      try {
        fileId = addFile(atomicOperation, getFullName());

        initCusterState(atomicOperation);

        if (config.root.clusters.size() <= config.id)
          config.root.clusters.add(config);
        else
          config.root.clusters.set(config.id, config);

        clusterPositionMap.create();

        endAtomicOperation(false, null);
      } catch (Exception e) {
        endAtomicOperation(true, e);
        throw OException
            .wrapException(new OPaginatedClusterException("Error during creation of cluster with name " + getName(), this), e);
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public void open() throws IOException {
    startOperation();
    try {
      acquireExclusiveLock();
      try {
        final OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
        fileId = openFile(atomicOperation, getFullName());

        final OCacheEntry pinnedStateEntry = loadPageForRead(atomicOperation, fileId, 0, false);
        try {
          pinPage(atomicOperation, pinnedStateEntry);
          pinnedStateEntryIndex = pinnedStateEntry.getPageIndex();
        } finally {
          releasePageFromRead(atomicOperation, pinnedStateEntry);
        }

        clusterPositionMap.open();
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      completeOperation();
    }
  }

  public void replaceFile(File file) throws IOException {
    startOperation();
    try {
      acquireExclusiveLock();
      try {
        final String tempFileName = file.getName() + "$temp";
        try {
          final long tempFileId = writeCache.addFile(tempFileName);
          writeCache.replaceFileContentWith(tempFileId, file.toPath());

          readCache.deleteFile(fileId, writeCache);
          writeCache.renameFile(tempFileId, getFullName());
          fileId = tempFileId;
        } finally {
          // If, for some reason, the temp file is still exists, wipe it out.

          final long tempFileId = writeCache.fileIdByName(tempFileName);
          if (tempFileId >= 0)
            writeCache.deleteFile(tempFileId);
        }
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      completeOperation();
    }
  }

  public void replaceClusterMapFile(File file) throws IOException {
    startOperation();
    try {
      acquireExclusiveLock();
      try {
        final String tempFileName = file.getName() + "$temp";
        try {
          final long tempFileId = writeCache.addFile(tempFileName);
          writeCache.replaceFileContentWith(tempFileId, file.toPath());

          readCache.deleteFile(clusterPositionMap.getFileId(), writeCache);
          writeCache.renameFile(tempFileId, clusterPositionMap.getFullName());
          clusterPositionMap.replaceFileId(tempFileId);
        } finally {
          final long tempFileId = writeCache.fileIdByName(tempFileName);
          if (tempFileId >= 0)
            writeCache.deleteFile(tempFileId);
        }
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public void close() throws IOException {
    close(true);
  }

  @Override
  public void close(final boolean flush) throws IOException {
    startOperation();
    try {
      acquireExclusiveLock();
      try {
        if (flush)
          synch();

        readCache.closeFile(fileId, flush, writeCache);
        clusterPositionMap.close(flush);
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public void delete() throws IOException {
    startOperation();
    try {
      final OAtomicOperation atomicOperation = startAtomicOperation(false);
      acquireExclusiveLock();
      try {
        deleteFile(atomicOperation, fileId);

        clusterPositionMap.delete();

        endAtomicOperation(false, null);
      } catch (IOException ioe) {
        endAtomicOperation(true, ioe);

        throw ioe;
      } catch (Exception e) {
        endAtomicOperation(true, e);

        throw OException.wrapException(new OPaginatedClusterException("Error during deletion of cluster " + getName(), this), e);
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public Object set(final OCluster.ATTRIBUTES attribute, final Object value) throws IOException {
    startOperation();
    try {
      if (attribute == null)
        throw new IllegalArgumentException("attribute is null");

      final String stringValue = value != null ? value.toString() : null;

      acquireExclusiveLock();
      try {

        switch (attribute) {
        case NAME:
          setNameInternal(stringValue);
          break;
        case RECORD_GROW_FACTOR:
          setRecordGrowFactorInternal(stringValue);
          break;
        case RECORD_OVERFLOW_GROW_FACTOR:
          setRecordOverflowGrowFactorInternal(stringValue);
          break;
        case CONFLICTSTRATEGY:
          setRecordConflictStrategy(stringValue);
          break;
        case STATUS: {
          if (stringValue == null)
            throw new IllegalStateException("Value of attribute is null");

          return storageLocal.setClusterStatus(id, OStorageClusterConfiguration.STATUS
              .valueOf(stringValue.toUpperCase(storageLocal.getConfiguration().getLocaleInstance())));
        }
        case ENCRYPTION:
          if (getEntries() > 0)
            throw new IllegalArgumentException(
                "Cannot change encryption setting on cluster '" + getName() + "' because it is not empty");
          setEncryptionInternal(stringValue,
              ODatabaseRecordThreadLocal.instance().get().getStorage().getConfiguration().getContextConfiguration()
                  .getValueAsString(OGlobalConfiguration.STORAGE_ENCRYPTION_KEY));
          break;
        default:
          throw new IllegalArgumentException("Runtime change of attribute '" + attribute + " is not supported");
        }

      } finally {
        releaseExclusiveLock();
      }

      return null;
    } finally {
      completeOperation();
    }
  }

  @Override
  public boolean isSystemCluster() {
    return systemCluster;
  }

  @Override
  public float recordGrowFactor() {
    acquireSharedLock();
    try {
      return config.recordGrowFactor;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public float recordOverflowGrowFactor() {
    acquireSharedLock();
    try {
      return config.recordOverflowGrowFactor;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public String compression() {
    acquireSharedLock();
    try {
      return config.compression;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public String encryption() {
    acquireSharedLock();
    try {
      return config.encryption;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public OPhysicalPosition allocatePosition(byte recordType) throws IOException {
    startOperation();
    try {
      OAtomicOperation atomicOperation = startAtomicOperation(true);
      acquireExclusiveLock();
      try {
        final OPhysicalPosition pos = createPhysicalPosition(recordType, clusterPositionMap.allocate(), -1);
        addAtomicOperationMetadata(new ORecordId(id, pos.clusterPosition), atomicOperation);
        endAtomicOperation(false, null);
        return pos;
      } catch (IOException | RuntimeException e) {
        endAtomicOperation(true, e);
        throw e;
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public OPhysicalPosition createRecord(byte[] content, final int recordVersion, final byte recordType, OPhysicalPosition allocatedPosition) throws IOException {
    startOperation();
    OSessionStoragePerformanceStatistic statistic = performanceStatisticManager.getSessionPerformanceStatistic();
    if (statistic != null)
      statistic.startRecordCreationTimer();
    try {
      content = compression.compress(content);
      content = encryption.encrypt(content);

      OAtomicOperation atomicOperation = startAtomicOperation(true);
      acquireExclusiveLock();
      try {
        int entryContentLength = getEntryContentLength(content.length);

        if (entryContentLength < OClusterPage.MAX_RECORD_SIZE) {
          try {
            byte[] entryContent = new byte[entryContentLength];

            int entryPosition = 0;
            entryContent[entryPosition] = recordType;
            entryPosition++;

            OIntegerSerializer.INSTANCE.serializeNative(content.length, entryContent, entryPosition);
            entryPosition += OIntegerSerializer.INT_SIZE;

            System.arraycopy(content, 0, entryContent, entryPosition, content.length);
            entryPosition += content.length;

            entryContent[entryPosition] = 1;
            entryPosition++;

            OLongSerializer.INSTANCE.serializeNative(-1L, entryContent, entryPosition);

            final AddEntryResult addEntryResult = addEntry(recordVersion, entryContent, atomicOperation);

            updateClusterState(1, addEntryResult.recordsSizeDiff, atomicOperation);

            final long clusterPosition;
            if (allocatedPosition != null) {
              clusterPositionMap.update(allocatedPosition.clusterPosition, new OClusterPositionMapBucket.PositionEntry(addEntryResult.pageIndex, addEntryResult.pagePosition));
              clusterPosition = allocatedPosition.clusterPosition;
            } else
              clusterPosition = clusterPositionMap.add(addEntryResult.pageIndex, addEntryResult.pagePosition);

            addAtomicOperationMetadata(new ORecordId(id, clusterPosition), atomicOperation);

            endAtomicOperation(false, null);

            return createPhysicalPosition(recordType, clusterPosition, addEntryResult.recordVersion);
          } catch (Exception e) {
            endAtomicOperation(true, e);
            throw OException.wrapException(new OPaginatedClusterException("Error during record creation", this), e);
          }
        } else {
          try {
            int entrySize = content.length + OIntegerSerializer.INT_SIZE + OByteSerializer.BYTE_SIZE;

            int fullEntryPosition = 0;
            byte[] fullEntry = new byte[entrySize];

            fullEntry[fullEntryPosition] = recordType;
            fullEntryPosition++;

            OIntegerSerializer.INSTANCE.serializeNative(content.length, fullEntry, fullEntryPosition);
            fullEntryPosition += OIntegerSerializer.INT_SIZE;

            System.arraycopy(content, 0, fullEntry, fullEntryPosition, content.length);

            long prevPageRecordPointer = -1;
            long firstPageIndex = -1;
            int firstPagePosition = -1;

            int version = 0;

            int from = 0;
            int to = from + (OClusterPage.MAX_RECORD_SIZE - OByteSerializer.BYTE_SIZE - OLongSerializer.LONG_SIZE);

            int recordsSizeDiff = 0;

            do {
              byte[] entryContent = new byte[to - from + OByteSerializer.BYTE_SIZE + OLongSerializer.LONG_SIZE];
              System.arraycopy(fullEntry, from, entryContent, 0, to - from);

              if (from > 0)
                entryContent[entryContent.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] = 0;
              else
                entryContent[entryContent.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] = 1;

              OLongSerializer.INSTANCE.serializeNative(-1L, entryContent, entryContent.length - OLongSerializer.LONG_SIZE);

              final AddEntryResult addEntryResult = addEntry(recordVersion, entryContent, atomicOperation);
              recordsSizeDiff += addEntryResult.recordsSizeDiff;

              if (firstPageIndex == -1) {
                firstPageIndex = addEntryResult.pageIndex;
                firstPagePosition = addEntryResult.pagePosition;
                version = addEntryResult.recordVersion;
              }

              long addedPagePointer = createPagePointer(addEntryResult.pageIndex, addEntryResult.pagePosition);
              if (prevPageRecordPointer >= 0) {
                long prevPageIndex = getPageIndex(prevPageRecordPointer);
                int prevPageRecordPosition = getRecordPosition(prevPageRecordPointer);

                final OCacheEntry prevPageCacheEntry = loadPageForWrite(atomicOperation, fileId, prevPageIndex, false);
                try {
                  final OClusterPage prevPage = new OClusterPage(prevPageCacheEntry, false);
                  prevPage.setRecordLongValue(prevPageRecordPosition, -OLongSerializer.LONG_SIZE, addedPagePointer);
                } finally {
                  releasePageFromWrite(atomicOperation, prevPageCacheEntry);
                }
              }

              prevPageRecordPointer = addedPagePointer;
              from = to;
              to = to + (OClusterPage.MAX_RECORD_SIZE - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE);
              if (to > fullEntry.length)
                to = fullEntry.length;

            } while (from < to);

            updateClusterState(1, recordsSizeDiff, atomicOperation);
            final long clusterPosition;
            if (allocatedPosition != null) {
              clusterPositionMap.update(allocatedPosition.clusterPosition, new OClusterPositionMapBucket.PositionEntry(firstPageIndex, firstPagePosition));
              clusterPosition = allocatedPosition.clusterPosition;
            } else
              clusterPosition = clusterPositionMap.add(firstPageIndex, firstPagePosition);

            addAtomicOperationMetadata(new ORecordId(id, clusterPosition), atomicOperation);
            endAtomicOperation(false, null);

            return createPhysicalPosition(recordType, clusterPosition, version);
          } catch (RuntimeException e) {
            endAtomicOperation(true, e);
            throw OException.wrapException(new OPaginatedClusterException("Error during record creation", this), e);
          }
        }
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      if (statistic != null)
        statistic.stopRecordCreationTimer();

      completeOperation();
    }
  }

  private void addAtomicOperationMetadata(ORID rid, OAtomicOperation atomicOperation) {
    if (!addRidMetadata)
      return;

    if (atomicOperation == null)
      return;

    ORecordOperationMetadata recordOperationMetadata = (ORecordOperationMetadata) atomicOperation
        .getMetadata(ORecordOperationMetadata.RID_METADATA_KEY);

    if (recordOperationMetadata == null) {
      recordOperationMetadata = new ORecordOperationMetadata();
      atomicOperation.addMetadata(recordOperationMetadata);
    }

    recordOperationMetadata.addRid(rid);
  }

  private static int getEntryContentLength(int grownContentSize) {
    int entryContentLength =
        grownContentSize + 2 * OByteSerializer.BYTE_SIZE + OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE;

    return entryContentLength;
  }

  @Override
  @SuppressFBWarnings(value = "PZLA_PREFER_ZERO_LENGTH_ARRAYS")
  public ORawBuffer readRecord(final long clusterPosition, boolean prefetchRecords) throws IOException {
    int pagesToPrefetch = 1;

    if (prefetchRecords)
      pagesToPrefetch = OGlobalConfiguration.QUERY_SCAN_PREFETCH_PAGES.getValueAsInteger();

    return readRecord(clusterPosition, pagesToPrefetch);

  }

  private ORawBuffer readRecord(final long clusterPosition, final int pageCount) throws IOException {
    startOperation();
    OSessionStoragePerformanceStatistic statistic = performanceStatisticManager.getSessionPerformanceStatistic();
    if (statistic != null)
      statistic.startRecordReadTimer();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          final OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(clusterPosition, pageCount);
          if (positionEntry == null)
            return null;

          final int recordPosition = positionEntry.getRecordPosition();
          final long pageIndex = positionEntry.getPageIndex();

          final OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
          if (getFilledUpTo(atomicOperation, fileId) <= pageIndex)
            return null;

          int recordVersion;
          final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false, pageCount);
          try {
            final OClusterPage localPage = new OClusterPage(cacheEntry, false);
            if (localPage.isDeleted(recordPosition))
              return null;

            recordVersion = localPage.getRecordVersion(recordPosition);
          } finally {
            releasePageFromRead(atomicOperation, cacheEntry);
          }

          final byte[] fullContent = readFullEntry(clusterPosition, pageIndex, recordPosition, atomicOperation, pageCount);
          if (fullContent == null)
            return null;

          int fullContentPosition = 0;

          final byte recordType = fullContent[fullContentPosition];
          fullContentPosition++;

          final int readContentSize = OIntegerSerializer.INSTANCE.deserializeNative(fullContent, fullContentPosition);
          fullContentPosition += OIntegerSerializer.INT_SIZE;

          byte[] recordContent = Arrays.copyOfRange(fullContent, fullContentPosition, fullContentPosition + readContentSize);

          recordContent = encryption.decrypt(recordContent);
          recordContent = compression.uncompress(recordContent);

          return new ORawBuffer(recordContent, recordVersion, recordType);
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      if (statistic != null)
        statistic.stopRecordReadTimer();
      completeOperation();
    }

  }

  @Override
  public ORawBuffer readRecordIfVersionIsNotLatest(long clusterPosition, final int recordVersion)
      throws IOException, ORecordNotFoundException {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
          OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(clusterPosition, 1);

          if (positionEntry == null)
            throw new ORecordNotFoundException(new ORecordId(id, clusterPosition),
                "Record for cluster with id " + id + " and position " + clusterPosition + " is absent.");

          int recordPosition = positionEntry.getRecordPosition();
          long pageIndex = positionEntry.getPageIndex();

          if (getFilledUpTo(atomicOperation, fileId) <= pageIndex)
            throw new ORecordNotFoundException(new ORecordId(id, clusterPosition),
                "Record for cluster with id " + id + " and position " + clusterPosition + " is absent.");

          int loadedRecordVersion;

          OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
          try {
            final OClusterPage localPage = new OClusterPage(cacheEntry, false);
            if (localPage.isDeleted(recordPosition))
              throw new ORecordNotFoundException(new ORecordId(id, clusterPosition),
                  "Record for cluster with id " + id + " and position " + clusterPosition + " is absent.");

            loadedRecordVersion = localPage.getRecordVersion(recordPosition);
          } finally {
            releasePageFromRead(atomicOperation, cacheEntry);
          }

          if (loadedRecordVersion > recordVersion)
            return readRecord(clusterPosition, false);

          return null;
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public boolean deleteRecord(long clusterPosition) throws IOException {
    startOperation();
    OSessionStoragePerformanceStatistic statistic = performanceStatisticManager.getSessionPerformanceStatistic();
    if (statistic != null)
      statistic.startRecordDeletionTimer();
    try {
      OAtomicOperation atomicOperation = startAtomicOperation(true);
      acquireExclusiveLock();
      try {

        OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(clusterPosition, 1);
        if (positionEntry == null) {
          endAtomicOperation(false, null);
          return false;
        }

        long pageIndex = positionEntry.getPageIndex();
        int recordPosition = positionEntry.getRecordPosition();

        if (getFilledUpTo(atomicOperation, fileId) <= pageIndex) {
          endAtomicOperation(false, null);
          return false;
        }

        long nextPagePointer;
        int removedContentSize = 0;

        do {
          boolean cacheEntryReleased = false;
          OCacheEntry cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, false);
          int initialFreePageIndex;
          try {
            OClusterPage localPage = new OClusterPage(cacheEntry, false);
            initialFreePageIndex = calculateFreePageIndex(localPage);

            if (localPage.isDeleted(recordPosition)) {
              if (removedContentSize == 0) {
                cacheEntryReleased = true;
                try {
                  releasePageFromWrite(atomicOperation, cacheEntry);
                } finally {
                  endAtomicOperation(false, null);
                }
                return false;
              } else
                throw new OPaginatedClusterException("Content of record " + new ORecordId(id, clusterPosition) + " was broken", this);
            } else if (removedContentSize == 0) {
              releasePageFromWrite(atomicOperation, cacheEntry);

              cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, false);

              localPage = new OClusterPage(cacheEntry, false);
            }

            byte[] content = localPage.getRecordBinaryValue(recordPosition, 0, localPage.getRecordSize(recordPosition));

            int initialFreeSpace = localPage.getFreeSpace();
            localPage.deleteRecord(recordPosition);

            removedContentSize += localPage.getFreeSpace() - initialFreeSpace;
            nextPagePointer = OLongSerializer.INSTANCE.deserializeNative(content, content.length - OLongSerializer.LONG_SIZE);
          } finally {
            if (!cacheEntryReleased) {
              releasePageFromWrite(atomicOperation, cacheEntry);
            }
          }

          updateFreePagesIndex(initialFreePageIndex, pageIndex, atomicOperation);

          pageIndex = getPageIndex(nextPagePointer);
          recordPosition = getRecordPosition(nextPagePointer);
        } while (nextPagePointer >= 0);

        updateClusterState(-1, -removedContentSize, atomicOperation);

        clusterPositionMap.remove(clusterPosition);
        addAtomicOperationMetadata(new ORecordId(id, clusterPosition), atomicOperation);
        endAtomicOperation(false, null);

        return true;
      } catch (IOException | RuntimeException e) {
        endAtomicOperation(true, e);
        throw OException.wrapException(new OPaginatedClusterException("Error during record deletion", this), e);
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      if (statistic != null)
        statistic.stopRecordDeletionTimer();

      completeOperation();
    }
  }

  @Override
  public boolean hideRecord(long position) throws IOException {
    startOperation();
    try {
      OAtomicOperation atomicOperation = startAtomicOperation(true);
      acquireExclusiveLock();
      try {
        OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(position, 1);

        if (positionEntry == null) {
          endAtomicOperation(false, null);
          return false;
        }

        long pageIndex = positionEntry.getPageIndex();
        if (getFilledUpTo(atomicOperation, fileId) <= pageIndex) {
          endAtomicOperation(false, null);
          return false;
        }

        try {
          updateClusterState(-1, 0, atomicOperation);
          clusterPositionMap.remove(position);

          addAtomicOperationMetadata(new ORecordId(id, position), atomicOperation);
          endAtomicOperation(false, null);

          return true;
        } catch (Exception e) {
          endAtomicOperation(true, e);
          throw OException.wrapException(new OPaginatedClusterException("Error during record hide", this), e);
        }
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public void updateRecord(final long clusterPosition, byte[] content, final int recordVersion, final byte recordType)
      throws IOException {
    startOperation();
    OSessionStoragePerformanceStatistic statistic = performanceStatisticManager.getSessionPerformanceStatistic();
    if (statistic != null)
      statistic.startRecordUpdateTimer();
    try {
      content = compression.compress(content);
      content = encryption.encrypt(content);

      OAtomicOperation atomicOperation = startAtomicOperation(true);

      acquireExclusiveLock();
      try {
        final OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(clusterPosition, 1);

        if (positionEntry == null) {
          endAtomicOperation(false, null);
          return;
        }

        int nextRecordPosition = positionEntry.getRecordPosition();
        long nextPageIndex = positionEntry.getPageIndex();

        int newRecordPosition = -1;
        long newPageIndex = -1;

        long prevPageIndex = -1;
        int prevRecordPosition = -1;

        long nextEntryPointer = -1;
        int from = 0;
        int to;

        long sizeDiff = 0;
        byte[] updateEntry = null;

        do {
          final int entrySize;
          final int updatedEntryPosition;

          if (updateEntry == null) {
            if (from == 0) {
              entrySize = Math.min(getEntryContentLength(content.length), OClusterPage.MAX_RECORD_SIZE);
              to = entrySize - (2 * OByteSerializer.BYTE_SIZE + OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE);
            } else {
              entrySize = Math.min(content.length - from + OByteSerializer.BYTE_SIZE + OLongSerializer.LONG_SIZE, OClusterPage.MAX_RECORD_SIZE);
              to = from + entrySize - (OByteSerializer.BYTE_SIZE + OLongSerializer.LONG_SIZE);
            }

            updateEntry = new byte[entrySize];
            int entryPosition = 0;

            if (from == 0) {
              updateEntry[entryPosition] = recordType;
              entryPosition++;

              OIntegerSerializer.INSTANCE.serializeNative(content.length, updateEntry, entryPosition);
              entryPosition += OIntegerSerializer.INT_SIZE;
            }

            System.arraycopy(content, from, updateEntry, entryPosition, to - from);
            entryPosition += to - from;

            if (nextPageIndex == positionEntry.getPageIndex())
              updateEntry[entryPosition] = 1;

            entryPosition++;

            OLongSerializer.INSTANCE.serializeNative(-1, updateEntry, entryPosition);

            if (to < content.length) {
              assert entrySize == OClusterPage.MAX_RECORD_SIZE;
            }
          } else {
            entrySize = updateEntry.length;

            if (from == 0) {
              to = entrySize - (2 * OByteSerializer.BYTE_SIZE + OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE);
            } else {
              to = from + entrySize - (OByteSerializer.BYTE_SIZE + OLongSerializer.LONG_SIZE);
            }
          }

          int freePageIndex = -1;

          if (nextPageIndex < 0) {
            FindFreePageResult findFreePageResult = findFreePage(entrySize, atomicOperation);
            nextPageIndex = findFreePageResult.pageIndex;
            freePageIndex = findFreePageResult.freePageIndex;
          }

          boolean isNew = false;
          OCacheEntry cacheEntry = loadPageForWrite(atomicOperation, fileId, nextPageIndex, false);
          if (cacheEntry == null) {
            cacheEntry = addPage(atomicOperation, fileId);
            isNew = true;
          }

          try {
            final OClusterPage localPage = new OClusterPage(cacheEntry, isNew);
            final int pageFreeSpace = localPage.getFreeSpace();

            if (freePageIndex < 0)
              freePageIndex = calculateFreePageIndex(localPage);
            else
              assert isNew || freePageIndex == calculateFreePageIndex(localPage);

            if (nextRecordPosition >= 0) {
              if (localPage.isDeleted(nextRecordPosition))
                throw new OPaginatedClusterException("Record with rid " + new ORecordId(id, clusterPosition) + " was deleted", this);

              int currentEntrySize = localPage.getRecordSize(nextRecordPosition);
              nextEntryPointer = localPage.getRecordLongValue(nextRecordPosition, currentEntrySize - OLongSerializer.LONG_SIZE);

              if (currentEntrySize == entrySize) {
                localPage.replaceRecord(nextRecordPosition, updateEntry, recordVersion);
                updatedEntryPosition = nextRecordPosition;
              } else {
                localPage.deleteRecord(nextRecordPosition);

                if (localPage.getMaxRecordSize() >= entrySize) {
                  updatedEntryPosition = localPage.appendRecord(recordVersion, updateEntry);

                  if (updatedEntryPosition < 0) {
                    localPage.dumpToLog();
                    throw new IllegalStateException("Page " + cacheEntry.getPageIndex() + " does not have enough free space to add record content, freePageIndex=" + freePageIndex
                        + ", updateEntry.length=" + updateEntry.length + ", content.length=" + content.length);
                  }
                } else {
                  updatedEntryPosition = -1;
                }
              }

              if (nextEntryPointer >= 0) {
                nextRecordPosition = getRecordPosition(nextEntryPointer);
                nextPageIndex = getPageIndex(nextEntryPointer);
              } else {
                nextPageIndex = -1;
                nextRecordPosition = -1;
              }

            } else {
              assert localPage.getFreeSpace() >= entrySize;
              updatedEntryPosition = localPage.appendRecord(recordVersion, updateEntry);

              if (updatedEntryPosition < 0) {
                localPage.dumpToLog();
                throw new IllegalStateException(
                    "Page " + cacheEntry.getPageIndex() + " does not have enough free space to add record content, freePageIndex=" + freePageIndex + ", updateEntry.length=" + updateEntry.length + ", content.length=" + content.length);
              }

              nextPageIndex = -1;
              nextRecordPosition = -1;
            }

            sizeDiff += pageFreeSpace - localPage.getFreeSpace();

          } finally {
            releasePageFromWrite(atomicOperation, cacheEntry);
          }

          updateFreePagesIndex(freePageIndex, cacheEntry.getPageIndex(), atomicOperation);

          if (updatedEntryPosition >= 0) {
            if (from == 0) {
              newPageIndex = cacheEntry.getPageIndex();
              newRecordPosition = updatedEntryPosition;
            }

            from = to;

            if (prevPageIndex >= 0) {
              OCacheEntry prevCacheEntry = loadPageForWrite(atomicOperation, fileId, prevPageIndex, false);
              try {
                OClusterPage prevPage = new OClusterPage(prevCacheEntry, false);
                prevPage.setRecordLongValue(prevRecordPosition, -OLongSerializer.LONG_SIZE, createPagePointer(cacheEntry.getPageIndex(), updatedEntryPosition));
              } finally {
                releasePageFromWrite(atomicOperation, prevCacheEntry);
              }
            }

            prevPageIndex = cacheEntry.getPageIndex();
            prevRecordPosition = updatedEntryPosition;

            updateEntry = null;
          }
        } while (to < content.length || updateEntry != null);

        // clear unneeded pages
        while (nextEntryPointer >= 0) {
          nextPageIndex = getPageIndex(nextEntryPointer);
          nextRecordPosition = getRecordPosition(nextEntryPointer);

          final int freePagesIndex;
          final int freeSpace;

          OCacheEntry cacheEntry = loadPageForWrite(atomicOperation, fileId, nextPageIndex, false);
          try {
            final OClusterPage localPage = new OClusterPage(cacheEntry, false);
            freeSpace = localPage.getFreeSpace();
            freePagesIndex = calculateFreePageIndex(localPage);

            nextEntryPointer = localPage.getRecordLongValue(nextRecordPosition, -OLongSerializer.LONG_SIZE);
            localPage.deleteRecord(nextRecordPosition);

            sizeDiff += freeSpace - localPage.getFreeSpace();
          } finally {
            releasePageFromWrite(atomicOperation, cacheEntry);
          }

          updateFreePagesIndex(freePagesIndex, nextPageIndex, atomicOperation);
        }

        assert newPageIndex >= 0;
        assert newRecordPosition >= 0;

        if (newPageIndex != positionEntry.getPageIndex() || newRecordPosition != positionEntry.getRecordPosition()) {
          clusterPositionMap.update(clusterPosition, new OClusterPositionMapBucket.PositionEntry(newPageIndex, newRecordPosition));
        }

        updateClusterState(0, sizeDiff, atomicOperation);

        addAtomicOperationMetadata(new ORecordId(id, clusterPosition), atomicOperation);
        endAtomicOperation(false, null);
      } catch (RuntimeException e) {
        endAtomicOperation(true, e);
        throw OException.wrapException(new OPaginatedClusterException("Error during record update", this), e);
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      if (statistic != null)
        statistic.stopRecordUpdateTimer();

      completeOperation();
    }
  }

  /**
   * Recycles a deleted record.
   */
  @Override
  public void recycleRecord(final long clusterPosition, byte[] content, final int recordVersion, final byte recordType)
      throws IOException {
    startOperation();
    OSessionStoragePerformanceStatistic statistic = performanceStatisticManager.getSessionPerformanceStatistic();
    if (statistic != null)
      statistic.startRecordUpdateTimer();
    try {
      OAtomicOperation atomicOperation = startAtomicOperation(true);

      acquireExclusiveLock();
      try {
        final OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(clusterPosition, 1);
        if (positionEntry != null) {
          // NOT DELETED
          throw new OPaginatedClusterException("Record with rid " + new ORecordId(id, clusterPosition) + " was not deleted", this);
        }

        content = compression.compress(content);
        content = encryption.encrypt(content);

        int entryContentLength = getEntryContentLength(content.length);

        if (entryContentLength < OClusterPage.MAX_RECORD_SIZE) {
          try {
            byte[] entryContent = new byte[entryContentLength];

            int entryPosition = 0;
            entryContent[entryPosition] = recordType;
            entryPosition++;

            OIntegerSerializer.INSTANCE.serializeNative(content.length, entryContent, entryPosition);
            entryPosition += OIntegerSerializer.INT_SIZE;

            System.arraycopy(content, 0, entryContent, entryPosition, content.length);
            entryPosition += content.length;

            entryContent[entryPosition] = 1;
            entryPosition++;

            OLongSerializer.INSTANCE.serializeNative(-1L, entryContent, entryPosition);

            final AddEntryResult addEntryResult = addEntry(recordVersion, entryContent, atomicOperation);

            updateClusterState(1, addEntryResult.recordsSizeDiff, atomicOperation);

            clusterPositionMap.resurrect(clusterPosition,
                new OClusterPositionMapBucket.PositionEntry(addEntryResult.pageIndex, addEntryResult.pagePosition));

            addAtomicOperationMetadata(new ORecordId(id, clusterPosition), atomicOperation);

            endAtomicOperation(false, null);

          } catch (Exception e) {
            throw OException.wrapException(new OPaginatedClusterException("Error during record recycling", this), e);
          }
        } else {
          try {
            int entrySize = content.length + OIntegerSerializer.INT_SIZE + OByteSerializer.BYTE_SIZE;

            int fullEntryPosition = 0;
            byte[] fullEntry = new byte[entrySize];

            fullEntry[fullEntryPosition] = recordType;
            fullEntryPosition++;

            OIntegerSerializer.INSTANCE.serializeNative(content.length, fullEntry, fullEntryPosition);
            fullEntryPosition += OIntegerSerializer.INT_SIZE;

            System.arraycopy(content, 0, fullEntry, fullEntryPosition, content.length);

            long prevPageRecordPointer = -1;
            long firstPageIndex = -1;
            int firstPagePosition = -1;

            int from = 0;
            int to = from + (OClusterPage.MAX_RECORD_SIZE - OByteSerializer.BYTE_SIZE - OLongSerializer.LONG_SIZE);

            int recordsSizeDiff = 0;

            do {
              byte[] entryContent = new byte[to - from + OByteSerializer.BYTE_SIZE + OLongSerializer.LONG_SIZE];
              System.arraycopy(fullEntry, from, entryContent, 0, to - from);

              if (from > 0)
                entryContent[entryContent.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] = 0;
              else
                entryContent[entryContent.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] = 1;

              OLongSerializer.INSTANCE.serializeNative(-1L, entryContent, entryContent.length - OLongSerializer.LONG_SIZE);

              final AddEntryResult addEntryResult = addEntry(recordVersion, entryContent, atomicOperation);
              recordsSizeDiff += addEntryResult.recordsSizeDiff;

              if (firstPageIndex == -1) {
                firstPageIndex = addEntryResult.pageIndex;
                firstPagePosition = addEntryResult.pagePosition;
              }

              long addedPagePointer = createPagePointer(addEntryResult.pageIndex, addEntryResult.pagePosition);
              if (prevPageRecordPointer >= 0) {
                long prevPageIndex = getPageIndex(prevPageRecordPointer);
                int prevPageRecordPosition = getRecordPosition(prevPageRecordPointer);

                final OCacheEntry prevPageCacheEntry = loadPageForWrite(atomicOperation, fileId, prevPageIndex, false);
                try {
                  final OClusterPage prevPage = new OClusterPage(prevPageCacheEntry, false);
                  prevPage.setRecordLongValue(prevPageRecordPosition, -OLongSerializer.LONG_SIZE, addedPagePointer);
                } finally {
                  releasePageFromWrite(atomicOperation, prevPageCacheEntry);
                }
              }

              prevPageRecordPointer = addedPagePointer;
              from = to;
              to = to + (OClusterPage.MAX_RECORD_SIZE - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE);
              if (to > fullEntry.length)
                to = fullEntry.length;

            } while (from < to);

            updateClusterState(1, recordsSizeDiff, atomicOperation);

            clusterPositionMap
                .update(clusterPosition, new OClusterPositionMapBucket.PositionEntry(firstPageIndex, firstPagePosition));

            addAtomicOperationMetadata(new ORecordId(id, clusterPosition), atomicOperation);

            endAtomicOperation(false, null);

          } catch (RuntimeException e) {
            if (e instanceof OPaginatedClusterException)
              throw e;
            else
              throw OException.wrapException(new OPaginatedClusterException("Error during record recycling", this), e);
          }
        }

      } catch (RuntimeException e) {
        endAtomicOperation(true, e);
        if (e instanceof OPaginatedClusterException)
          throw e;
        else
          throw OException.wrapException(new OPaginatedClusterException("Error during record recycling", this), e);
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      if (statistic != null)
        statistic.stopRecordUpdateTimer();

      completeOperation();
    }
  }

  @Override
  public long getTombstonesCount() {
    return 0;
  }

  @Override
  public void truncate() throws IOException {
    startOperation();
    try {

      OAtomicOperation atomicOperation = startAtomicOperation(true);

      acquireExclusiveLock();
      try {
        truncateFile(atomicOperation, fileId);
        clusterPositionMap.truncate();

        initCusterState(atomicOperation);

        endAtomicOperation(false, null);

      } catch (Exception e) {
        endAtomicOperation(true, e);
        throw OException.wrapException(new OPaginatedClusterException("Error during cluster truncate", this), e);
      } finally {
        releaseExclusiveLock();
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public OPhysicalPosition getPhysicalPosition(OPhysicalPosition position) throws IOException {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          long clusterPosition = position.clusterPosition;
          OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(clusterPosition, 1);

          if (positionEntry == null)
            return null;

          OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
          long pageIndex = positionEntry.getPageIndex();
          int recordPosition = positionEntry.getRecordPosition();

          long pagesCount = getFilledUpTo(atomicOperation, fileId);
          if (pageIndex >= pagesCount)
            return null;

          OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
          try {
            final OClusterPage localPage = new OClusterPage(cacheEntry, false);
            if (localPage.isDeleted(recordPosition))
              return null;

            if (localPage.getRecordByteValue(recordPosition, -OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE) == 0)
              return null;

            final OPhysicalPosition physicalPosition = new OPhysicalPosition();
            physicalPosition.recordSize = -1;

            physicalPosition.recordType = localPage.getRecordByteValue(recordPosition, 0);
            physicalPosition.recordVersion = localPage.getRecordVersion(recordPosition);
            physicalPosition.clusterPosition = position.clusterPosition;

            return physicalPosition;
          } finally {
            releasePageFromRead(atomicOperation, cacheEntry);
          }
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public long getEntries() {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          final OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();
          final OCacheEntry pinnedStateEntry = loadPageForRead(atomicOperation, fileId, pinnedStateEntryIndex, true);
          try {
            return new OPaginatedClusterState(pinnedStateEntry).getSize();
          } finally {
            releasePageFromRead(atomicOperation, pinnedStateEntry);
          }
        } finally {
          releaseSharedLock();
        }
      } catch (IOException ioe) {
        throw OException
            .wrapException(new OPaginatedClusterException("Error during retrieval of size of '" + getName() + "' cluster", this),
                ioe);
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public long getFirstPosition() throws IOException {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          return clusterPositionMap.getFirstPosition();
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public long getLastPosition() throws IOException {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          return clusterPositionMap.getLastPosition();
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public long getNextPosition() throws IOException {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          return clusterPositionMap.getNextPosition();
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public String getFileName() {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          return writeCache.fileNameById(fileId);
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public int getId() {
    return id;
  }

  /**
   * Returns the fileId used in disk cache.
   */
  public long getFileId() {
    return fileId;
  }

  @Override
  public void synch() throws IOException {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          writeCache.flush(fileId);
          clusterPositionMap.flush();
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public long getRecordsSize() throws IOException {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          final OAtomicOperation atomicOperation = atomicOperationsManager.getCurrentOperation();

          final OCacheEntry pinnedStateEntry = loadPageForRead(atomicOperation, fileId, pinnedStateEntryIndex, true);
          try {
            return new OPaginatedClusterState(pinnedStateEntry).getRecordsSize();
          } finally {
            releasePageFromRead(atomicOperation, pinnedStateEntry);
          }
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public boolean isHashBased() {
    return false;
  }

  @Override
  public OClusterEntryIterator absoluteIterator() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        return new OClusterEntryIterator(this);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public OPhysicalPosition[] higherPositions(OPhysicalPosition position) throws IOException {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          final long[] clusterPositions = clusterPositionMap.higherPositions(position.clusterPosition);
          return convertToPhysicalPositions(clusterPositions);
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public OPhysicalPosition[] ceilingPositions(OPhysicalPosition position) throws IOException {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          final long[] clusterPositions = clusterPositionMap.ceilingPositions(position.clusterPosition);
          return convertToPhysicalPositions(clusterPositions);
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public OPhysicalPosition[] lowerPositions(OPhysicalPosition position) throws IOException {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          final long[] clusterPositions = clusterPositionMap.lowerPositions(position.clusterPosition);
          return convertToPhysicalPositions(clusterPositions);
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public OPhysicalPosition[] floorPositions(OPhysicalPosition position) throws IOException {
    startOperation();
    try {
      atomicOperationsManager.acquireReadLock(this);
      try {
        acquireSharedLock();
        try {
          final long[] clusterPositions = clusterPositionMap.floorPositions(position.clusterPosition);
          return convertToPhysicalPositions(clusterPositions);
        } finally {
          releaseSharedLock();
        }
      } finally {
        atomicOperationsManager.releaseReadLock(this);
      }
    } finally {
      completeOperation();
    }
  }

  @Override
  public ORecordConflictStrategy getRecordConflictStrategy() {
    return recordConflictStrategy;
  }

  private void setRecordConflictStrategy(final String stringValue) {
    recordConflictStrategy = Orient.instance().getRecordConflictStrategy().getStrategy(stringValue);
    config.conflictStrategy = stringValue;
    storageLocal.getConfiguration().update();
  }

  private void updateClusterState(long sizeDiff, long recordsSizeDiff, OAtomicOperation atomicOperation) throws IOException {
    final OCacheEntry pinnedStateEntry = loadPageForWrite(atomicOperation, fileId, pinnedStateEntryIndex, true);
    try {
      OPaginatedClusterState paginatedClusterState = new OPaginatedClusterState(pinnedStateEntry);
      paginatedClusterState.setSize(paginatedClusterState.getSize() + sizeDiff);
      paginatedClusterState.setRecordsSize(paginatedClusterState.getRecordsSize() + recordsSizeDiff);
    } finally {
      releasePageFromWrite(atomicOperation, pinnedStateEntry);
    }
  }

  private void init(final OAbstractPaginatedStorage storage, final OStorageClusterConfiguration config) throws IOException {
    OFileUtils.checkValidName(config.getName());

    this.config = (OStoragePaginatedClusterConfiguration) config;
    this.compression = OCompressionFactory.INSTANCE.getCompression(this.config.compression, null);
    this.encryption = OEncryptionFactory.INSTANCE.getEncryption(this.config.encryption, this.config.encryptionKey);

    if (((OStoragePaginatedClusterConfiguration) config).conflictStrategy != null)
      this.recordConflictStrategy = Orient.instance().getRecordConflictStrategy()
          .getStrategy(((OStoragePaginatedClusterConfiguration) config).conflictStrategy);

    storageLocal = storage;

    this.id = config.getId();

    clusterPositionMap = new OClusterPositionMap(storage, getName(), getFullName());
  }

  private void setEncryptionInternal(final String iMethod, final String iKey) {
    try {
      encryption = OEncryptionFactory.INSTANCE.getEncryption(iMethod, iKey);
      config.encryption = iMethod;
      storageLocal.getConfiguration().update();
    } catch (IllegalArgumentException e) {
      throw OException
          .wrapException(new OPaginatedClusterException("Invalid value for " + ATTRIBUTES.ENCRYPTION + " attribute", this), e);
    }
  }

  private void setRecordOverflowGrowFactorInternal(final String stringValue) {
    try {
      float growFactor = Float.parseFloat(stringValue);
      if (growFactor < 1)
        throw new OPaginatedClusterException(OCluster.ATTRIBUTES.RECORD_OVERFLOW_GROW_FACTOR + " cannot be less than 1", this);

      config.recordOverflowGrowFactor = growFactor;
      storageLocal.getConfiguration().update();
    } catch (NumberFormatException nfe) {
      throw OException.wrapException(new OPaginatedClusterException(
          "Invalid value for cluster attribute " + OCluster.ATTRIBUTES.RECORD_OVERFLOW_GROW_FACTOR + " was passed [" + stringValue
              + "]", this), nfe);
    }
  }

  private void setRecordGrowFactorInternal(String stringValue) {
    try {
      float growFactor = Float.parseFloat(stringValue);
      if (growFactor < 1)
        throw new OPaginatedClusterException(OCluster.ATTRIBUTES.RECORD_GROW_FACTOR + " cannot be less than 1", this);

      config.recordGrowFactor = growFactor;
      storageLocal.getConfiguration().update();
    } catch (NumberFormatException nfe) {
      throw OException.wrapException(new OPaginatedClusterException(
          "Invalid value for cluster attribute " + OCluster.ATTRIBUTES.RECORD_GROW_FACTOR + " was passed [" + stringValue + "]",
          this), nfe);
    }
  }

  private void setNameInternal(final String newName) throws IOException {

    writeCache.renameFile(fileId, newName + getExtension());
    clusterPositionMap.rename(newName);

    config.name = newName;
    storageLocal.renameCluster(getName(), newName);
    setName(newName);

    storageLocal.getConfiguration().update();
  }

  private OPhysicalPosition createPhysicalPosition(final byte recordType, final long clusterPosition, final int version) {
    final OPhysicalPosition physicalPosition = new OPhysicalPosition();
    physicalPosition.recordType = recordType;
    physicalPosition.recordSize = -1;
    physicalPosition.clusterPosition = clusterPosition;
    physicalPosition.recordVersion = version;
    return physicalPosition;
  }

  @SuppressFBWarnings(value = "PZLA_PREFER_ZERO_LENGTH_ARRAYS")
  private byte[] readFullEntry(final long clusterPosition, long pageIndex, int recordPosition,
      final OAtomicOperation atomicOperation, final int pageCount) throws IOException {
    if (getFilledUpTo(atomicOperation, fileId) <= pageIndex)
      return null;

    final List<byte[]> recordChunks = new ArrayList<>();
    int contentSize = 0;

    long nextPagePointer;
    boolean firstEntry = true;
    do {
      OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false, pageCount);
      try {
        final OClusterPage localPage = new OClusterPage(cacheEntry, false);

        if (localPage.isDeleted(recordPosition)) {
          if (recordChunks.isEmpty())
            return null;
          else
            throw new OPaginatedClusterException("Content of record " + new ORecordId(id, clusterPosition) + " was broken", this);
        }

        byte[] content = localPage.getRecordBinaryValue(recordPosition, 0, localPage.getRecordSize(recordPosition));

        if (firstEntry && content[content.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] == 0)
          return null;

        recordChunks.add(content);
        nextPagePointer = OLongSerializer.INSTANCE.deserializeNative(content, content.length - OLongSerializer.LONG_SIZE);
        contentSize += content.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE;

        firstEntry = false;
      } finally {
        releasePageFromRead(atomicOperation, cacheEntry);
      }

      pageIndex = getPageIndex(nextPagePointer);
      recordPosition = getRecordPosition(nextPagePointer);
    } while (nextPagePointer >= 0);

    byte[] fullContent;
    if (recordChunks.size() == 1)
      fullContent = recordChunks.get(0);
    else {
      fullContent = new byte[contentSize + OLongSerializer.LONG_SIZE + OByteSerializer.BYTE_SIZE];
      int fullContentPosition = 0;
      for (byte[] recordChuck : recordChunks) {
        System.arraycopy(recordChuck, 0, fullContent, fullContentPosition,
            recordChuck.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE);
        fullContentPosition += recordChuck.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE;
      }
    }

    return fullContent;
  }

  private static long createPagePointer(long pageIndex, int pagePosition) {
    return pageIndex << PAGE_INDEX_OFFSET | pagePosition;
  }

  private static int getRecordPosition(long nextPagePointer) {
    return (int) (nextPagePointer & RECORD_POSITION_MASK);
  }

  private static long getPageIndex(long nextPagePointer) {
    return nextPagePointer >>> PAGE_INDEX_OFFSET;
  }

  private AddEntryResult addEntry(final int recordVersion, byte[] entryContent, OAtomicOperation atomicOperation)
      throws IOException {
    final FindFreePageResult findFreePageResult = findFreePage(entryContent.length, atomicOperation);

    int freePageIndex = findFreePageResult.freePageIndex;
    long pageIndex = findFreePageResult.pageIndex;

    boolean newRecord = freePageIndex >= FREE_LIST_SIZE;

    OCacheEntry cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, false);
    if (cacheEntry == null)
      cacheEntry = addPage(atomicOperation, fileId);

    int recordSizesDiff;
    int position;
    final int finalVersion;

    try {
      final OClusterPage localPage = new OClusterPage(cacheEntry, newRecord);
      assert newRecord || freePageIndex == calculateFreePageIndex(localPage);

      int initialFreeSpace = localPage.getFreeSpace();

      position = localPage.appendRecord(recordVersion, entryContent);

      if (position < 0) {
        localPage.dumpToLog();
        throw new IllegalStateException(
            "Page " + cacheEntry.getPageIndex() + " does not have enough free space to add record content, freePageIndex="
                + freePageIndex + ", entryContent.length=" + entryContent.length);
      }

      finalVersion = localPage.getRecordVersion(position);

      int freeSpace = localPage.getFreeSpace();
      recordSizesDiff = initialFreeSpace - freeSpace;
    } finally {
      releasePageFromWrite(atomicOperation, cacheEntry);
    }

    updateFreePagesIndex(freePageIndex, pageIndex, atomicOperation);

    return new AddEntryResult(pageIndex, position, finalVersion, recordSizesDiff);
  }

  private FindFreePageResult findFreePage(int contentSize, OAtomicOperation atomicOperation) throws IOException {
    while (true) {
      int freePageIndex = contentSize / ONE_KB;
      freePageIndex -= PAGINATED_STORAGE_LOWEST_FREELIST_BOUNDARY.getValueAsInteger();
      if (freePageIndex < 0)
        freePageIndex = 0;

      long pageIndex;

      final OCacheEntry pinnedStateEntry = loadPageForRead(atomicOperation, fileId, pinnedStateEntryIndex, true);
      if (pinnedStateEntry == null) {
        loadPageForRead(atomicOperation, fileId, pinnedStateEntryIndex, true);
      }
      try {
        OPaginatedClusterState freePageLists = new OPaginatedClusterState(pinnedStateEntry);
        do {
          pageIndex = freePageLists.getFreeListPage(freePageIndex);
          freePageIndex++;
        } while (pageIndex < 0 && freePageIndex < FREE_LIST_SIZE);

      } finally {
        releasePageFromRead(atomicOperation, pinnedStateEntry);
      }

      if (pageIndex < 0)
        pageIndex = getFilledUpTo(atomicOperation, fileId);
      else
        freePageIndex--;

      if (freePageIndex < FREE_LIST_SIZE) {
        OCacheEntry cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, false);

        //free list is broken automatically fix it
        if (cacheEntry == null) {
          updateFreePagesList(freePageIndex, -1, atomicOperation);

          continue;
        } else {
          int realFreePageIndex;
          try {
            OClusterPage localPage = new OClusterPage(cacheEntry, false);
            realFreePageIndex = calculateFreePageIndex(localPage);
          } finally {
            releasePageFromWrite(atomicOperation, cacheEntry);
          }

          if (realFreePageIndex != freePageIndex) {
            OLogManager.instance()
                .warn(this, "Page in file %s with index %d was placed in wrong free list, this error will be fixed automatically",
                    getFullName(), pageIndex);

            updateFreePagesIndex(freePageIndex, pageIndex, atomicOperation);
            continue;
          }
        }
      }

      return new FindFreePageResult(pageIndex, freePageIndex);
    }
  }

  private void updateFreePagesIndex(int prevFreePageIndex, long pageIndex, OAtomicOperation atomicOperation) throws IOException {
    final OCacheEntry cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, false);

    try {
      final OClusterPage localPage = new OClusterPage(cacheEntry, false);
      int newFreePageIndex = calculateFreePageIndex(localPage);

      if (prevFreePageIndex == newFreePageIndex)
        return;

      long nextPageIndex = localPage.getNextPage();
      long prevPageIndex = localPage.getPrevPage();

      if (prevPageIndex >= 0) {
        final OCacheEntry prevPageCacheEntry = loadPageForWrite(atomicOperation, fileId, prevPageIndex, false);
        try {
          final OClusterPage prevPage = new OClusterPage(prevPageCacheEntry, false);
          assert calculateFreePageIndex(prevPage) == prevFreePageIndex;
          prevPage.setNextPage(nextPageIndex);
        } finally {
          releasePageFromWrite(atomicOperation, prevPageCacheEntry);
        }
      }

      if (nextPageIndex >= 0) {
        final OCacheEntry nextPageCacheEntry = loadPageForWrite(atomicOperation, fileId, nextPageIndex, false);
        try {
          final OClusterPage nextPage = new OClusterPage(nextPageCacheEntry, false);
          if (calculateFreePageIndex(nextPage) != prevFreePageIndex)
            calculateFreePageIndex(nextPage);

          assert calculateFreePageIndex(nextPage) == prevFreePageIndex;
          nextPage.setPrevPage(prevPageIndex);

        } finally {
          releasePageFromWrite(atomicOperation, nextPageCacheEntry);
        }
      }

      localPage.setNextPage(-1);
      localPage.setPrevPage(-1);

      if (prevFreePageIndex < 0 && newFreePageIndex < 0)
        return;

      if (prevFreePageIndex >= 0 && prevFreePageIndex < FREE_LIST_SIZE) {
        if (prevPageIndex < 0)
          updateFreePagesList(prevFreePageIndex, nextPageIndex, atomicOperation);
      }

      if (newFreePageIndex >= 0) {
        long oldFreePage;
        OCacheEntry pinnedStateEntry = loadPageForRead(atomicOperation, fileId, pinnedStateEntryIndex, true);
        try {
          OPaginatedClusterState clusterFreeList = new OPaginatedClusterState(pinnedStateEntry);
          oldFreePage = clusterFreeList.getFreeListPage(newFreePageIndex);
        } finally {
          releasePageFromRead(atomicOperation, pinnedStateEntry);
        }

        if (oldFreePage >= 0) {
          final OCacheEntry oldFreePageCacheEntry = loadPageForWrite(atomicOperation, fileId, oldFreePage, false);
          try {
            final OClusterPage oldFreeLocalPage = new OClusterPage(oldFreePageCacheEntry, false);
            assert calculateFreePageIndex(oldFreeLocalPage) == newFreePageIndex;

            oldFreeLocalPage.setPrevPage(pageIndex);
          } finally {
            releasePageFromWrite(atomicOperation, oldFreePageCacheEntry);
          }

          localPage.setNextPage(oldFreePage);
          localPage.setPrevPage(-1);
        }

        updateFreePagesList(newFreePageIndex, pageIndex, atomicOperation);
      }
    } finally {
      releasePageFromWrite(atomicOperation, cacheEntry);
    }
  }

  private void updateFreePagesList(int freeListIndex, long pageIndex, OAtomicOperation atomicOperation) throws IOException {
    final OCacheEntry pinnedStateEntry = loadPageForWrite(atomicOperation, fileId, pinnedStateEntryIndex, true);
    try {
      OPaginatedClusterState paginatedClusterState = new OPaginatedClusterState(pinnedStateEntry);
      paginatedClusterState.setFreeListPage(freeListIndex, pageIndex);
    } finally {
      releasePageFromWrite(atomicOperation, pinnedStateEntry);
    }
  }

  private int calculateFreePageIndex(OClusterPage localPage) {
    int newFreePageIndex;
    if (localPage.isEmpty())
      newFreePageIndex = FREE_LIST_SIZE - 1;
    else {
      newFreePageIndex = (localPage.getMaxRecordSize() - (ONE_KB - 1)) / ONE_KB;

      newFreePageIndex -= LOWEST_FREELIST_BOUNDARY;
    }
    return newFreePageIndex;
  }

  private void initCusterState(OAtomicOperation atomicOperation) throws IOException {
    OCacheEntry pinnedStateEntry = addPage(atomicOperation, fileId);
    try {
      OPaginatedClusterState paginatedClusterState = new OPaginatedClusterState(pinnedStateEntry);

      pinPage(atomicOperation, pinnedStateEntry);
      paginatedClusterState.setSize(0);
      paginatedClusterState.setRecordsSize(0);

      for (int i = 0; i < FREE_LIST_SIZE; i++)
        paginatedClusterState.setFreeListPage(i, -1);

      pinnedStateEntryIndex = pinnedStateEntry.getPageIndex();
    } finally {
      releasePageFromWrite(atomicOperation, pinnedStateEntry);
    }

  }

  private OPhysicalPosition[] convertToPhysicalPositions(long[] clusterPositions) {
    OPhysicalPosition[] positions = new OPhysicalPosition[clusterPositions.length];
    for (int i = 0; i < positions.length; i++) {
      OPhysicalPosition physicalPosition = new OPhysicalPosition();
      physicalPosition.clusterPosition = clusterPositions[i];
      positions[i] = physicalPosition;
    }
    return positions;
  }

  @Override
  protected void startOperation() {
    OSessionStoragePerformanceStatistic sessionStoragePerformanceStatistic = performanceStatisticManager
        .getSessionPerformanceStatistic();
    if (sessionStoragePerformanceStatistic != null) {
      sessionStoragePerformanceStatistic
          .startComponentOperation(getFullName(), OSessionStoragePerformanceStatistic.ComponentType.CLUSTER);
    }
  }

  public OPaginatedClusterDebug readDebug(long clusterPosition) throws IOException {
    startOperation();
    try {
      OPaginatedClusterDebug debug = new OPaginatedClusterDebug();
      debug.clusterPosition = clusterPosition;
      debug.fileId = fileId;
      OAtomicOperation atomicOperation = storageLocal.getAtomicOperationsManager().getCurrentOperation();

      OClusterPositionMapBucket.PositionEntry positionEntry = clusterPositionMap.get(clusterPosition, 1);
      if (positionEntry == null) {
        debug.empty = true;
        return debug;
      }

      long pageIndex = positionEntry.getPageIndex();
      int recordPosition = positionEntry.getRecordPosition();
      if (getFilledUpTo(atomicOperation, fileId) <= pageIndex) {
        debug.empty = true;
        return debug;
      }

      debug.pages = new ArrayList<>();
      int contentSize = 0;

      long nextPagePointer;
      boolean firstEntry = true;
      do {
        OClusterPageDebug debugPage = new OClusterPageDebug();
        debugPage.pageIndex = pageIndex;
        OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
        try {
          final OClusterPage localPage = new OClusterPage(cacheEntry, false);

          if (localPage.isDeleted(recordPosition)) {
            if (debug.pages.isEmpty()) {
              debug.empty = true;
              return debug;
            } else
              throw new OPaginatedClusterException("Content of record " + new ORecordId(id, clusterPosition) + " was broken", this);
          }
          debugPage.inPagePosition = recordPosition;
          debugPage.inPageSize = localPage.getRecordSize(recordPosition);
          byte[] content = localPage.getRecordBinaryValue(recordPosition, 0, debugPage.inPageSize);
          debugPage.content = content;
          if (firstEntry && content[content.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] == 0) {
            debug.empty = true;
            return debug;
          }

          debug.pages.add(debugPage);
          nextPagePointer = OLongSerializer.INSTANCE.deserializeNative(content, content.length - OLongSerializer.LONG_SIZE);
          contentSize += content.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE;

          firstEntry = false;
        } finally {
          releasePageFromRead(atomicOperation, cacheEntry);
        }

        pageIndex = getPageIndex(nextPagePointer);
        recordPosition = getRecordPosition(nextPagePointer);
      } while (nextPagePointer >= 0);
      debug.contentSize = contentSize;
      return debug;
    } finally {
      completeOperation();
    }
  }

  public RECORD_STATUS getRecordStatus(final long clusterPosition) throws IOException {
    final byte status = clusterPositionMap.getStatus(clusterPosition);

    switch (status) {
    case OClusterPositionMapBucket.NOT_EXISTENT:
      return RECORD_STATUS.NOT_EXISTENT;
    case OClusterPositionMapBucket.ALLOCATED:
      return RECORD_STATUS.ALLOCATED;
    case OClusterPositionMapBucket.FILLED:
      return RECORD_STATUS.PRESENT;
    case OClusterPositionMapBucket.REMOVED:
      return RECORD_STATUS.REMOVED;
    }

    // UNREACHABLE
    return null;
  }

  @Override
  public void acquireAtomicExclusiveLock() {
    atomicOperationsManager.acquireExclusiveLockTillOperationComplete(this);
  }

  @Override
  public String toString() {
    return "plocal cluster: " + getName();
  }
}

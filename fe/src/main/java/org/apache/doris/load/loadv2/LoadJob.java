// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.load.loadv2;

import org.apache.doris.analysis.DataDescription;
import org.apache.doris.analysis.LoadStmt;
import org.apache.doris.catalog.AuthorizationInfo;
import org.apache.doris.catalog.Catalog;
import org.apache.doris.catalog.Database;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.Config;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.ErrorCode;
import org.apache.doris.common.ErrorReport;
import org.apache.doris.common.FeMetaVersion;
import org.apache.doris.common.LabelAlreadyUsedException;
import org.apache.doris.common.MetaNotFoundException;
import org.apache.doris.common.UserException;
import org.apache.doris.common.io.Text;
import org.apache.doris.common.io.Writable;
import org.apache.doris.common.util.LogBuilder;
import org.apache.doris.common.util.LogKey;
import org.apache.doris.common.util.TimeUtils;
import org.apache.doris.load.EtlJobType;
import org.apache.doris.load.EtlStatus;
import org.apache.doris.load.FailMsg;
import org.apache.doris.load.Load;
import org.apache.doris.load.Source;
import org.apache.doris.metric.MetricRepo;
import org.apache.doris.mysql.privilege.PaloPrivilege;
import org.apache.doris.mysql.privilege.PrivPredicate;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.qe.Coordinator;
import org.apache.doris.qe.QeProcessorImpl;
import org.apache.doris.thrift.TEtlState;
import org.apache.doris.thrift.TUniqueId;
import org.apache.doris.transaction.AbstractTxnStateChangeCallback;
import org.apache.doris.transaction.BeginTransactionException;
import org.apache.doris.transaction.TransactionException;
import org.apache.doris.transaction.TransactionState;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.Gson;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public abstract class LoadJob extends AbstractTxnStateChangeCallback implements LoadTaskCallback, Writable {

    private static final Logger LOG = LogManager.getLogger(LoadJob.class);

    protected static final String QUALITY_FAIL_MSG = "quality not good enough to cancel";
    protected static final String DPP_NORMAL_ALL = "dpp.norm.ALL";
    protected static final String DPP_ABNORMAL_ALL = "dpp.abnorm.ALL";

    protected long id;
    // input params
    protected long dbId;
    protected String label;
    protected JobState state = JobState.PENDING;
    protected EtlJobType jobType;
    // the auth info could be null when load job is created before commit named 'Persist auth info in load job'
    protected AuthorizationInfo authorizationInfo;

    // optional properties
    // timeout second need to be reset in constructor of subclass
    protected long timeoutSecond = Config.broker_load_default_timeout_second;
    protected long execMemLimit = 2147483648L; // 2GB;
    protected double maxFilterRatio = 0;
    @Deprecated
    protected boolean deleteFlag = false;
    protected boolean strictMode = true;

    protected long createTimestamp = System.currentTimeMillis();
    protected long loadStartTimestamp = -1;
    protected long finishTimestamp = -1;

    protected long transactionId;
    protected FailMsg failMsg;
    protected Map<Long, LoadTask> idToTasks = Maps.newConcurrentMap();
    protected Set<Long> finishedTaskIds = Sets.newHashSet();
    protected EtlStatus loadingStatus = new EtlStatus();
    // 0: the job status is pending
    // n/100: n is the number of task which has been finished
    // 99: all of tasks have been finished
    // 100: txn status is visible and load has been finished
    protected int progress;

    // non-persistence
    protected boolean isCommitting = false;
    protected boolean isCancellable = true;

    // only for persistence param
    private boolean isJobTypeRead = false;

    protected ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    // this request id is only used for checking if a load begin request is a duplicate request.
    protected TUniqueId requestId;

    protected LoadStatistic loadStatistic = new LoadStatistic();

    public static class LoadStatistic {
        // number of rows processed on BE, this number will be updated periodically by query report.
        // A load job may has several load tasks, so the map key is load task's plan load id.
        public Map<TUniqueId, AtomicLong> numLoadedRowsMap = Maps.newConcurrentMap();
        // number of file to be loaded
        public int fileNum = 0;
        public long totalFileSizeB = 0;
        
        public String toJson() {
            long total = 0;
            for (AtomicLong atomicLong : numLoadedRowsMap.values()) {
                total += atomicLong.get();
            }

            Map<String, Object> details = Maps.newHashMap();
            details.put("LoadedRows", total);
            details.put("FileNumber", fileNum);
            details.put("FileSize", totalFileSizeB);
            details.put("TaskNumber", numLoadedRowsMap.size());
            Gson gson = new Gson();
            return gson.toJson(details);
        }
    }

    // only for log replay
    public LoadJob() {
    }

    public LoadJob(long dbId, String label) {
        this.id = Catalog.getCurrentCatalog().getNextId();
        this.dbId = dbId;
        this.label = label;
    }

    protected void readLock() {
        lock.readLock().lock();
    }

    protected void readUnlock() {
        lock.readLock().unlock();
    }

    protected void writeLock() {
        lock.writeLock().lock();
    }

    protected void writeUnlock() {
        lock.writeLock().unlock();
    }

    public long getId() {
        return id;
    }

    public Database getDb() throws MetaNotFoundException {
        // get db
        Database db = Catalog.getCurrentCatalog().getDb(dbId);
        if (db == null) {
            throw new MetaNotFoundException("Database " + dbId + " already has been deleted");
        }
        return db;
    }

    public long getDbId() {
        return dbId;
    }

    public String getLabel() {
        return label;
    }

    public JobState getState() {
        return state;
    }

    public long getCreateTimestamp() {
        return createTimestamp;
    }

    public long getDeadlineMs() {
        return createTimestamp + timeoutSecond * 1000;
    }

    public long getLeftTimeMs() {
        return getDeadlineMs() - System.currentTimeMillis();
    }

    public long getFinishTimestamp() {
        return finishTimestamp;
    }

    public long getTransactionId() {
        return transactionId;
    }

    public void updateLoadedRows(TUniqueId loadId, long loadedRows) {
        AtomicLong atomicLong = loadStatistic.numLoadedRowsMap.get(loadId);
        if (atomicLong != null) {
            atomicLong.set(loadedRows);
        }
    }

    public void setLoadFileInfo(int fileNum, long fileSize) {
        this.loadStatistic.fileNum = fileNum;
        this.loadStatistic.totalFileSizeB = fileSize;
    }

    public TUniqueId getRequestId() {
        return requestId;
    }

    /**
     * Show table names for frontend
     * If table name could not be found by id, the table id will be used instead.
     *
     * @return
     */
    abstract Set<String> getTableNamesForShow();

    /**
     * Return the real table names by table ids.
     * The method is invoked by 'checkAuth' when authorization info is null in job.
     * Also it is invoked by 'gatherAuthInfo' which saves the auth info in the constructor of job.
     * Throw MetaNofFoundException when table name could not be found.
     * @return
     */
    abstract Set<String> getTableNames() throws MetaNotFoundException;

    public boolean isCompleted() {
        return state == JobState.FINISHED || state == JobState.CANCELLED;
    }

    protected void setJobProperties(Map<String, String> properties) throws DdlException {
        // resource info
        if (ConnectContext.get() != null) {
            execMemLimit = ConnectContext.get().getSessionVariable().getMaxExecMemByte();
        }

        // job properties
        if (properties != null) {
            if (properties.containsKey(LoadStmt.TIMEOUT_PROPERTY)) {
                try {
                    timeoutSecond = Integer.parseInt(properties.get(LoadStmt.TIMEOUT_PROPERTY));
                } catch (NumberFormatException e) {
                    throw new DdlException("Timeout is not INT", e);
                }
            }

            if (properties.containsKey(LoadStmt.MAX_FILTER_RATIO_PROPERTY)) {
                try {
                    maxFilterRatio = Double.parseDouble(properties.get(LoadStmt.MAX_FILTER_RATIO_PROPERTY));
                } catch (NumberFormatException e) {
                    throw new DdlException("Max filter ratio is not DOUBLE", e);
                }
            }

            if (properties.containsKey(LoadStmt.LOAD_DELETE_FLAG_PROPERTY)) {
                String flag = properties.get(LoadStmt.LOAD_DELETE_FLAG_PROPERTY);
                if (flag.equalsIgnoreCase("true") || flag.equalsIgnoreCase("false")) {
                    deleteFlag = Boolean.parseBoolean(flag);
                } else {
                    throw new DdlException("Value of delete flag is invalid");
                }
            }

            if (properties.containsKey(LoadStmt.EXEC_MEM_LIMIT)) {
                try {
                    execMemLimit = Long.parseLong(properties.get(LoadStmt.EXEC_MEM_LIMIT));
                } catch (NumberFormatException e) {
                    throw new DdlException("Execute memory limit is not Long", e);
                }
            }

            if (properties.containsKey(LoadStmt.STRICT_MODE)) {
                strictMode = Boolean.valueOf(properties.get(LoadStmt.STRICT_MODE));
            }
        }
    }

    protected static void checkDataSourceInfo(Database db, List<DataDescription> dataDescriptions,
            EtlJobType jobType) throws DdlException {
        for (DataDescription dataDescription : dataDescriptions) {
            // loadInfo is a temporary param for the method of checkAndCreateSource.
            // <TableId,<PartitionId,<LoadInfoList>>>
            Map<Long, Map<Long, List<Source>>> loadInfo = Maps.newHashMap();
            // only support broker load now
            Load.checkAndCreateSource(db, dataDescription, loadInfo, false, jobType);
        }
    }

    public void isJobTypeRead(boolean jobTypeRead) {
        isJobTypeRead = jobTypeRead;
    }

    public void beginTxn() throws LabelAlreadyUsedException, BeginTransactionException, AnalysisException {
    }

    /**
     * create pending task for load job and add pending task into pool
     * if job has been cancelled, this step will be ignored
     *
     * @throws LabelAlreadyUsedException the job is duplicated
     * @throws BeginTransactionException the limit of load job is exceeded
     * @throws AnalysisException there are error params in job
     */
    public void execute() throws LabelAlreadyUsedException, BeginTransactionException, AnalysisException {
        writeLock();
        try {
            unprotectedExecute();
        } finally {
            writeUnlock();
        }
    }

    public void unprotectedExecute() throws LabelAlreadyUsedException, BeginTransactionException, AnalysisException {
        // check if job state is pending
        if (state != JobState.PENDING) {
            return;
        }
        // the limit of job will be restrict when begin txn
        beginTxn();
        unprotectedExecuteJob();
        unprotectedUpdateState(JobState.LOADING);
    }

    public void processTimeout() {
        writeLock();
        try {
            if (isCompleted() || getDeadlineMs() >= System.currentTimeMillis() || isCommitting) {
                return;
            }
            unprotectedExecuteCancel(new FailMsg(FailMsg.CancelType.TIMEOUT, "loading timeout to cancel"), true);
        } finally {
            writeUnlock();
        }
        logFinalOperation();
    }

    protected void unprotectedExecuteJob() {
    }

    /**
     * This method only support update state to finished and loading.
     * It will not be persisted when desired state is finished because txn visible will edit the log.
     * If you want to update state to cancelled, please use the cancelJob function.
     *
     * @param jobState
     */
    public void updateState(JobState jobState) {
        writeLock();
        try {
            unprotectedUpdateState(jobState);
        } finally {
            writeUnlock();
        }
    }

    protected void unprotectedUpdateState(JobState jobState) {
        switch (jobState) {
            case LOADING:
                executeLoad();
                break;
            case FINISHED:
                executeFinish();
            default:
                break;
        }
    }

    private void executeLoad() {
        loadStartTimestamp = System.currentTimeMillis();
        state = JobState.LOADING;
    }

    public void cancelJobWithoutCheck(FailMsg failMsg, boolean abortTxn) {
        writeLock();
        try {
            unprotectedExecuteCancel(failMsg, abortTxn);
        } finally {
            writeUnlock();
        }
        logFinalOperation();
    }

    public void cancelJob(FailMsg failMsg) throws DdlException {
        writeLock();
        try {
            // check
            if (!isCancellable) {
                throw new DdlException("Job could not be cancelled in type " + jobType.name());
            }
            if (isCommitting) {
                LOG.warn(new LogBuilder(LogKey.LOAD_JOB, id)
                                 .add("error_msg", "The txn which belongs to job is committing. "
                                         + "The job could not be cancelled in this step").build());
                throw new DdlException("Job could not be cancelled while txn is committing");
            }
            if (isCompleted()) {
                LOG.warn(new LogBuilder(LogKey.LOAD_JOB, id)
                                 .add("state", state)
                                 .add("error_msg", "Job could not be cancelled when job is completed")
                                 .build());
                throw new DdlException("Job could not be cancelled when job is finished or cancelled");
            }

            checkAuth("CANCEL LOAD");
            unprotectedExecuteCancel(failMsg, true);
        } finally {
            writeUnlock();
        }
        logFinalOperation();
    }

    private void checkAuth(String command) throws DdlException {
        if (authorizationInfo == null) {
            // use the old method to check priv
            checkAuthWithoutAuthInfo(command);
            return;
        }
        if (!Catalog.getCurrentCatalog().getAuth().checkPrivByAuthInfo(ConnectContext.get(), authorizationInfo,
                                                                       PrivPredicate.LOAD)) {
            ErrorReport.reportDdlException(ErrorCode.ERR_SPECIFIC_ACCESS_DENIED_ERROR,
                                           PaloPrivilege.LOAD_PRIV);
        }
    }

    /**
     * This method is compatible with old load job without authorization info
     * If db or table name could not be found by id, it will throw the NOT_EXISTS_ERROR
     *
     * @throws DdlException
     */
    private void checkAuthWithoutAuthInfo(String command) throws DdlException {
        Database db = Catalog.getInstance().getDb(dbId);
        if (db == null) {
            ErrorReport.reportDdlException(ErrorCode.ERR_BAD_DB_ERROR, dbId);
        }

        // check auth
        try {
            Set<String> tableNames = getTableNames();
            if (tableNames.isEmpty()) {
                // forward compatibility
                if (!Catalog.getCurrentCatalog().getAuth().checkDbPriv(ConnectContext.get(), db.getFullName(),
                                                                       PrivPredicate.LOAD)) {
                    ErrorReport.reportDdlException(ErrorCode.ERR_SPECIFIC_ACCESS_DENIED_ERROR,
                                                   PaloPrivilege.LOAD_PRIV);
                }
            } else {
                for (String tblName : tableNames) {
                    if (!Catalog.getCurrentCatalog().getAuth().checkTblPriv(ConnectContext.get(), db.getFullName(),
                                                                            tblName, PrivPredicate.LOAD)) {
                        ErrorReport.reportDdlException(ErrorCode.ERR_TABLEACCESS_DENIED_ERROR,
                                                       command,
                                                       ConnectContext.get().getQualifiedUser(),
                                                       ConnectContext.get().getRemoteIP(), tblName);
                    }
                }
            }
        } catch (MetaNotFoundException e) {
            throw new DdlException(e.getMessage());
        }
    }

    /**
     * This method will cancel job without edit log and lock
     *
     * @param failMsg
     * @param abortTxn true: abort txn when cancel job, false: only change the state of job and ignore abort txn
     */
    protected void unprotectedExecuteCancel(FailMsg failMsg, boolean abortTxn) {
        LOG.warn(new LogBuilder(LogKey.LOAD_JOB, id)
                         .add("transaction_id", transactionId)
                         .add("error_msg", "Failed to execute load with error " + failMsg.getMsg())
                         .build());

        // clean the loadingStatus
        loadingStatus.setState(TEtlState.CANCELLED);

        // get load ids of all loading tasks, we will cancel their coordinator process later
        List<TUniqueId> loadIds = Lists.newArrayList();
        for (LoadTask loadTask : idToTasks.values()) {
            if (loadTask instanceof LoadLoadingTask ) {
                loadIds.add(((LoadLoadingTask)loadTask).getLoadId());
            }
        }
        idToTasks.clear();
        loadStatistic.numLoadedRowsMap.clear();

        // set failMsg and state
        this.failMsg = failMsg;
        finishTimestamp = System.currentTimeMillis();

        // remove callback
        Catalog.getCurrentGlobalTransactionMgr().getCallbackFactory().removeCallback(id);
        if (abortTxn) {
            // abort txn
            try {
                LOG.debug(new LogBuilder(LogKey.LOAD_JOB, id)
                                  .add("transaction_id", transactionId)
                                  .add("msg", "begin to abort txn")
                                  .build());
                Catalog.getCurrentGlobalTransactionMgr().abortTransaction(transactionId, failMsg.getMsg());
            } catch (UserException e) {
                LOG.warn(new LogBuilder(LogKey.LOAD_JOB, id)
                                 .add("transaction_id", transactionId)
                                 .add("error_msg", "failed to abort txn when job is cancelled, txn will be aborted later")
                                 .build());
            }
        }
        
        // cancel all running coordinators, so that the scheduler's worker thread will be released
        for (TUniqueId loadId : loadIds) {
            Coordinator coordinator = QeProcessorImpl.INSTANCE.getCoordinator(loadId);
            if (coordinator != null) {
                coordinator.cancel();
            }
        }

        // change state
        state = JobState.CANCELLED;
    }

    private void executeFinish() {
        progress = 100;
        finishTimestamp = System.currentTimeMillis();
        Catalog.getCurrentGlobalTransactionMgr().getCallbackFactory().removeCallback(id);
        state = JobState.FINISHED;

        MetricRepo.COUNTER_LOAD_FINISHED.increase(1L);
    }

    protected boolean checkDataQuality() {
        Map<String, String> counters = loadingStatus.getCounters();
        if (!counters.containsKey(DPP_NORMAL_ALL) || !counters.containsKey(DPP_ABNORMAL_ALL)) {
            return true;
        }

        long normalNum = Long.parseLong(counters.get(DPP_NORMAL_ALL));
        long abnormalNum = Long.parseLong(counters.get(DPP_ABNORMAL_ALL));
        if (abnormalNum > (abnormalNum + normalNum) * maxFilterRatio) {
            return false;
        }

        return true;
    }

    protected void logFinalOperation() {
        Catalog.getCurrentCatalog().getEditLog().logEndLoadJob(
                new LoadJobFinalOperation(id, loadingStatus, progress, loadStartTimestamp, finishTimestamp,
                                          state, failMsg));
    }

    public void unprotectReadEndOperation(LoadJobFinalOperation loadJobFinalOperation) {
        loadingStatus = loadJobFinalOperation.getLoadingStatus();
        progress = loadJobFinalOperation.getProgress();
        loadStartTimestamp = loadJobFinalOperation.getLoadStartTimestamp();
        finishTimestamp = loadJobFinalOperation.getFinishTimestamp();
        state = loadJobFinalOperation.getJobState();
        failMsg = loadJobFinalOperation.getFailMsg();
    }

    public List<Comparable> getShowInfo() throws DdlException {
        readLock();
        try {
            // check auth
            checkAuth("SHOW LOAD");
            List<Comparable> jobInfo = Lists.newArrayList();
            // jobId
            jobInfo.add(id);
            // label
            jobInfo.add(label);
            // state
            jobInfo.add(state.name());

            // progress
            switch (state) {
                case PENDING:
                    jobInfo.add("ETL:N/A; LOAD:0%");
                    break;
                case CANCELLED:
                    jobInfo.add("ETL:N/A; LOAD:N/A");
                    break;
                default:
                    jobInfo.add("ETL:N/A; LOAD:" + progress + "%");
                    break;
            }

            // type
            jobInfo.add(jobType);

            // etl info
            if (loadingStatus.getCounters().size() == 0) {
                jobInfo.add("N/A");
            } else {
                jobInfo.add(Joiner.on("; ").withKeyValueSeparator("=").join(loadingStatus.getCounters()));
            }

            // task info
            jobInfo.add("cluster:N/A" + "; timeout(s):" + timeoutSecond
                                + "; max_filter_ratio:" + maxFilterRatio);

            // error msg
            if (failMsg == null) {
                jobInfo.add("N/A");
            } else {
                jobInfo.add("type:" + failMsg.getCancelType() + "; msg:" + failMsg.getMsg());
            }

            // create time
            jobInfo.add(TimeUtils.longToTimeString(createTimestamp));
            // etl start time
            jobInfo.add(TimeUtils.longToTimeString(loadStartTimestamp));
            // etl end time
            jobInfo.add(TimeUtils.longToTimeString(loadStartTimestamp));
            // load start time
            jobInfo.add(TimeUtils.longToTimeString(loadStartTimestamp));
            // load end time
            jobInfo.add(TimeUtils.longToTimeString(finishTimestamp));
            // tracking url
            jobInfo.add(loadingStatus.getTrackingUrl());
            jobInfo.add(loadStatistic.toJson());
            return jobInfo;
        } finally {
            readUnlock();
        }
    }

    public void getJobInfo(Load.JobInfo jobInfo) throws DdlException {
        checkAuth("SHOW LOAD");
        jobInfo.tblNames.addAll(getTableNamesForShow());
        jobInfo.state = org.apache.doris.load.LoadJob.JobState.valueOf(state.name());
        if (failMsg != null) {
            jobInfo.failMsg = failMsg.getMsg();
        } else {
            jobInfo.failMsg = "";
        }
        jobInfo.trackingUrl = loadingStatus.getTrackingUrl();
    }

    public static LoadJob read(DataInput in) throws IOException {
        LoadJob job = null;
        EtlJobType type = EtlJobType.valueOf(Text.readString(in));
        if (type == EtlJobType.BROKER) {
            job = new BrokerLoadJob();
        } else if (type == EtlJobType.INSERT) {
            job = new InsertLoadJob();
        } else if (type == EtlJobType.MINI) {
            job = new MiniLoadJob();
        } else {
            throw new IOException("Unknown load type: " + type.name());
        }

        job.isJobTypeRead(true);
        job.readFields(in);
        return job;
    }

    @Override
    public long getCallbackId() {
        return id;
    }

    @Override
    public void beforeCommitted(TransactionState txnState) throws TransactionException {
        writeLock();
        try {
            if (isCompleted()) {
                throw new TransactionException("txn could not be committed when job has been cancelled");
            }
            isCommitting = true;
        } finally {
            writeUnlock();
        }
    }

    @Override
    public void afterCommitted(TransactionState txnState, boolean txnOperated) throws UserException {
        if (txnOperated) {
            return;
        }
        writeLock();
        try {
            isCommitting = false;
        } finally {
            writeUnlock();
        }
    }

    @Override
    public void replayOnCommitted(TransactionState txnState) {
        writeLock();
        try {
            isCommitting = true;
        } finally {
            writeUnlock();
        }
    }

    /**
     * This method will cancel job without edit log.
     * The job will be cancelled by replayOnAborted when journal replay
     *
     * @param txnState
     * @param txnOperated
     * @param txnStatusChangeReason
     * @throws UserException
     */
    @Override
    public void afterAborted(TransactionState txnState, boolean txnOperated, String txnStatusChangeReason)
            throws UserException {
        if (!txnOperated) {
            return;
        }
        writeLock();
        try {
            if (isCompleted()) {
                return;
            }
            // record attachment in load job
            executeAfterAborted(txnState);
            // cancel load job
            unprotectedExecuteCancel(new FailMsg(FailMsg.CancelType.LOAD_RUN_FAIL, txnStatusChangeReason), false);
        } finally {
            writeUnlock();
        }
    }

    protected void executeAfterAborted(TransactionState txnState) {
    }

    /**
     * This method is used to replay the cancelled state of load job
     *
     * @param txnState
     */
    @Override
    public void replayOnAborted(TransactionState txnState) {
        writeLock();
        try {
            executeReplayOnAborted(txnState);
            failMsg = new FailMsg(FailMsg.CancelType.LOAD_RUN_FAIL, txnState.getReason());
            finishTimestamp = txnState.getFinishTime();
            state = JobState.CANCELLED;
        } finally {
            writeUnlock();
        }
    }

    protected void executeReplayOnAborted(TransactionState txnState) {
    }

    /**
     * This method will finish the load job without edit log.
     * The job will be finished by replayOnVisible when txn journal replay
     *
     * @param txnState
     * @param txnOperated
     */
    @Override
    public void afterVisible(TransactionState txnState, boolean txnOperated) {
        if (!txnOperated) {
            return;
        }
        executeAfterVisible(txnState);
        updateState(JobState.FINISHED);
    }

    protected void executeAfterVisible(TransactionState txnState) {
    }

    @Override
    public void replayOnVisible(TransactionState txnState) {
        writeLock();
        try {
            executeReplayOnVisible(txnState);
            progress = 100;
            finishTimestamp = txnState.getFinishTime();
            state = JobState.FINISHED;
        } finally {
            writeUnlock();
        }
    }

    protected void executeReplayOnVisible(TransactionState txnState) {
    }

    @Override
    public void onTaskFinished(TaskAttachment attachment) {
    }

    @Override
    public void onTaskFailed(long taskId, FailMsg failMsg) {
    }

    // This analyze will be invoked after the replay is finished.
    // The edit log of LoadJob saves the origin param which is not analyzed.
    // So, the re-analyze must be invoked between the replay is finished and LoadJobScheduler is started.
    // Only, the PENDING load job need to be analyzed.
    public void analyze() {
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        LoadJob other = (LoadJob) obj;

        return this.id == other.id
        && this.dbId == other.dbId
        && this.label.equals(other.label)
        && this.state.equals(other.state)
        && this.jobType.equals(other.jobType);
    }

    @Override
    public void write(DataOutput out) throws IOException {
        // Add the type of load secondly
        Text.writeString(out, jobType.name());

        out.writeLong(id);
        out.writeLong(dbId);
        Text.writeString(out, label);
        Text.writeString(out, state.name());
        out.writeLong(timeoutSecond);
        out.writeLong(execMemLimit);
        out.writeDouble(maxFilterRatio);
        out.writeBoolean(deleteFlag);
        out.writeLong(createTimestamp);
        out.writeLong(loadStartTimestamp);
        out.writeLong(finishTimestamp);
        if (failMsg == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            failMsg.write(out);
        }
        out.writeInt(progress);
        loadingStatus.write(out);
        out.writeBoolean(strictMode);
        out.writeLong(transactionId);
        if (authorizationInfo == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            authorizationInfo.write(out);
        }
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        if (!isJobTypeRead) {
            jobType = EtlJobType.valueOf(Text.readString(in));
            isJobTypeRead = true;
        }

        id = in.readLong();
        dbId = in.readLong();
        label = Text.readString(in);
        state = JobState.valueOf(Text.readString(in));
        if (Catalog.getCurrentCatalogJournalVersion() >= FeMetaVersion.VERSION_54) {
            timeoutSecond = in.readLong();
        } else {
            timeoutSecond = in.readInt();
        }
        execMemLimit = in.readLong();
        maxFilterRatio = in.readDouble();
        deleteFlag = in.readBoolean();
        createTimestamp = in.readLong();
        loadStartTimestamp = in.readLong();
        finishTimestamp = in.readLong();
        if (in.readBoolean()) {
            failMsg = new FailMsg();
            failMsg.readFields(in);
        }
        progress = in.readInt();
        loadingStatus.readFields(in);
        if (Catalog.getCurrentCatalogJournalVersion() >= FeMetaVersion.VERSION_54) {
            strictMode = in.readBoolean();
            transactionId = in.readLong();
        }
        if (Catalog.getCurrentCatalogJournalVersion() >= FeMetaVersion.VERSION_56) {
            if (in.readBoolean()) {
                authorizationInfo = new AuthorizationInfo();
                authorizationInfo.readFields(in);
            }
        }
    }
}

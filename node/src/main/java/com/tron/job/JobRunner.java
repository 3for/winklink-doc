package com.tron.job;

import com.tron.common.Constant;
import com.tron.job.adaptes.AdapterManager;
import com.tron.job.adaptes.BaseAdapter;
import com.tron.web.common.util.R;
import com.tron.web.entity.Initiator;
import com.tron.web.entity.JobRun;
import com.tron.web.entity.JobSpec;
import com.tron.web.entity.TaskRun;
import com.tron.web.entity.TaskSpec;
import com.tron.web.entity.TronTx;
import com.tron.web.service.JobRunsService;
import com.tron.web.service.JobSpecsService;
import com.tron.client.EventRequest;
import com.tron.web.service.TronTxService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JobRunner {
  @Autowired
  JobSpecsService jobSpecsService;
  @Autowired
  JobRunsService jobRunsService;
  @Autowired
  TronTxService tronTxService;

  public List<Initiator> getAllJobInitiatorList() {
    List<Initiator> initiators = new ArrayList<>();
    List<JobSpec> jobSpecs = jobSpecsService.getAllJob();
    for (JobSpec jobSpec : jobSpecs) {
      if (jobSpec.getDeletedAt() != null) {
        continue;
      }
      List<Initiator> jobInitiators = jobSpecsService.getInitiatorsByJobId(jobSpec.getId());
      initiators.add(jobInitiators.get(0));
    }
    return initiators;
  }

  public void addJobRun(EventRequest event) {

    try {
      JobSpec job = jobSpecsService.getById(event.getJobId());

      // check run
      boolean checkResult = validateRun(job, event);

      if (checkResult) {
        JobRun jobRun = new JobRun();
        String jobRunId = UUID.randomUUID().toString();
        jobRunId = jobRunId.replaceAll("-", "");
        jobRun.setId(jobRunId);
        jobRun.setJobSpecID(event.getJobId());
        jobRun.setStatus(1);
        jobRun.setCreationHeight(event.getBlockNum());
        jobRun.setPayment(event.getPayment());
        jobRun.setInitiatorId(job.getInitiators().get(0).getId());
        String params = com.tron.web.common.util.JsonUtil.obj2String(event);
        jobRun.setParams(params);
        jobRun.setRequestId(event.getRequestId());

        jobRunsService.insert(jobRun);

        for (TaskSpec task : job.getTaskSpecs()) {
          TaskRun taskRun = new TaskRun();
          String taskRunId = UUID.randomUUID().toString();
          taskRunId = taskRunId.replaceAll("-", "");
          taskRun.setId(taskRunId);
          taskRun.setJobRunID(jobRunId);
          taskRun.setTaskSpecId(task.getId());
          jobRunsService.insertTaskRun(taskRun);
        }

        run(jobRun, params);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void run(JobRun jobRun, String params) {
    new Thread(() -> {
      try {
        execute(jobRun.getId(), params);
      } catch (Exception e) {
        //TODO
        e.printStackTrace();
      }
    }, "ExecuteJobRun").start();
  }

  private void execute(String runId, String params) {
    try {
      JobRun jobRun = jobRunsService.getById(runId);
      List<TaskRun> taskRuns = jobRunsService.getTaskRunsByJobRunId(runId);
      jobRun.setTaskRuns(taskRuns);

      R preTaskResult = new R();
      preTaskResult.put("params", params);
      preTaskResult.put("result", null);
      preTaskResult.put("jobRunId", runId);
      preTaskResult.put("taskRunId", "");
      for (TaskRun taskRun : taskRuns) {
        preTaskResult.replace("taskRunId", taskRun.getId());
        TaskSpec taskSpec = jobSpecsService.getTasksById(taskRun.getTaskSpecId());
        R result = executeTask(taskRun, taskSpec, preTaskResult);

        if (result.get("code").equals(0)) {
          preTaskResult.replace("result", result.get("result"));
        } else {
          log.error(taskSpec.getType() + " run failed");
          preTaskResult.replace("code", result.get("code"));
          preTaskResult.replace("msg", result.get("msg"));
          break;
        }
      }

      // update job run
      if (preTaskResult.get("code").equals(0)) {
        jobRunsService.updateJobResult(runId, 2, null, null);
      } else {
        jobRunsService.updateJobResult(runId, 3, null, String.valueOf(preTaskResult.get("msg")));
      }
    } catch (Exception e) {
      e.printStackTrace();
    }


  }

  private R executeTask(TaskRun taskRun, TaskSpec taskSpec, R input) {
    BaseAdapter adapter = AdapterManager.getAdapter(taskSpec);
    R result = adapter.perform(input);

    // update task run
    if (result.get("code").equals(0)) {
      String resultStr = String.valueOf(result.get("result"));
      jobRunsService.updateTaskResult(taskRun.getId(), 2, resultStr, null);

      if (taskSpec.getType().equals(Constant.TASK_TYPE_TRON_TX)) {
        tronTxService.insert((TronTx)result.get("tx"));
      }
    } else {
      jobRunsService.updateTaskResult(taskRun.getId(), 3, null, String.valueOf(result.get("msg")));
    }

    return result;
  }

  private boolean validateRun(JobSpec jobSpec, EventRequest event) {
    if (jobSpec == null) {
      log.warn("failed to find job spec, ID: " + event.getJobId());
      return false;
    }

    if (jobSpec.archived()) {
      log.warn("Trying to run archived job " + jobSpec.getId());
      return false;
    }

    if (!event.getContractAddr().equals(jobSpec.getInitiators().get(0).getAddress())) {
      log.error("Contract address({}) in event do not match the log subscriber address({})",
          event.getContractAddr(), jobSpec.getInitiators().get(0).getAddress());
      return false;
    }

    Long minPayment;
    if (jobSpec.getMinPayment() == null) {
      minPayment = 1L;
    } else {
      minPayment = jobSpec.getMinPayment();
    }

    if (event.getPayment() > 0 && minPayment.compareTo(event.getPayment()) > 0) {
      log.warn("rejecting job {} with payment {} below minimum threshold ({})", event.getJobId(), event.getPayment(), minPayment);
      return false;
    }

    // TODO repeated requestId check

    return true;
  }
}

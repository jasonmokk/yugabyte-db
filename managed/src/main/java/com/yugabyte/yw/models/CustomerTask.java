// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.models;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.yugabyte.yw.common.PlatformServiceException;
import io.ebean.Finder;
import io.ebean.Model;
import io.ebean.annotation.EnumValue;
import io.ebean.annotation.Transactional;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.validation.Constraints;
import static io.swagger.annotations.ApiModelProperty.AccessMode.READ_ONLY;
import static play.mvc.Http.Status.BAD_REQUEST;

@Entity
@ApiModel(
    description = "Customer task information. A customer task has a _target_ and a _task type_.")
public class CustomerTask extends Model {
  public static final Logger LOG = LoggerFactory.getLogger(CustomerTask.class);

  public enum TargetType {
    @EnumValue("Universe")
    Universe(true),

    @EnumValue("Cluster")
    Cluster(true),

    @EnumValue("Table")
    Table(true),

    @EnumValue("Provider")
    Provider(false),

    @EnumValue("Node")
    Node(true),

    @EnumValue("Backup")
    Backup(false),

    @EnumValue("KMS Configuration")
    KMSConfiguration(false),

    @EnumValue("XCluster Configuration")
    XClusterConfig(false);

    private final boolean universeTarget;

    TargetType(boolean universeTarget) {
      this.universeTarget = universeTarget;
    }

    public boolean isUniverseTarget() {
      return universeTarget;
    }
  }

  public enum TaskType {
    @EnumValue("Create")
    Create,

    @EnumValue("Pause")
    Pause,

    @EnumValue("Resume")
    Resume,

    @EnumValue("Update")
    Update,

    @EnumValue("Delete")
    Delete,

    @EnumValue("Stop")
    Stop,

    @EnumValue("Start")
    Start,

    @EnumValue("Restart")
    Restart,

    @EnumValue("Remove")
    Remove,

    @EnumValue("Add")
    Add,

    @EnumValue("Release")
    Release,

    @EnumValue("RestartUniverse")
    RestartUniverse,

    @EnumValue("SoftwareUpgrade")
    SoftwareUpgrade,

    @EnumValue("GFlagsUpgrade")
    GFlagsUpgrade,

    @EnumValue("CertsRotate")
    CertsRotate,

    @EnumValue("TlsToggle")
    TlsToggle,

    @EnumValue("VMImageUpgrade")
    VMImageUpgrade,

    @EnumValue("SystemdUpgrade")
    SystemdUpgrade,

    @Deprecated
    @EnumValue("UpgradeSoftware")
    UpgradeSoftware,

    @Deprecated
    @EnumValue("UpgradeVMImage")
    UpgradeVMImage,

    @EnumValue("ResizeNode")
    ResizeNode,

    @Deprecated
    @EnumValue("UpdateCert")
    UpdateCert,

    @Deprecated
    @EnumValue("ToggleTls")
    ToggleTls,

    @EnumValue("UpdateDiskSize")
    UpdateDiskSize,

    @Deprecated
    @EnumValue("UpgradeGflags")
    UpgradeGflags,

    @EnumValue("BulkImportData")
    BulkImportData,

    @Deprecated
    @EnumValue("Backup")
    Backup,

    @EnumValue("Restore")
    Restore,

    @Deprecated
    @EnumValue("SetEncryptionKey")
    SetEncryptionKey,

    @EnumValue("EnableEncryptionAtRest")
    EnableEncryptionAtRest,

    @EnumValue("RotateEncryptionKey")
    RotateEncryptionKey,

    @EnumValue("DisableEncryptionAtRest")
    DisableEncryptionAtRest,

    @EnumValue("StartMaster")
    StartMaster,

    @EnumValue("CreateAlertDefinitions")
    CreateAlertDefinitions,

    @EnumValue("ExternalScript")
    ExternalScript,

    @EnumValue("CreateXClusterReplication")
    CreateXClusterReplication,

    @EnumValue("CreateXClusterConfig")
    CreateXClusterConfig,

    @EnumValue("DeleteXClusterReplication")
    DeleteXClusterReplication,

    @EnumValue("DeleteXClusterConfig")
    DeleteXClusterConfig,

    @EnumValue("EditXClusterReplication")
    EditXClusterReplication,

    @EnumValue("EditXClusterConfig")
    EditXClusterConfig,

    @EnumValue("PauseXClusterReplication")
    PauseXClusterReplication,

    @EnumValue("ResumeXClusterReplication")
    ResumeXClusterReplication;

    public String toString(boolean completed) {
      switch (this) {
        case Create:
          return completed ? "Created " : "Creating ";
        case Pause:
          return completed ? "Paused " : "Pausing ";
        case Resume:
          return completed ? "Resumed " : "Resuming ";
        case Update:
          return completed ? "Updated " : "Updating ";
        case Delete:
          return completed ? "Deleted " : "Deleting ";
        case RestartUniverse:
          return completed ? "Restarted " : "Restarting ";
        case SoftwareUpgrade:
          return completed ? "Upgraded Software " : "Upgrading Software ";
        case SystemdUpgrade:
          return completed ? "Upgraded to Systemd " : "Upgrading to Systemd ";
        case GFlagsUpgrade:
          return completed ? "Upgraded GFlags " : "Upgrading GFlags ";
        case CertsRotate:
          return completed ? "Updated Certificates " : "Updating Certificates ";
        case TlsToggle:
          return completed ? "Toggled TLS " : "Toggling TLS ";
        case VMImageUpgrade:
          return completed ? "Upgraded VM Image " : "Upgrading VM Image ";
        case UpgradeSoftware:
          return completed ? "Upgraded Software " : "Upgrading Software ";
        case UpdateCert:
          return completed ? "Updated Cert " : "Updating Cert ";
        case ToggleTls:
          return completed ? "Toggled Tls " : "Toggling Tls ";
        case UpgradeGflags:
          return completed ? "Upgraded GFlags " : "Upgrading GFlags ";
        case BulkImportData:
          return completed ? "Bulk imported data" : "Bulk importing data";
        case Restore:
          return completed ? "Restored " : "Restoring ";
        case Restart:
          return completed ? "Restarted " : "Restarting ";
        case Backup:
          return completed ? "Backed up " : "Backing up ";
        case SetEncryptionKey:
          return completed ? "Set encryption key" : "Setting encryption key";
        case EnableEncryptionAtRest:
          return completed ? "Enabled encryption at rest" : "Enabling encryption at rest";
        case RotateEncryptionKey:
          return completed
              ? "Rotated encryption at rest universe key"
              : "Rotating encryption at rest universe key";
        case DisableEncryptionAtRest:
          return completed ? "Disabled encryption at rest" : "Disabling encryption at rest";
        case StartMaster:
          return completed ? "Started Master process on " : "Starting Master process on ";
        case CreateAlertDefinitions:
          return completed ? "Created alert definitions " : "Creating alert definitions ";
        case ExternalScript:
          return completed ? "Script execution completed " : "Script execution is running";
        case CreateXClusterReplication:
          return completed ? "Created xCluster replication " : "Creating xCluster replication ";
        case DeleteXClusterReplication:
          return completed ? "Deleted xCluster replication " : "Deleting xCluster replication ";
        case EditXClusterReplication:
          return completed ? "Edited xCluster replication " : "Editing xCluster replication ";
        case PauseXClusterReplication:
          return completed ? "Paused xCluster replication " : "Pausing xCluster replication ";
        case ResumeXClusterReplication:
          return completed ? "Resumed xCluster replication " : "Resuming xCluster replication ";
        default:
          return null;
      }
    }

    public static List<TaskType> filteredValues() {
      return Arrays.stream(TaskType.values())
          .filter(
              value -> {
                try {
                  Field field = TaskType.class.getField(value.name());
                  return !field.isAnnotationPresent(Deprecated.class);
                } catch (Exception e) {
                  return false;
                }
              })
          .collect(Collectors.toList());
    }

    public String getFriendlyName() {
      switch (this) {
        case StartMaster:
          return "Start Master Process on";
        default:
          return toFriendlyTypeName();
      }
    }

    private String toFriendlyTypeName() {
      return name().replaceAll("([a-z]+)([A-Z]+)", "$1 $2");
    }
  }

  // Use IDENTITY strategy because `customer_task.id` is a `bigserial` type; not a sequence.
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @ApiModelProperty(value = "Customer task UUID", accessMode = READ_ONLY)
  private Long id;

  public Long getId() {
    return id;
  }

  @Constraints.Required
  @Column(nullable = false)
  @ApiModelProperty(value = "Customer UUID", accessMode = READ_ONLY, required = true)
  private UUID customerUUID;

  public UUID getCustomerUUID() {
    return customerUUID;
  }

  @Constraints.Required
  @Column(nullable = false)
  @ApiModelProperty(value = "Task UUID", accessMode = READ_ONLY, required = true)
  private UUID taskUUID;

  public UUID getTaskUUID() {
    return taskUUID;
  }

  @Constraints.Required
  @Column(nullable = false)
  @ApiModelProperty(value = "Task type", accessMode = READ_ONLY, required = true)
  private TaskType type;

  public TaskType getType() {
    return type;
  }

  @Constraints.Required
  @Column(nullable = false)
  @ApiModelProperty(value = "Task target type", accessMode = READ_ONLY, required = true)
  private TargetType targetType;

  public TargetType getTarget() {
    return targetType;
  }

  @Constraints.Required
  @Column(nullable = false)
  @ApiModelProperty(value = "Task target name", accessMode = READ_ONLY, required = true)
  private String targetName;

  public String getTargetName() {
    return targetName;
  }

  @Constraints.Required
  @Column(nullable = false)
  @ApiModelProperty(value = "Task target UUID", accessMode = READ_ONLY, required = true)
  private UUID targetUUID;

  public UUID getTargetUUID() {
    return targetUUID;
  }

  @Constraints.Required
  @Column(nullable = false)
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
  @ApiModelProperty(
      value = "Creation time",
      accessMode = READ_ONLY,
      example = "2021-06-17 15:00:05",
      required = true)
  private Date createTime;

  public Date getCreateTime() {
    return createTime;
  }

  @Column
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
  @ApiModelProperty(
      value = "Completion time (present only if a task has completed)",
      accessMode = READ_ONLY,
      example = "2021-06-17 15:00:05")
  private Date completionTime;

  public Date getCompletionTime() {
    return completionTime;
  }

  public void markAsCompleted() {
    markAsCompleted(new Date());
  }

  @VisibleForTesting
  void markAsCompleted(Date completionTime) {
    if (this.completionTime == null) {
      this.completionTime = completionTime;
      this.save();
    }
  }

  public static final Finder<Long, CustomerTask> find =
      new Finder<Long, CustomerTask>(CustomerTask.class) {};

  public static CustomerTask create(
      Customer customer,
      UUID targetUUID,
      UUID taskUUID,
      TargetType targetType,
      TaskType type,
      String targetName) {
    CustomerTask th = new CustomerTask();
    th.customerUUID = customer.uuid;
    th.targetUUID = targetUUID;
    th.taskUUID = taskUUID;
    th.targetType = targetType;
    th.type = type;
    th.targetName = targetName;
    th.createTime = new Date();
    th.save();
    return th;
  }

  public static CustomerTask get(Long id) {
    return CustomerTask.find.query().where().idEq(id).findOne();
  }

  @Deprecated
  public static CustomerTask get(UUID customerUUID, UUID taskUUID) {
    return CustomerTask.find
        .query()
        .where()
        .eq("customer_uuid", customerUUID)
        .eq("task_uuid", taskUUID)
        .findOne();
  }

  public static CustomerTask getOrBadRequest(UUID customerUUID, UUID taskUUID) {
    CustomerTask customerTask = get(customerUUID, taskUUID);
    if (customerTask == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Invalid Customer Task UUID: " + taskUUID);
    }
    return customerTask;
  }

  public String getFriendlyDescription() {
    StringBuilder sb = new StringBuilder();
    sb.append(type.toString(completionTime != null));
    sb.append(targetType.name());
    sb.append(" : ").append(targetName);
    return sb.toString();
  }

  /**
   * deletes customer_task, task_info and all its subtasks of a given task. Assumes task_info tree
   * is one level deep. If this assumption changes then this code needs to be reworked to recurse.
   * When successful; it deletes at least 2 rows because there is always customer_task and
   * associated task_info row that get deleted.
   *
   * @return number of rows deleted. ==0 - if deletion was skipped due to data integrity issues. >=2
   *     - number of rows deleted
   */
  @Transactional
  public int cascadeDeleteCompleted() {
    Preconditions.checkNotNull(
        completionTime, String.format("CustomerTask %s has not completed", id));
    TaskInfo rootTaskInfo = TaskInfo.get(taskUUID);
    if (!rootTaskInfo.hasCompleted()) {
      LOG.warn(
          "Completed CustomerTask(id:{}, type:{}) has incomplete task_info {}",
          id,
          type,
          rootTaskInfo);
      return 0;
    }
    List<TaskInfo> subTasks = rootTaskInfo.getSubTasks();
    List<TaskInfo> incompleteSubTasks =
        subTasks.stream().filter(taskInfo -> !taskInfo.hasCompleted()).collect(Collectors.toList());
    if (rootTaskInfo.getTaskState() == TaskInfo.State.Success && !incompleteSubTasks.isEmpty()) {
      LOG.warn(
          "For a customer_task.id: {}, Successful task_info.uuid ({}) has {} incomplete subtasks {}",
          id,
          rootTaskInfo.getTaskUUID(),
          incompleteSubTasks.size(),
          incompleteSubTasks);
      return 0;
    }
    // Note: delete leaf nodes first to preserve referential integrity.
    subTasks.forEach(Model::delete);
    rootTaskInfo.delete();
    this.delete();
    return 2 + subTasks.size();
  }

  public static CustomerTask findByTaskUUID(UUID taskUUID) {
    return find.query().where().eq("task_uuid", taskUUID).findOne();
  }

  public static List<CustomerTask> findOlderThan(Customer customer, Duration duration) {
    Date cutoffDate = new Date(Instant.now().minus(duration).toEpochMilli());
    return find.query()
        .where()
        .eq("customerUUID", customer.uuid)
        .le("completion_time", cutoffDate)
        .findList();
  }

  public static List<CustomerTask> findIncompleteByTargetUUID(UUID targetUUID) {
    return find.query().where().eq("target_uuid", targetUUID).isNull("completion_time").findList();
  }

  public static CustomerTask getLatestByUniverseUuid(UUID universeUUID) {
    List<CustomerTask> tasks =
        find.query()
            .where()
            .eq("target_uuid", universeUUID)
            .isNotNull("completion_time")
            .orderBy("completion_time desc")
            .setMaxRows(1)
            .findList();
    if (tasks.size() > 0) {
      return tasks.get(0);
    } else {
      return null;
    }
  }

  public String getNotificationTargetName() {
    if (getType().equals(TaskType.Create) && getTarget().equals(TargetType.Backup)) {
      return Universe.getOrBadRequest(getTargetUUID()).name;
    } else {
      return getTargetName();
    }
  }
}

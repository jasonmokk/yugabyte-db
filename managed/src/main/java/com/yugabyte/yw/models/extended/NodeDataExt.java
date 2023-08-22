package com.yugabyte.yw.models.extended;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.yugabyte.yw.models.HealthCheck.Details.NodeData;
import com.yugabyte.yw.models.common.YbaApi;
import com.yugabyte.yw.models.common.YbaApi.YbaApiVisibility;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@ApiModel(description = "Health check results for node")
public class NodeDataExt {

  @JsonUnwrapped private NodeData nodeData;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
  @ApiModelProperty(value = "Deprecated since YBA version 2.17.2.0. Use timestampIso instead")
  @YbaApi(visibility = YbaApiVisibility.DEPRECATED, sinceYBAVersion = "2.17.2.0")
  private Date timestamp;
}

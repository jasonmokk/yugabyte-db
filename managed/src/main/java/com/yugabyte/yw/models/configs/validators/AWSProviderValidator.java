// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.models.configs.validators;

import static play.mvc.Http.Status.BAD_REQUEST;

import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.Subnet;
import com.google.inject.Singleton;
import com.yugabyte.yw.cloud.aws.AWSCloudImpl;
import com.yugabyte.yw.common.BeanValidator;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.config.RuntimeConfGetter;
import com.yugabyte.yw.models.AccessKey;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.helpers.provider.AWSCloudInfo;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import org.apache.commons.lang3.StringUtils;

@Singleton
public class AWSProviderValidator extends ProviderFieldsValidator {

  private final AWSCloudImpl awsCloudImpl;
  private final RuntimeConfGetter runtimeConfigGetter;

  @Inject
  public AWSProviderValidator(
      BeanValidator beanValidator,
      AWSCloudImpl awsCloudImpl,
      RuntimeConfGetter runtimeConfigGetter) {
    super(beanValidator, runtimeConfigGetter);
    this.awsCloudImpl = awsCloudImpl;
    this.runtimeConfigGetter = runtimeConfigGetter;
  }

  @Override
  public void validate(Provider provider) {
    // To guarantee that we can safely fall back to the default provider,
    // the user should either submit both keys and their secret or leave them as null.
    checkMissingKeys(provider);

    // validate access
    try {
      awsCloudImpl.getStsClientOrBadRequest(provider);
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        if (awsCloudImpl.checkKeysExists(provider)) {
          throwBeanValidatorError("KEYS", e.getMessage());
        } else {
          throwBeanValidatorError("IAM", e.getMessage());
        }
      }
      throw e;
    }

    // validate SSH private key content
    try {
      if (provider.allAccessKeys != null && provider.allAccessKeys.size() > 0) {
        for (AccessKey accessKey : provider.allAccessKeys) {
          String privateKeyContent = accessKey.getKeyInfo().sshPrivateKeyContent;
          if (!awsCloudImpl.getPrivateKeyOrBadRequest(privateKeyContent).equals("RSA")) {
            throwBeanValidatorError("SSH_PRIVATE_KEY_CONTENT", "Please provide a valid RSA key");
          }
        }
      }
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        throwBeanValidatorError("SSH_PRIVATE_KEY_CONTENT", e.getMessage());
      }
      throw e;
    }

    // validate NTP Servers
    if (provider.details != null && provider.details.ntpServers != null) {
      validateNTPServers(provider.details.ntpServers);
    }

    // validate hosted zone id
    try {
      String hostedZoneId = provider.details.cloudInfo.aws.awsHostedZoneId;
      if (!StringUtils.isEmpty(hostedZoneId)) {
        awsCloudImpl.getHostedZoneOrBadRequest(provider, hostedZoneId);
      }
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        throwBeanValidatorError("HOSTED_ZONE", e.getMessage());
      }
      throw e;
    }

    // validate Region and its details
    if (provider.regions != null) {
      for (Region region : provider.regions) {
        validateAMI(provider, region);
        validateVpc(provider, region);
        validateSgAndPort(provider, region);
        validateSubnets(provider, region);
        dryRun(provider, region);
      }
    } else {
      throwBeanValidatorError("REGION", "Provider must have at least one region");
    }
  }

  private void dryRun(Provider provider, Region region) {
    String fieldDetails = "DRY_RUN." + region.code;
    try {
      awsCloudImpl.dryRunDescribeInstanceOrBadRequest(provider, region.code);
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        throwBeanValidatorError(fieldDetails, e.getMessage());
      }
      throw e;
    }
  }

  private void validateAMI(Provider provider, Region region) {
    String imageId = region.getYbImage();
    String fieldDetails = "REGION." + region.code + "." + "IMAGE";
    try {
      if (!StringUtils.isEmpty(imageId)) {
        Image image = awsCloudImpl.describeImageOrBadRequest(provider, region, imageId);
        String arch = image.getArchitecture().toLowerCase();
        List<String> supportedArch =
            runtimeConfigGetter.getStaticConf().getStringList("yb.aws.supported_arch_types");
        if (!supportedArch.contains(arch)) {
          throwBeanValidatorError(
              fieldDetails, arch + " arch on image " + imageId + " is not supported");
        }
        List<String> supportedRootDeviceType =
            runtimeConfigGetter.getStaticConf().getStringList("yb.aws.supported_root_device_type");
        String rootDeviceType = image.getRootDeviceType().toLowerCase();
        if (!supportedRootDeviceType.contains(rootDeviceType)) {
          throwBeanValidatorError(
              fieldDetails,
              rootDeviceType + " root device type on image " + imageId + " is not supported");
        }
        List<String> supportedPlatform =
            runtimeConfigGetter.getStaticConf().getStringList("yb.aws.supported_platform");
        String platformDetails = image.getPlatformDetails().toLowerCase();
        if (!supportedPlatform.stream().anyMatch(platform -> platformDetails.contains(platform))) {
          throwBeanValidatorError(
              fieldDetails,
              platformDetails + " platform on image " + imageId + " is not supported");
        }
      }
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        throwBeanValidatorError(fieldDetails, e.getMessage());
      }
      throw e;
    }
  }

  private void validateVpc(Provider provider, Region region) {
    String fieldDetails = "REGION." + region.code + ".VPC";
    try {
      if (!StringUtils.isEmpty(region.getVnetName())) {
        awsCloudImpl.describeVpcOrBadRequest(provider, region);
      }
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        throwBeanValidatorError(fieldDetails, e.getMessage());
      }
      throw e;
    }
  }

  private void validateSgAndPort(Provider provider, Region region) {
    String fieldDetails = "REGION." + region.code + ".SECURITY_GROUP";

    try {
      if (!StringUtils.isEmpty(region.getSecurityGroupId())) {
        SecurityGroup securityGroup =
            awsCloudImpl.describeSecurityGroupsOrBadRequest(provider, region);
        Integer sshPort = provider.getProviderDetails().sshPort;
        boolean portOpen = false;
        for (IpPermission ipPermission : securityGroup.getIpPermissions()) {
          Integer fromPort = ipPermission.getFromPort();
          Integer toPort = ipPermission.getToPort();
          if (fromPort == null || toPort == null) {
            continue;
          }
          if (fromPort <= sshPort && toPort >= sshPort) {
            portOpen = true;
            break;
          }
        }
        if (!portOpen) {
          throwBeanValidatorError(
              fieldDetails,
              sshPort + " is not open on security group " + region.getSecurityGroupId());
        }
      }
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        throwBeanValidatorError(fieldDetails, e.getMessage());
      }
      throw e;
    }
  }

  private void validateSubnets(Provider provider, Region region) {
    String fieldDetails = "REGION." + region.code + ".SUBNETS";
    String regionVnetName = region.getVnetName();
    try {
      if (!StringUtils.isEmpty(region.getSecurityGroupId())) {
        List<Subnet> subnets = awsCloudImpl.describeSubnetsOrBadRequest(provider, region);
        Set<String> cidrBlocks = new HashSet<>();
        for (Subnet subnet : subnets) {
          if (cidrBlocks.contains(subnet.getCidrBlock())) {
            throwBeanValidatorError(
                fieldDetails, "Please provide non-overlapping CIDR blocks subnets");
          }
          if (!subnet.getVpcId().equals(regionVnetName)) {
            throwBeanValidatorError(
                fieldDetails, subnet.getSubnetId() + "is not associated with " + regionVnetName);
          }
          cidrBlocks.add(subnet.getCidrBlock());
        }
      }
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        throwBeanValidatorError(fieldDetails, e.getMessage());
      }
      throw e;
    }
  }

  private void checkMissingKeys(Provider provider) {
    AWSCloudInfo cloudInfo = provider.getProviderDetails().getCloudInfo().getAws();
    String accessKey = cloudInfo.awsAccessKeyID;
    String accessKeySecret = cloudInfo.awsAccessKeySecret;
    if ((StringUtils.isEmpty(accessKey) && !StringUtils.isEmpty(accessKeySecret))
        || (!StringUtils.isEmpty(accessKey) && StringUtils.isEmpty(accessKeySecret))) {
      throwBeanValidatorError("KEYS", "Please provide both access key and its secret");
    }
  }
}

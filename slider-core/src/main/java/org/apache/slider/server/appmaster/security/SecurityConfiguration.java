/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.slider.server.appmaster.security;

import com.google.common.base.Preconditions;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.slider.common.SliderExitCodes;
import org.apache.slider.common.SliderKeys;
import org.apache.slider.common.SliderXmlConfKeys;
import org.apache.slider.common.tools.SliderFileSystem;
import org.apache.slider.common.tools.SliderUtils;
import org.apache.slider.core.conf.AggregateConf;
import org.apache.slider.core.exceptions.SliderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 *
 */
public class SecurityConfiguration {

  protected static final Logger log =
      LoggerFactory.getLogger(SecurityConfiguration.class);
  private final Configuration configuration;
  private final AggregateConf instanceDefinition;
  private String clusterName;

  public SecurityConfiguration(Configuration configuration,
                               AggregateConf instanceDefinition,
                               String clusterName) throws SliderException {
    Preconditions.checkNotNull(configuration);
    Preconditions.checkNotNull(instanceDefinition);
    Preconditions.checkNotNull(clusterName);
    this.configuration = configuration;
    this.instanceDefinition = instanceDefinition;
    this.clusterName = clusterName;
    validate();
  }

  private void validate() throws SliderException {
    if (isSecurityEnabled()) {
      String principal = instanceDefinition.getAppConfOperations()
          .getComponent(SliderKeys.COMPONENT_AM).get(SliderXmlConfKeys.KEY_KEYTAB_PRINCIPAL);
      if(SliderUtils.isUnset(principal)) {
        // if no login identity is available, fail
        UserGroupInformation loginUser = null;
        try {
          loginUser = getLoginUser();
        } catch (IOException e) {
          throw new SliderException(SliderExitCodes.EXIT_BAD_STATE, e,
                                    "No principal configured for the application and "
                                    + "exception raised during retrieval of login user. "
                                    + "Unable to proceed with application "
                                    + "initialization.  Please ensure a value "
                                    + "for %s exists in the application "
                                    + "configuration or the login issue is addressed",
                                    SliderXmlConfKeys.KEY_KEYTAB_PRINCIPAL);
        }
        if (loginUser == null) {
          throw new SliderException(SliderExitCodes.EXIT_BAD_CONFIGURATION,
                                    "No principal configured for the application "
                                    + "and no login user found. "
                                    + "Unable to proceed with application "
                                    + "initialization.  Please ensure a value "
                                    + "for %s exists in the application "
                                    + "configuration or the login issue is addressed",
                                    SliderXmlConfKeys.KEY_KEYTAB_PRINCIPAL);
        }
      }
      // ensure that either local or distributed keytab mechanism is enabled,
      // but not both
      String keytabFullPath = instanceDefinition.getAppConfOperations()
          .getComponent(SliderKeys.COMPONENT_AM)
          .get(SliderXmlConfKeys.KEY_AM_KEYTAB_LOCAL_PATH);
      String keytabName = instanceDefinition.getAppConfOperations()
          .getComponent(SliderKeys.COMPONENT_AM)
          .get(SliderXmlConfKeys.KEY_AM_LOGIN_KEYTAB_NAME);
      if (SliderUtils.isUnset(keytabFullPath) && SliderUtils.isUnset(keytabName)) {
        throw new SliderException(SliderExitCodes.EXIT_BAD_CONFIGURATION,
                                  "Either a keytab path on the cluster host (%s) or a"
                                  + " keytab to be retrieved from HDFS (%s) are"
                                  + " required.  Please configure one of the keytab"
                                  + " retrieval mechanisms.",
                                  SliderXmlConfKeys.KEY_AM_KEYTAB_LOCAL_PATH,
                                  SliderXmlConfKeys.KEY_AM_LOGIN_KEYTAB_NAME);
      }
      if (SliderUtils.isSet(keytabFullPath) && SliderUtils.isSet(keytabName)) {
        throw new SliderException(SliderExitCodes.EXIT_BAD_CONFIGURATION,
                                  "Both a keytab on the cluster host (%s) and a"
                                  + " keytab to be retrieved from HDFS (%s) are"
                                  + " specified.  Please configure only one keytab"
                                  + " retrieval mechanism.",
                                  SliderXmlConfKeys.KEY_AM_KEYTAB_LOCAL_PATH,
                                  SliderXmlConfKeys.KEY_AM_LOGIN_KEYTAB_NAME);

      }
    }
  }

  protected UserGroupInformation getLoginUser() throws IOException {
    return UserGroupInformation.getLoginUser();
  }

  public boolean isSecurityEnabled () {
    return SliderUtils.isHadoopClusterSecure(configuration);
  }

  public String getPrincipal () throws IOException {
    String principal = instanceDefinition.getAppConfOperations()
        .getComponent(SliderKeys.COMPONENT_AM).get(SliderXmlConfKeys.KEY_KEYTAB_PRINCIPAL);
    if (SliderUtils.isUnset(principal)) {
      principal = UserGroupInformation.getLoginUser().getShortUserName();
      log.info("No principal set in the slider configuration.  Will use AM login"
               + " identity {} to attempt keytab-based login", principal);
    }

    return principal;
  }

  public File getKeytabFile(SliderFileSystem fs,
                             AggregateConf instanceDefinition, String principal)
      throws SliderException, IOException {
    String keytabFullPath = instanceDefinition.getAppConfOperations()
        .getComponent(SliderKeys.COMPONENT_AM)
        .get(SliderXmlConfKeys.KEY_AM_KEYTAB_LOCAL_PATH);
    File localKeytabFile;
    if (SliderUtils.isUnset(keytabFullPath)) {
      // get the keytab
      String keytabName = instanceDefinition.getAppConfOperations()
          .getComponent(SliderKeys.COMPONENT_AM).get(SliderXmlConfKeys.KEY_AM_LOGIN_KEYTAB_NAME);
      log.info("No host keytab file path specified. Downloading keytab {}"
               + " from HDFS to perform login of using principal {}",
               keytabName, principal);
      // download keytab to local, protected directory
      localKeytabFile = getFileFromFileSystem(fs, keytabName);
    } else {
      log.info("Leveraging host keytab file {} to login  principal {}",
               keytabFullPath, principal);
      localKeytabFile = new File(keytabFullPath);
    }
    return localKeytabFile;
  }

  /**
   * Download the keytab file from FileSystem to local file.
   * @param fs
   * @param keytabName
   * @return
   * @throws SliderException
   * @throws IOException
   */
  protected File getFileFromFileSystem(SliderFileSystem fs, String keytabName)
      throws SliderException, IOException {
    File keytabDestinationDir = new File(
        FileUtils.getTempDirectory().getAbsolutePath() +
        "/keytab" + System.currentTimeMillis());
    if (!keytabDestinationDir.mkdirs()) {
      throw new SliderException("Unable to create local keytab directory");
    }
    RawLocalFileSystem fileSystem = new RawLocalFileSystem();
    // allow app user to access local keytab dir
    FsPermission permissions = new FsPermission(FsAction.ALL, FsAction.NONE,
                                                FsAction.NONE);
    fileSystem.setPermission(new Path(keytabDestinationDir.getAbsolutePath()),
                             permissions);

    Path keytabPath = fs.buildKeytabPath(keytabName, clusterName);
    File localKeytabFile = new File(keytabDestinationDir, keytabName);
    FileUtil.copy(fs.getFileSystem(), keytabPath,
                  localKeytabFile,
                  false, configuration);
    // set permissions on actual keytab file to be read-only for user
    permissions = new FsPermission(FsAction.READ, FsAction.NONE, FsAction.NONE);
    fileSystem.setPermission(new Path(localKeytabFile.getAbsolutePath()),
                             permissions);
    return localKeytabFile;
  }
}
